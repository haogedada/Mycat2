/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.calcite.prepare;

import com.google.common.collect.ImmutableList;
import io.mycat.calcite.MycatCalciteDataContext;
import io.mycat.calcite.MycatCalciteSupport;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.RelOptTable.ViewExpander;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.metadata.CachingRelMetadataProvider;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.*;
import org.apache.calcite.util.Pair;

import java.io.Reader;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Implementation of {@link org.apache.calcite.tools.Planner}.
 */
public class PlannerImpl implements Planner, ViewExpander {
    private static final SqlOperatorTable operatorTable;
    private static final ImmutableList<Program> programs;
    private static final RelOptCostFactory costFactory;
    private static final Context context;
    private static  final CalciteConnectionConfig connectionConfig;
    private final MycatCalciteDataContext config;

    CalciteCatalogReader reader = null;
    /**
     * Holds the trait definitions to be registered with planner. May be null.
     */
    private static final ImmutableList<RelTraitDef> traitDefs;

    private static final SqlParser.Config parserConfig;
    private static final SqlValidator.Config sqlValidatorConfig;
    private static final SqlToRelConverter.Config sqlToRelConverterConfig;
    private static final SqlRexConvertletTable convertletTable;

    private State state;

    // set in STATE_1_RESET
    private boolean open;

    // set in STATE_2_READY
    private SchemaPlus defaultSchema;
    private final static JavaTypeFactory typeFactory = MycatCalciteSupport.INSTANCE.TypeFactory;
    private RelOptPlanner planner;
    private final static RexExecutor executor;

    // set in STATE_4_VALIDATE
    private SqlValidator validator;
    private SqlNode validatedSqlNode;

    // set in STATE_5_CONVERT
    private RelRoot root;

    static {
        FrameworkConfig frameworkConfig = MycatCalciteSupport.INSTANCE.config;
        costFactory = frameworkConfig.getCostFactory();
        operatorTable = frameworkConfig.getOperatorTable();
        programs = frameworkConfig.getPrograms();
        parserConfig = frameworkConfig.getParserConfig();
        sqlValidatorConfig = frameworkConfig.getSqlValidatorConfig();
        sqlToRelConverterConfig = frameworkConfig.getSqlToRelConverterConfig();
        traitDefs = frameworkConfig.getTraitDefs();
        convertletTable = frameworkConfig.getConvertletTable();
        executor = frameworkConfig.getExecutor();
        context = frameworkConfig.getContext();
        connectionConfig = MycatCalciteSupport.INSTANCE.calciteConnectionConfig;
    }

    /**
     * Creates a planner. Not a public API; call
     * {@link org.apache.calcite.tools.Frameworks#getPlanner} instead.
     */
    public PlannerImpl(MycatCalciteDataContext config) {
        defaultSchema = config.getDefaultSchema();
        this.config = config;
        this.state = State.STATE_0_CLOSED;
        reset();
    }

    /**
     * Gets a user defined config and appends default connection values
     */
//    private CalciteConnectionConfig connConfig() {
//
//        return config;
//    }

    /**
     * Makes sure that the state is at least the given state.
     */
    private void ensure(State state) {

    }

    public RelTraitSet getEmptyTraitSet() {
        return planner.emptyTraitSet();
    }

    public void close() {
        open = false;
        state = State.STATE_0_CLOSED;
    }

    public void reset() {
        ensure(State.STATE_0_CLOSED);
        open = true;
        state = State.STATE_1_RESET;
    }

    public void ready() {
        switch (state) {
            case STATE_0_CLOSED:
                reset();
        }
        ensure(State.STATE_1_RESET);
        planner = new VolcanoPlanner(costFactory, context);
        RelOptUtil.registerDefaultRules(planner,
                connectionConfig.materializationsEnabled(),
                Hook.ENABLE_BINDABLE.get(false));
        planner.setExecutor(executor);

        state = State.STATE_2_READY;

        // If user specify own traitDef, instead of default default trait,
        // register the trait def specified in traitDefs.
        if (this.traitDefs == null) {
            planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
            if (CalciteSystemProperty.ENABLE_COLLATION_TRAIT.value()) {
                planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
            }
        } else {
            for (RelTraitDef def : this.traitDefs) {
                planner.addRelTraitDef(def);
            }
        }
    }

