/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.graphql.schema.schemafirst.migration;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import graphql.GraphqlErrorException;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Keyspace;
import io.stargate.db.schema.SchemaEntity;
import io.stargate.db.schema.Table;
import io.stargate.db.schema.UserDefinedType;
import io.stargate.graphql.schema.schemafirst.migration.CassandraSchemaHelper.Difference;
import io.stargate.graphql.schema.schemafirst.processor.EntityModel;
import io.stargate.graphql.schema.schemafirst.processor.MappingModel;
import io.stargate.graphql.schema.schemafirst.util.DirectedGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CassandraMigrator {

  /** Creates a new instance for a deployment initiated by the user. */
  public static CassandraMigrator forDeployment(MigrationStrategy strategy) {
    return new CassandraMigrator(strategy, false);
  }

  /**
   * Creates a new instance for a GraphQL schema already stored in the database (from a previous
   * deployment).
   */
  public static CassandraMigrator forPersisted() {
    return new CassandraMigrator(MigrationStrategy.USE_EXISTING, true);
  }

  private final MigrationStrategy strategy;
  private final boolean isPersisted;

  private CassandraMigrator(MigrationStrategy strategy, boolean isPersisted) {
    this.strategy = strategy;
    this.isPersisted = isPersisted;
  }

  /** @throws GraphqlErrorException if the CQL data model can't be migrated */
  public List<MigrationQuery> compute(MappingModel mappingModel, Keyspace keyspace) {
    List<MigrationQuery> queries = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    for (EntityModel entity : mappingModel.getEntities().values()) {
      switch (entity.getTarget()) {
        case TABLE:
          Table expectedTable = entity.getTableCqlSchema();
          Table actualTable = keyspace.table(entity.getCqlName());
          compute(
              expectedTable,
              actualTable,
              CassandraSchemaHelper::compare,
              CreateTableQuery::new,
              DropTableQuery::new,
              AddTableColumnQuery::new,
              "table",
              queries,
              errors);
          break;
        case UDT:
          UserDefinedType expectedType = entity.getUdtCqlSchema();
          UserDefinedType actualType = keyspace.userDefinedType(entity.getCqlName());
          compute(
              expectedType,
              actualType,
              CassandraSchemaHelper::compare,
              CreateUdtQuery::new,
              DropUdtQuery::new,
              AddUdtFieldQuery::new,
              "UDT",
              queries,
              errors);
          break;
        default:
          throw new AssertionError("Unexpected target " + entity.getTarget());
      }
    }
    if (!errors.isEmpty()) {
      String message =
          isPersisted
              ? "The GraphQL schema stored for this keyspace doesn't match the CQL data model "
                  + "anymore. It looks like the database was altered manually."
              : String.format(
                  "The GraphQL schema that you provided can't be mapped to the current CQL data "
                      + "model. Consider using a different migration strategy (current: %s).",
                  strategy);
      throw GraphqlErrorException.newErrorException()
          .message(message + " See details in `extensions.migrationErrors` below.")
          .extensions(ImmutableMap.of("migrationErrors", errors))
          .build();
    }
    return sortForExecution(queries);
  }

  private <T extends SchemaEntity> void compute(
      T expected,
      T actual,
      BiFunction<T, T, List<Difference>> comparator,
      Function<T, MigrationQuery> createBuilder,
      Function<T, MigrationQuery> dropBuilder,
      BiFunction<T, Column, MigrationQuery> addColumnBuilder,
      String entityType,
      List<MigrationQuery> queries,
      List<String> errors) {

    switch (strategy) {
      case USE_EXISTING:
        if (actual == null) {
          errors.add(String.format("Missing %s %s", entityType, expected.name()));
        } else {
          failIfMismatch(expected, actual, comparator, errors);
        }
        break;
      case ADD_MISSING_TABLES:
        if (actual == null) {
          queries.add(createBuilder.apply(expected));
        } else {
          failIfMismatch(expected, actual, comparator, errors);
        }
        break;
      case ADD_MISSING_TABLES_AND_COLUMNS:
        if (actual == null) {
          queries.add(createBuilder.apply(expected));
        } else {
          addMissingColumns(expected, actual, comparator, addColumnBuilder, queries, errors);
        }
        break;
      case DROP_AND_RECREATE_ALL:
        queries.add(dropBuilder.apply(expected));
        queries.add(createBuilder.apply(expected));
        break;
      case DROP_AND_RECREATE_IF_MISMATCH:
        if (actual == null) {
          queries.add(createBuilder.apply(expected));
        } else {
          List<Difference> differences = comparator.apply(expected, actual);
          if (!differences.isEmpty()) {
            queries.add(dropBuilder.apply(expected));
            queries.add(createBuilder.apply(expected));
          }
        }
        break;
      default:
        throw new AssertionError("Unexpected strategy " + strategy);
    }
  }

  private <T extends SchemaEntity> void failIfMismatch(
      T expected, T actual, BiFunction<T, T, List<Difference>> comparator, List<String> errors) {
    List<Difference> differences = comparator.apply(expected, actual);
    if (!differences.isEmpty()) {
      errors.addAll(
          differences.stream().map(Difference::toGraphqlMessage).collect(Collectors.toList()));
    }
  }

  private <T extends SchemaEntity> void addMissingColumns(
      T expected,
      T actual,
      BiFunction<T, T, List<Difference>> comparator,
      BiFunction<T, Column, MigrationQuery> addColumnBuilder,
      List<MigrationQuery> queries,
      List<String> errors) {
    List<Difference> differences = comparator.apply(expected, actual);
    if (!differences.isEmpty()) {
      List<Difference> blockers =
          differences.stream().filter(d -> !isAddableColumn(d)).collect(Collectors.toList());
      if (blockers.isEmpty()) {
        for (Difference difference : differences) {
          assert isAddableColumn(difference);
          queries.add(addColumnBuilder.apply(expected, difference.getColumn()));
        }
      } else {
        errors.addAll(
            blockers.stream().map(Difference::toGraphqlMessage).collect(Collectors.toList()));
      }
    }
  }

  private boolean isAddableColumn(Difference difference) {
    return difference.getType() == CassandraSchemaHelper.DifferenceType.MISSING_COLUMN
        && !difference.getColumn().isPartitionKey()
        && !difference.getColumn().isClusteringKey();
  }

  @VisibleForTesting
  static List<MigrationQuery> sortForExecution(List<MigrationQuery> queries) {
    if (queries.size() < 2) {
      return queries;
    }
    DirectedGraph<MigrationQuery> graph = new DirectedGraph<>(queries);
    for (MigrationQuery query1 : queries) {
      for (MigrationQuery query2 : queries) {
        if (query1 != query2 && query1.mustRunBefore(query2)) {
          graph.addEdge(query1, query2);
        }
      }
    }
    return graph.topologicalSort();
  }
}
