/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.cost.CachingCostProvider;
import io.prestosql.cost.CachingStatsProvider;
import io.prestosql.cost.CostCalculator;
import io.prestosql.cost.CostProvider;
import io.prestosql.cost.StatsAndCosts;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.cost.StatsProvider;
import io.prestosql.event.QueryMonitor;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.NewTableLayout;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.metadata.TableHandle;
import io.prestosql.metadata.TableMetadata;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.statistics.TableStatisticsMetadata;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.analyzer.Analysis;
import io.prestosql.sql.analyzer.Field;
import io.prestosql.sql.analyzer.RelationId;
import io.prestosql.sql.analyzer.RelationType;
import io.prestosql.sql.analyzer.Scope;
import io.prestosql.sql.planner.StatisticsAggregationPlanner.TableStatisticAggregation;
import io.prestosql.sql.planner.optimizations.PlanOptimizer;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.DeleteNode;
import io.prestosql.sql.planner.plan.ExplainAnalyzeNode;
import io.prestosql.sql.planner.plan.LimitNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.planner.plan.StatisticAggregations;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableScanNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.ValuesNode;
import io.prestosql.sql.planner.sanity.PlanSanityChecker;
import io.prestosql.sql.tree.Analyze;
import io.prestosql.sql.tree.Cast;
import io.prestosql.sql.tree.CreateTableAsSelect;
import io.prestosql.sql.tree.Delete;
import io.prestosql.sql.tree.Explain;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.Identifier;
import io.prestosql.sql.tree.Insert;
import io.prestosql.sql.tree.LambdaArgumentDeclaration;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.NodeRef;
import io.prestosql.sql.tree.NullLiteral;
import io.prestosql.sql.tree.Query;
import io.prestosql.sql.tree.Statement;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.util.*;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map.Entry;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Streams.zip;
import static io.prestosql.spi.StandardErrorCode.NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.statistics.TableStatisticType.ROW_COUNT;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.sql.planner.plan.AggregationNode.singleGroupingSet;
import static io.prestosql.sql.planner.plan.TableWriterNode.CreateReference;
import static io.prestosql.sql.planner.plan.TableWriterNode.InsertReference;
import static io.prestosql.sql.planner.plan.TableWriterNode.WriterTarget;
import static io.prestosql.sql.planner.sanity.PlanSanityChecker.DISTRIBUTED_PLAN_SANITY_CHECKER;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class LogicalPlanner
{
    public enum Stage
    {
        CREATED, OPTIMIZED, OPTIMIZED_AND_VALIDATED
    }

    private final PlanNodeIdAllocator idAllocator;

    private final Session session;
    private final List<PlanOptimizer> planOptimizers;
    private final PlanSanityChecker planSanityChecker;
    private final SymbolAllocator symbolAllocator = new SymbolAllocator();
    private final Metadata metadata;
    private final TypeAnalyzer typeAnalyzer;
    private final StatisticsAggregationPlanner statisticsAggregationPlanner;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final WarningCollector warningCollector;

    Logger log = Logger.get(LogicalPlanner.class);

    public LogicalPlanner(Session session,
                          List<PlanOptimizer> planOptimizers,
                          PlanNodeIdAllocator idAllocator,
                          Metadata metadata,
                          TypeAnalyzer typeAnalyzer,
                          StatsCalculator statsCalculator,
                          CostCalculator costCalculator,
                          WarningCollector warningCollector)
    {
        this(session, planOptimizers, DISTRIBUTED_PLAN_SANITY_CHECKER, idAllocator, metadata, typeAnalyzer, statsCalculator, costCalculator, warningCollector);
    }

    public LogicalPlanner(Session session,
                          List<PlanOptimizer> planOptimizers,
                          PlanSanityChecker planSanityChecker,
                          PlanNodeIdAllocator idAllocator,
                          Metadata metadata,
                          TypeAnalyzer typeAnalyzer,
                          StatsCalculator statsCalculator,
                          CostCalculator costCalculator,
                          WarningCollector warningCollector)
    {
        this.session = requireNonNull(session, "session is null");
        this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
        this.planSanityChecker = requireNonNull(planSanityChecker, "planSanityChecker is null");
        this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
        this.statisticsAggregationPlanner = new StatisticsAggregationPlanner(symbolAllocator, metadata);
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    public Plan plan(Analysis analysis)
    {
        return plan(analysis, Stage.OPTIMIZED_AND_VALIDATED);
    }

    public Plan plan(Analysis analysis, Stage stage)
    {
        PlanNode root = planStatement(analysis, analysis.getStatement());

        Collection<TableHandle> tableHandles = analysis.getTables();
        String user = session.getUser();

        //jdbc valid
        if (session.getSource().toString().equals("Optional[presto-jdbc]")) {
            String passwordRaw = session.getClientInfo().toString();
            if (passwordRaw == null || passwordRaw.length() <= 0 || passwordRaw.equals("Optional.empty")) {
                log.info(format("%s user:%s Query is not Valid without ClientInfo ", session.getQueryId(), user));
                root = null;
                requireNonNull(root, format("hi %s , Query returned a null plan Because password needed . " +
                        "Usage: setClientInfo(\"ClientInfo\", userpassword)", user));
            }
            int indexLeft = passwordRaw.lastIndexOf('[');
            int indexRight = passwordRaw.indexOf(']');
            String password = passwordRaw.substring(indexLeft + 1, indexRight);
            if (password == null || password.length() <= 0) {
                log.info(format("%s user:%s Query is not Valid without password  ", session.getQueryId(), user));
                root = null;
                requireNonNull(root, format("hi %s , Query returned a null plan Because password needed . ", user));
            }

            if (!validate(user, password)) {
                log.info(format("%s user:%s The password does not match your account , " +
                        "or There was a network problem during validation, try again later ", session.getQueryId(), user));
                root = null;
                requireNonNull(root, format("hi %s , The password does not match your account , " +
                        " or There was a network problem during validation, try again later ", user));
            }
        }

        String operatorType = "";
        if (analysis.getStatement() instanceof Query || analysis.getStatement() instanceof Analyze ||
                analysis.getStatement() instanceof Explain) {
            operatorType = "select";
        }
        if (analysis.getStatement() instanceof Insert) {
            operatorType = "insert";
        }
        if (analysis.getStatement() instanceof Delete || analysis.getStatement() instanceof CreateTableAsSelect) {
            operatorType = "all";
        }
        if ("select".equals(operatorType) || "insert".equals(operatorType) || "all".equals(operatorType)) {
            List<String> tables = new ArrayList<>();
            for (TableHandle th : tableHandles) {
                String database;
                String table;
                String connectorHandle = th.getConnectorHandle().toString();

                if(connectorHandle.contains(":")){
                    database = connectorHandle.split(":")[0];
                    table = connectorHandle.split(":")[1];
                }else {
                    database = th.toString().split("\\.")[0];
                    table = th.toString().split("\\.")[1];
                }
                tables.add(database + "::" + table + "::" + operatorType);
            }
            QueryMonitor.mapTable.put(session.getQueryId().toString(), tables);
        }

        planSanityChecker.validateIntermediatePlan(root, session, metadata, typeAnalyzer, symbolAllocator.getTypes(), warningCollector);

        if (stage.ordinal() >= Stage.OPTIMIZED.ordinal()) {
            for (PlanOptimizer optimizer : planOptimizers) {
                root = optimizer.optimize(root, session, symbolAllocator.getTypes(), symbolAllocator, idAllocator, warningCollector);
                requireNonNull(root, format("%s returned a null plan", optimizer.getClass().getName()));
            }
        }

        if (stage.ordinal() >= Stage.OPTIMIZED_AND_VALIDATED.ordinal()) {
            // make sure we produce a valid plan after optimizations run. This is mainly to catch programming errors
            planSanityChecker.validateFinalPlan(root, session, metadata, typeAnalyzer, symbolAllocator.getTypes(), warningCollector);
        }

        TypeProvider types = symbolAllocator.getTypes();
        StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, session, types);
        CostProvider costProvider = new CachingCostProvider(costCalculator, statsProvider, Optional.empty(), session, types);
        return new Plan(root, types, StatsAndCosts.create(root, statsProvider, costProvider));
    }

    /**
     * re_Fuction validate for AD by JDBC Query
     * Determine if the user is an valid user
     */
    public boolean validate(String user, String password) {
        boolean isLegal;
        String url = "LDAP://10.13.25.16:389/";
        String baseDn = "OU=data,OU=OS,OU=system users,DC=adc,DC=com";
        if (isUserID(user)) {
            log.info(format("%s :Individual users are not supported at the moment, please use AD public account", user));
            return false;
        }
        String dn = "CN=" + user + "," + baseDn;

        Properties props = new Properties();
        props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        props.put(Context.PROVIDER_URL, url);
        props.put(Context.SECURITY_PRINCIPAL, dn);
        props.put(Context.SECURITY_CREDENTIALS, password);

        try {

            InitialDirContext context = new InitialDirContext(props);
            isLegal = true;
            log.info(format("%s, congratulations  , AD Validate is successful", user));
        } catch (Exception e) {
            try {
                new InitialDirContext(props);
                isLegal = true;
            } catch (NamingException e1) {
                log.info(format("%s, sorry  , AD Validate is failed", user));
                isLegal = false;
                e.printStackTrace();
            }


        }
        return isLegal;
    }

    /**
     * re_Fuction isPerson.
     * Determine if the user is an individual user, true means the individual user ; false means the public user
     */
    public static boolean isUserID(String username) {
        boolean isNum = true;
        for (char c : username.toCharArray()) {
            if (c < '0' || c > '9') {
                isNum = false;
                return isNum;
            }
        }
        return isNum;
    }

    public PlanNode planStatement(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect && analysis.isCreateTableAsSelectNoOp()) {
            checkState(analysis.getCreateTableDestination().isPresent(), "Table destination is missing");
            Symbol symbol = symbolAllocator.newSymbol("rows", BIGINT);
            PlanNode source = new ValuesNode(idAllocator.getNextId(), ImmutableList.of(symbol), ImmutableList.of(ImmutableList.of(new LongLiteral("0"))));
            return new OutputNode(idAllocator.getNextId(), source, ImmutableList.of("rows"), ImmutableList.of(symbol));
        }
        return createOutputPlan(planStatementWithoutOutput(analysis, statement), analysis);
    }

    private RelationPlan planStatementWithoutOutput(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect) {
            if (analysis.isCreateTableAsSelectNoOp()) {
                throw new PrestoException(NOT_SUPPORTED, "CREATE TABLE IF NOT EXISTS is not supported in this context " + statement.getClass().getSimpleName());
            }
            return createTableCreationPlan(analysis, ((CreateTableAsSelect) statement).getQuery());
        }
        else if (statement instanceof Analyze) {
            return createAnalyzePlan(analysis, (Analyze) statement);
        }
        else if (statement instanceof Insert) {
            checkState(analysis.getInsert().isPresent(), "Insert handle is missing");
            return createInsertPlan(analysis, (Insert) statement);
        }
        else if (statement instanceof Delete) {
            return createDeletePlan(analysis, (Delete) statement);
        }
        else if (statement instanceof Query) {
            return createRelationPlan(analysis, (Query) statement);
        }
        else if (statement instanceof Explain && ((Explain) statement).isAnalyze()) {
            return createExplainAnalyzePlan(analysis, (Explain) statement);
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported statement type " + statement.getClass().getSimpleName());
        }
    }

    private RelationPlan createExplainAnalyzePlan(Analysis analysis, Explain statement)
    {
        RelationPlan underlyingPlan = planStatementWithoutOutput(analysis, statement.getStatement());
        PlanNode root = underlyingPlan.getRoot();
        Scope scope = analysis.getScope(statement);
        Symbol outputSymbol = symbolAllocator.newSymbol(scope.getRelationType().getFieldByIndex(0));
        root = new ExplainAnalyzeNode(idAllocator.getNextId(), root, outputSymbol, statement.isVerbose());
        return new RelationPlan(root, scope, ImmutableList.of(outputSymbol));
    }

    private RelationPlan createAnalyzePlan(Analysis analysis, Analyze analyzeStatement)
    {
        TableHandle targetTable = analysis.getAnalyzeTarget().get();

        // Plan table scan
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTable);
        ImmutableList.Builder<Symbol> tableScanOutputs = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, ColumnHandle> symbolToColumnHandle = ImmutableMap.builder();
        ImmutableMap.Builder<String, Symbol> columnNameToSymbol = ImmutableMap.builder();
        TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTable);
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            Symbol symbol = symbolAllocator.newSymbol(column.getName(), column.getType());
            tableScanOutputs.add(symbol);
            symbolToColumnHandle.put(symbol, columnHandles.get(column.getName()));
            columnNameToSymbol.put(column.getName(), symbol);
        }

        TableStatisticsMetadata tableStatisticsMetadata = metadata.getStatisticsCollectionMetadata(
                session,
                targetTable.getCatalogName().getCatalogName(),
                tableMetadata.getMetadata());

        TableStatisticAggregation tableStatisticAggregation = statisticsAggregationPlanner.createStatisticsAggregation(tableStatisticsMetadata, columnNameToSymbol.build());
        StatisticAggregations statisticAggregations = tableStatisticAggregation.getAggregations();
        List<Symbol> groupingSymbols = statisticAggregations.getGroupingSymbols();

        PlanNode planNode = new StatisticsWriterNode(
                idAllocator.getNextId(),
                new AggregationNode(
                        idAllocator.getNextId(),
                        TableScanNode.newInstance(idAllocator.getNextId(), targetTable, tableScanOutputs.build(), symbolToColumnHandle.build()),
                        statisticAggregations.getAggregations(),
                        singleGroupingSet(groupingSymbols),
                        ImmutableList.of(),
                        AggregationNode.Step.SINGLE,
                        Optional.empty(),
                        Optional.empty()),
                new StatisticsWriterNode.WriteStatisticsReference(targetTable),
                symbolAllocator.newSymbol("rows", BIGINT),
                tableStatisticsMetadata.getTableStatistics().contains(ROW_COUNT),
                tableStatisticAggregation.getDescriptor());
        return new RelationPlan(planNode, analysis.getScope(analyzeStatement), planNode.getOutputSymbols());
    }

    private RelationPlan createTableCreationPlan(Analysis analysis, Query query)
    {
        QualifiedObjectName destination = analysis.getCreateTableDestination().get();

        RelationPlan plan = createRelationPlan(analysis, query);

        ConnectorTableMetadata tableMetadata = createTableMetadata(
                destination,
                getOutputTableColumns(plan, analysis.getColumnAliases()),
                analysis.getCreateTableProperties(),
                analysis.getParameters(),
                analysis.getCreateTableComment());
        Optional<NewTableLayout> newTableLayout = metadata.getNewTableLayout(session, destination.getCatalogName(), tableMetadata);

        List<String> columnNames = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, destination.getCatalogName(), tableMetadata);

        return createTableWriterPlan(
                analysis,
                plan,
                new CreateReference(destination.getCatalogName(), tableMetadata, newTableLayout),
                columnNames,
                newTableLayout,
                statisticsMetadata);
    }

    private RelationPlan createInsertPlan(Analysis analysis, Insert insertStatement)
    {
        Analysis.Insert insert = analysis.getInsert().get();

        TableMetadata tableMetadata = metadata.getTableMetadata(session, insert.getTarget());

        List<ColumnMetadata> visibleTableColumns = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .collect(toImmutableList());
        List<String> visibleTableColumnNames = visibleTableColumns.stream()
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        RelationPlan plan = createRelationPlan(analysis, insertStatement.getQuery());

        Map<String, ColumnHandle> columns = metadata.getColumnHandles(session, insert.getTarget());
        Assignments.Builder assignments = Assignments.builder();
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.isHidden()) {
                continue;
            }
            Symbol output = symbolAllocator.newSymbol(column.getName(), column.getType());
            int index = insert.getColumns().indexOf(columns.get(column.getName()));
            if (index < 0) {
                Expression cast = new Cast(new NullLiteral(), column.getType().getTypeSignature().toString());
                assignments.put(output, cast);
            }
            else {
                Symbol input = plan.getSymbol(index);
                Type tableType = column.getType();
                Type queryType = symbolAllocator.getTypes().get(input);

                if (queryType.equals(tableType) || metadata.getTypeManager().isTypeOnlyCoercion(queryType, tableType)) {
                    assignments.put(output, input.toSymbolReference());
                }
                else {
                    Expression cast = new Cast(input.toSymbolReference(), tableType.getTypeSignature().toString());
                    assignments.put(output, cast);
                }
            }
        }
        ProjectNode projectNode = new ProjectNode(idAllocator.getNextId(), plan.getRoot(), assignments.build());

        List<Field> fields = visibleTableColumns.stream()
                .map(column -> Field.newUnqualified(column.getName(), column.getType()))
                .collect(toImmutableList());
        Scope scope = Scope.builder().withRelationType(RelationId.anonymous(), new RelationType(fields)).build();

        plan = new RelationPlan(projectNode, scope, projectNode.getOutputSymbols());

        Optional<NewTableLayout> newTableLayout = metadata.getInsertLayout(session, insert.getTarget());
        String catalogName = insert.getTarget().getCatalogName().getCatalogName();
        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, catalogName, tableMetadata.getMetadata());

        return createTableWriterPlan(
                analysis,
                plan,
                new InsertReference(insert.getTarget()),
                visibleTableColumnNames,
                newTableLayout,
                statisticsMetadata);
    }

    private RelationPlan createTableWriterPlan(
            Analysis analysis,
            RelationPlan plan,
            WriterTarget target,
            List<String> columnNames,
            Optional<NewTableLayout> writeTableLayout,
            TableStatisticsMetadata statisticsMetadata)
    {
        PlanNode source = plan.getRoot();

        if (!analysis.isCreateTableAsSelectWithData()) {
            source = new LimitNode(idAllocator.getNextId(), source, 0L, false);
        }

        // todo this should be checked in analysis
        writeTableLayout.ifPresent(layout -> {
            if (!ImmutableSet.copyOf(columnNames).containsAll(layout.getPartitionColumns())) {
                throw new PrestoException(NOT_SUPPORTED, "INSERT must write all distribution columns: " + layout.getPartitionColumns());
            }
        });

        List<Symbol> symbols = plan.getFieldMappings();

        Optional<PartitioningScheme> partitioningScheme = Optional.empty();
        if (writeTableLayout.isPresent()) {
            List<Symbol> partitionFunctionArguments = new ArrayList<>();
            writeTableLayout.get().getPartitionColumns().stream()
                    .mapToInt(columnNames::indexOf)
                    .mapToObj(symbols::get)
                    .forEach(partitionFunctionArguments::add);

            List<Symbol> outputLayout = new ArrayList<>(symbols);

            partitioningScheme = Optional.of(new PartitioningScheme(
                    Partitioning.create(writeTableLayout.get().getPartitioning(), partitionFunctionArguments),
                    outputLayout));
        }

        if (!statisticsMetadata.isEmpty()) {
            verify(columnNames.size() == symbols.size(), "columnNames.size() != symbols.size(): %s and %s", columnNames, symbols);
            Map<String, Symbol> columnToSymbolMap = zip(columnNames.stream(), symbols.stream(), SimpleImmutableEntry::new)
                    .collect(toImmutableMap(Entry::getKey, Entry::getValue));

            TableStatisticAggregation result = statisticsAggregationPlanner.createStatisticsAggregation(statisticsMetadata, columnToSymbolMap);

            StatisticAggregations.Parts aggregations = result.getAggregations().createPartialAggregations(symbolAllocator, metadata.getFunctionRegistry());

            // partial aggregation is run within the TableWriteOperator to calculate the statistics for
            // the data consumed by the TableWriteOperator
            // final aggregation is run within the TableFinishOperator to summarize collected statistics
            // by the partial aggregation from all of the writer nodes
            StatisticAggregations partialAggregation = aggregations.getPartialAggregation();

            PlanNode writerNode = new TableWriterNode(
                    idAllocator.getNextId(),
                    source,
                    target,
                    symbolAllocator.newSymbol("partialrows", BIGINT),
                    symbolAllocator.newSymbol("fragment", VARBINARY),
                    symbols,
                    columnNames,
                    partitioningScheme,
                    Optional.of(partialAggregation),
                    Optional.of(result.getDescriptor().map(aggregations.getMappings()::get)));

            TableFinishNode commitNode = new TableFinishNode(
                    idAllocator.getNextId(),
                    writerNode,
                    target,
                    symbolAllocator.newSymbol("rows", BIGINT),
                    Optional.of(aggregations.getFinalAggregation()),
                    Optional.of(result.getDescriptor()));

            return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputSymbols());
        }

        TableFinishNode commitNode = new TableFinishNode(
                idAllocator.getNextId(),
                new TableWriterNode(
                        idAllocator.getNextId(),
                        source,
                        target,
                        symbolAllocator.newSymbol("partialrows", BIGINT),
                        symbolAllocator.newSymbol("fragment", VARBINARY),
                        symbols,
                        columnNames,
                        partitioningScheme,
                        Optional.empty(),
                        Optional.empty()),
                target,
                symbolAllocator.newSymbol("rows", BIGINT),
                Optional.empty(),
                Optional.empty());
        return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputSymbols());
    }

    private RelationPlan createDeletePlan(Analysis analysis, Delete node)
    {
        DeleteNode deleteNode = new QueryPlanner(analysis, symbolAllocator, idAllocator, buildLambdaDeclarationToSymbolMap(analysis, symbolAllocator), metadata, session)
                .plan(node);

        TableFinishNode commitNode = new TableFinishNode(
                idAllocator.getNextId(),
                deleteNode,
                deleteNode.getTarget(),
                symbolAllocator.newSymbol("rows", BIGINT),
                Optional.empty(),
                Optional.empty());

        return new RelationPlan(commitNode, analysis.getScope(node), commitNode.getOutputSymbols());
    }

    private PlanNode createOutputPlan(RelationPlan plan, Analysis analysis)
    {
        ImmutableList.Builder<Symbol> outputs = ImmutableList.builder();
        ImmutableList.Builder<String> names = ImmutableList.builder();

        int columnNumber = 0;
        RelationType outputDescriptor = analysis.getOutputDescriptor();
        for (Field field : outputDescriptor.getVisibleFields()) {
            String name = field.getName().orElse("_col" + columnNumber);
            names.add(name);

            int fieldIndex = outputDescriptor.indexOf(field);
            Symbol symbol = plan.getSymbol(fieldIndex);
            outputs.add(symbol);

            columnNumber++;
        }

        return new OutputNode(idAllocator.getNextId(), plan.getRoot(), names.build(), outputs.build());
    }

    private RelationPlan createRelationPlan(Analysis analysis, Query query)
    {
        return new RelationPlanner(analysis, symbolAllocator, idAllocator, buildLambdaDeclarationToSymbolMap(analysis, symbolAllocator), metadata, session)
                .process(query, null);
    }

    private ConnectorTableMetadata createTableMetadata(QualifiedObjectName table, List<ColumnMetadata> columns, Map<String, Expression> propertyExpressions, List<Expression> parameters, Optional<String> comment)
    {
        CatalogName catalogName = metadata.getCatalogHandle(session, table.getCatalogName())
                .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog does not exist: " + table.getCatalogName()));

        Map<String, Object> properties = metadata.getTablePropertyManager().getProperties(
                catalogName,
                table.getCatalogName(),
                propertyExpressions,
                session,
                metadata,
                parameters);

        return new ConnectorTableMetadata(table.asSchemaTableName(), columns, properties, comment);
    }

    private static List<ColumnMetadata> getOutputTableColumns(RelationPlan plan, Optional<List<Identifier>> columnAliases)
    {
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        int aliasPosition = 0;
        for (Field field : plan.getDescriptor().getVisibleFields()) {
            String columnName = columnAliases.isPresent() ? columnAliases.get().get(aliasPosition).getValue() : field.getName().get();
            columns.add(new ColumnMetadata(columnName, field.getType()));
            aliasPosition++;
        }
        return columns.build();
    }

    private static Map<NodeRef<LambdaArgumentDeclaration>, Symbol> buildLambdaDeclarationToSymbolMap(Analysis analysis, SymbolAllocator symbolAllocator)
    {
        Map<NodeRef<LambdaArgumentDeclaration>, Symbol> resultMap = new LinkedHashMap<>();
        for (Entry<NodeRef<Expression>, Type> entry : analysis.getTypes().entrySet()) {
            if (!(entry.getKey().getNode() instanceof LambdaArgumentDeclaration)) {
                continue;
            }
            NodeRef<LambdaArgumentDeclaration> lambdaArgumentDeclaration = NodeRef.of((LambdaArgumentDeclaration) entry.getKey().getNode());
            if (resultMap.containsKey(lambdaArgumentDeclaration)) {
                continue;
            }
            resultMap.put(lambdaArgumentDeclaration, symbolAllocator.newSymbol(lambdaArgumentDeclaration.getNode(), entry.getValue()));
        }
        return resultMap;
    }
}