    public SqlNode parse(final Reader reader) throws SqlParseException {
        switch (state) {
            case STATE_0_CLOSED:
            case STATE_1_RESET:
                ready();
        }
        ensure(State.STATE_2_READY);
        SqlParser parser = SqlParser.create(reader, parserConfig);
        SqlNode sqlNode = parser.parseStmt();
        state = State.STATE_3_PARSED;
        return sqlNode;
    }
    public void parse() throws SqlParseException {
        switch (state) {
            case STATE_0_CLOSED:
            case STATE_1_RESET:
                ready();
        }
        ensure(State.STATE_2_READY);
        state = State.STATE_3_PARSED;
    }
    public SqlNode validate(SqlNode sqlNode) throws ValidationException {
        ensure(State.STATE_3_PARSED);
        this.validator = createSqlValidator(createCatalogReader());
        try {
            validatedSqlNode = validator.validate(sqlNode);
        } catch (RuntimeException e) {
            throw new ValidationException(e);
        }
        state = State.STATE_4_VALIDATED;
        return validatedSqlNode;
    }

    public Pair<SqlNode, RelDataType> validateAndGetType(SqlNode sqlNode)
            throws ValidationException {
        final SqlNode validatedNode = this.validate(sqlNode);
        final RelDataType type =
                this.validator.getValidatedNodeType(validatedNode);
        return Pair.of(validatedNode, type);
    }

    @SuppressWarnings("deprecation")
    public final RelNode convert(SqlNode sql) {
        return rel(sql).rel;
    }

    public RelRoot rel(SqlNode sql) {
        ensure(State.STATE_4_VALIDATED);
        assert validatedSqlNode != null;
        final RexBuilder rexBuilder = createRexBuilder();
        final RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);
        final SqlToRelConverter.Config config = SqlToRelConverter.configBuilder()
                .withConfig(sqlToRelConverterConfig)
                .withTrimUnusedFields(false)
                .build();
        final SqlToRelConverter sqlToRelConverter =
                new SqlToRelConverter(this, validator,
                        createCatalogReader(), cluster, convertletTable, config);
        root =
                sqlToRelConverter.convertQuery(validatedSqlNode, false, true);
        root = root.withRel(sqlToRelConverter.flattenTypes(root.rel, true));
        final RelBuilder relBuilder =
                config.getRelBuilderFactory().create(cluster, null);
        root = root.withRel(
                RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
        state = State.STATE_5_CONVERTED;
        return root;
    }


    /**
     * @deprecated Now {@link PlannerImpl} implements {@link ViewExpander}
     * directly.
     */
    @Deprecated
    public class ViewExpanderImpl implements ViewExpander {
        ViewExpanderImpl() {
        }

        public RelRoot expandView(RelDataType rowType, String queryString,
                                  List<String> schemaPath, List<String> viewPath) {
            return PlannerImpl.this.expandView(rowType, queryString, schemaPath,
                    viewPath);
        }
    }

    @Override
    public RelRoot expandView(RelDataType rowType, String queryString,
                              List<String> schemaPath, List<String> viewPath) {
        if (planner == null) {
            ready();
        }
        SqlParser parser = SqlParser.create(queryString, parserConfig);
        SqlNode sqlNode;
        try {
            sqlNode = parser.parseQuery();
        } catch (SqlParseException e) {
            throw new RuntimeException("parse failed", e);
        }

        final CalciteCatalogReader catalogReader =
                createCatalogReader().withSchemaPath(schemaPath);
        final SqlValidator validator = createSqlValidator(catalogReader);

        final RexBuilder rexBuilder = createRexBuilder();
        final RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);
        final SqlToRelConverter.Config config = SqlToRelConverter
                .configBuilder()
                .withConfig(sqlToRelConverterConfig)
                .withTrimUnusedFields(false)
                .build();
        final SqlToRelConverter sqlToRelConverter =
                new SqlToRelConverter(this, validator,
                        catalogReader, cluster, convertletTable, config);

