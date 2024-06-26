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
package io.stargate.it.http.graphql.graphqlfirst;

import com.datastax.oss.driver.api.core.CqlSession;
import io.stargate.it.BaseIntegrationTest;
import io.stargate.it.storage.StargateParameters;
import io.stargate.it.storage.StargateSpec;
import java.util.List;
import java.util.Map;

@StargateSpec(parametersCustomizer = "enableGraphqlFirst")
public abstract class GraphqlFirstTestBase extends BaseIntegrationTest {

  @SuppressWarnings("ununsed") // invoked by StargateSpec
  public static void enableGraphqlFirst(StargateParameters.Builder builder) {
    builder.putSystemProperties("stargate.graphql_first.enabled", "true");
  }

  protected static void deleteAllGraphqlSchemas(String keyspace, CqlSession session) {
    session
        .getMetadata()
        .getKeyspace("stargate_graphql")
        .flatMap(ks -> ks.getTable("schema_source"))
        .ifPresent(
            __ ->
                session.execute(
                    "DELETE FROM stargate_graphql.schema_source WHERE keyspace_name = ?",
                    keyspace));
  }

  @SuppressWarnings("unchecked")
  protected String getMappingErrors(Map<String, Object> errors) {
    Map<String, Object> value =
        ((Map<String, List<Map<String, Object>>>) errors.get("extensions"))
            .get("mappingErrors")
            .get(0);
    return (String) value.get("message");
  }
}
