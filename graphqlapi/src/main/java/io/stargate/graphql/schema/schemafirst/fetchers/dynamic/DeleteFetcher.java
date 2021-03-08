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
package io.stargate.graphql.schema.schemafirst.fetchers.dynamic;

import com.google.common.collect.ImmutableMap;
import graphql.schema.DataFetchingEnvironment;
import io.stargate.auth.AuthenticationSubject;
import io.stargate.auth.AuthorizationService;
import io.stargate.auth.Scope;
import io.stargate.auth.SourceAPI;
import io.stargate.auth.TypedKeyValue;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.datastore.DataStore;
import io.stargate.db.datastore.DataStoreFactory;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.query.BoundDelete;
import io.stargate.db.query.Predicate;
import io.stargate.db.query.builder.AbstractBound;
import io.stargate.db.query.builder.BuiltCondition;
import io.stargate.db.schema.Keyspace;
import io.stargate.graphql.schema.schemafirst.processor.DeleteModel;
import io.stargate.graphql.schema.schemafirst.processor.EntityModel;
import io.stargate.graphql.schema.schemafirst.processor.FieldModel;
import io.stargate.graphql.schema.schemafirst.processor.MappingModel;
import io.stargate.graphql.schema.schemafirst.processor.OperationModel.ReturnType;
import io.stargate.graphql.schema.schemafirst.processor.OperationModel.SimpleReturnType;
import io.stargate.graphql.schema.schemafirst.processor.ResponsePayloadModel;
import io.stargate.graphql.schema.schemafirst.processor.ResponsePayloadModel.TechnicalField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class DeleteFetcher extends DynamicFetcher<Object> {

  private final DeleteModel model;

  public DeleteFetcher(
      DeleteModel model,
      MappingModel mappingModel,
      AuthorizationService authorizationService,
      DataStoreFactory dataStoreFactory) {
    super(mappingModel, authorizationService, dataStoreFactory);
    this.model = model;
  }

  @Override
  protected Object get(
      DataFetchingEnvironment environment,
      DataStore dataStore,
      AuthenticationSubject authenticationSubject)
      throws UnauthorizedException {

    EntityModel entityModel = model.getEntity();
    Keyspace keyspace = dataStore.schema().keyspace(entityModel.getKeyspaceName());
    Map<String, Object> input = environment.getArgument(model.getEntityArgumentName());
    Collection<BuiltCondition> conditions = new ArrayList<>();
    for (FieldModel column : entityModel.getPrimaryKey()) {
      String graphqlName = column.getGraphqlName();
      Object graphqlValue = input.get(graphqlName);
      if (graphqlValue == null) {
        throw new IllegalArgumentException("Missing value for field " + graphqlName);
      } else {
        conditions.add(
            BuiltCondition.of(
                column.getCqlName(),
                Predicate.EQ,
                toCqlValue(graphqlValue, column.getCqlType(), keyspace)));
      }
    }

    AbstractBound<?> query =
        dataStore
            .queryBuilder()
            .delete()
            .from(entityModel.getKeyspaceName(), entityModel.getCqlName())
            .where(conditions)
            .ifExists(model.ifExists())
            .build()
            .bind();

    authorizationService.authorizeDataWrite(
        authenticationSubject,
        entityModel.getKeyspaceName(),
        entityModel.getCqlName(),
        TypedKeyValue.forDML((BoundDelete) query),
        Scope.DELETE,
        SourceAPI.GRAPHQL);

    ResultSet resultSet = executeUnchecked(query, Optional.empty(), Optional.empty(), dataStore);
    boolean applied = !model.ifExists() || resultSet.one().getBoolean("[applied]");

    ReturnType returnType = model.getReturnType();
    if (returnType == SimpleReturnType.BOOLEAN) {
      return applied;
    } else {
      ResponsePayloadModel payload = (ResponsePayloadModel) returnType;
      if (payload.getTechnicalFields().contains(TechnicalField.APPLIED)) {
        return ImmutableMap.of(TechnicalField.APPLIED.getGraphqlName(), applied);
      } else {
        return Collections.emptyMap();
      }
    }
  }
}