        final RelRoot root =
                sqlToRelConverter.convertQuery(sqlNode, true, false);
        final RelRoot root2 =
                root.withRel(sqlToRelConverter.flattenTypes(root.rel, true));
        final RelBuilder relBuilder =
                config.getRelBuilderFactory().create(cluster, null);
        return root2.withRel(
                RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
    }

    // CalciteCatalogReader is stateless; no need to store one
    public CalciteCatalogReader createCatalogReader() {
        return createCalciteCatalogReader();
    }
    public CalciteCatalogReader createCalciteCatalogReader() {
        if (reader == null) {
            List<String> path = Collections.emptyList();
            if (defaultSchema != null) {
                String name = defaultSchema.getName();
                path = Collections.singletonList(name);
            }
            reader = new CalciteCatalogReader(
                    CalciteSchema.from(config.getRootSchema()),
                    path,
                    MycatCalciteSupport.INSTANCE.TypeFactory, MycatCalciteSupport.INSTANCE.getCalciteConnectionConfig());
        }
        return reader;
    }
    private SqlValidator createSqlValidator(CalciteCatalogReader catalogReader) {
        final SqlOperatorTable opTab =
                ChainedSqlOperatorTable.of(operatorTable, catalogReader);
        return new CalciteSqlValidator(opTab,
                catalogReader,
                typeFactory,
                sqlValidatorConfig
                        .withDefaultNullCollation(connectionConfig.defaultNullCollation())
                        .withLenientOperatorLookup(connectionConfig.lenientOperatorLookup())
                        .withSqlConformance(connectionConfig.conformance())
                        .withIdentifierExpansion(true));
    }

    private static SchemaPlus rootSchema(SchemaPlus schema) {
        for (; ; ) {
            if (schema.getParentSchema() == null) {
                return schema;
            }
            schema = schema.getParentSchema();
        }
    }

    // RexBuilder is stateless; no need to store one
    private RexBuilder createRexBuilder() {
        return new RexBuilder(typeFactory);
    }

    public JavaTypeFactory getTypeFactory() {
        return typeFactory;
    }

    public RelNode transform(int ruleSetIndex, RelTraitSet requiredOutputTraits,
                             RelNode rel) {
        ensure(State.STATE_5_CONVERTED);
        rel.getCluster().setMetadataProvider(
                new CachingRelMetadataProvider(
                        rel.getCluster().getMetadataProvider(),
                        rel.getCluster().getPlanner()));
        Program program = programs.get(ruleSetIndex);
        return program.run(planner, rel, requiredOutputTraits, ImmutableList.of(),
                ImmutableList.of());
    }

    /**
     * Stage of a statement in the query-preparation lifecycle.
     */
    private enum State {
        STATE_0_CLOSED {
            @Override
            void from(PlannerImpl planner) {
                planner.close();
            }
        },
        STATE_1_RESET {
            @Override
            void from(PlannerImpl planner) {
                planner.ensure(STATE_0_CLOSED);
                planner.reset();
            }
        },
        STATE_2_READY {
            @Override
            void from(PlannerImpl planner) {
                STATE_1_RESET.from(planner);
                planner.ready();
            }
        },
        STATE_3_PARSED,
        STATE_4_VALIDATED,
        STATE_5_CONVERTED;

        /**
         * Moves planner's state to this state. This must be a higher state.
         */
        void from(PlannerImpl planner) {
            throw new IllegalArgumentException("cannot move from " + planner.state
                    + " to " + this);
        }
    }
}
