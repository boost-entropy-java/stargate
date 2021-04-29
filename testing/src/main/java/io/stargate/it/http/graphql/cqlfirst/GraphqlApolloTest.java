package io.stargate.it.http.graphql.cqlfirst;

import static io.stargate.it.MetricsTestsHelper.getMetricValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.apollographql.apollo.ApolloCall;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.ApolloMutationCall;
import com.apollographql.apollo.ApolloQueryCall;
import com.apollographql.apollo.api.CustomTypeAdapter;
import com.apollographql.apollo.api.CustomTypeValue;
import com.apollographql.apollo.api.Error;
import com.apollographql.apollo.api.Mutation;
import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.datastax.oss.driver.shaded.guava.common.base.Charsets;
import com.example.graphql.client.betterbotz.atomic.BulkInsertProductsAndOrdersWithAtomicMutation;
import com.example.graphql.client.betterbotz.atomic.BulkInsertProductsWithAtomicMutation;
import com.example.graphql.client.betterbotz.atomic.InsertOrdersAndBulkInsertProductsWithAtomicMutation;
import com.example.graphql.client.betterbotz.atomic.InsertOrdersWithAtomicMutation;
import com.example.graphql.client.betterbotz.atomic.ProductsAndOrdersMutation;
import com.example.graphql.client.betterbotz.orders.GetOrdersByValueQuery;
import com.example.graphql.client.betterbotz.orders.GetOrdersWithFilterQuery;
import com.example.graphql.client.betterbotz.products.BulkInsertProductsMutation;
import com.example.graphql.client.betterbotz.products.DeleteProductsMutation;
import com.example.graphql.client.betterbotz.products.GetProductsWithFilterQuery;
import com.example.graphql.client.betterbotz.products.GetProductsWithFilterQuery.Products;
import com.example.graphql.client.betterbotz.products.GetProductsWithFilterQuery.Value;
import com.example.graphql.client.betterbotz.products.InsertProductsMutation;
import com.example.graphql.client.betterbotz.products.UpdateProductsMutation;
import com.example.graphql.client.betterbotz.tuples.GetTuplesPkQuery;
import com.example.graphql.client.betterbotz.tuples.GetTuplesQuery;
import com.example.graphql.client.betterbotz.tuples.InsertTuplesMutation;
import com.example.graphql.client.betterbotz.tuples.InsertTuplesPkMutation;
import com.example.graphql.client.betterbotz.tuples.UpdateTuplesMutation;
import com.example.graphql.client.betterbotz.type.AUdtInput;
import com.example.graphql.client.betterbotz.type.BUdtInput;
import com.example.graphql.client.betterbotz.type.CustomType;
import com.example.graphql.client.betterbotz.type.MutationConsistency;
import com.example.graphql.client.betterbotz.type.MutationOptions;
import com.example.graphql.client.betterbotz.type.OrdersFilterInput;
import com.example.graphql.client.betterbotz.type.OrdersInput;
import com.example.graphql.client.betterbotz.type.ProductsFilterInput;
import com.example.graphql.client.betterbotz.type.ProductsInput;
import com.example.graphql.client.betterbotz.type.QueryConsistency;
import com.example.graphql.client.betterbotz.type.QueryOptions;
import com.example.graphql.client.betterbotz.type.StringFilterInput;
import com.example.graphql.client.betterbotz.type.TupleIntIntInput;
import com.example.graphql.client.betterbotz.type.Tuplx65_sPkInput;
import com.example.graphql.client.betterbotz.type.UdtsInput;
import com.example.graphql.client.betterbotz.type.UuidFilterInput;
import com.example.graphql.client.betterbotz.udts.GetUdtsQuery;
import com.example.graphql.client.betterbotz.udts.InsertUdtsMutation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import io.stargate.db.schema.Column;
import io.stargate.it.BaseOsgiIntegrationTest;
import io.stargate.it.http.RestUtils;
import io.stargate.it.http.graphql.GraphqlClient;
import io.stargate.it.http.graphql.TupleHelper;
import io.stargate.it.storage.StargateConnectionInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jcip.annotations.NotThreadSafe;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.http.HttpStatus;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Covers the CQL-first API using the apollo-runtime client library.
 *
 * <p>Note that this requires a lot of boilerplate:
 *
 * <ul>
 *   <li>If the schema has changed, update the `schema.json` files in `src/main/graphql`. You can
 *       use the query in `src/main/resources/introspection.graphql` (paste it into the graphql
 *       playground at ${STARGATE_HOST}:8080/playground).
 *   <li>If there are new operations, create corresponding descriptors in
 *       `src/main/graphql/betterbotz` or `src/main/graphql/schema`. For betterbotz, there's a cql
 *       schema file at src/main/resources/betterbotz.cql
 *   <li>Run the apollo-client-maven-plugin, which reads the descriptors and generates the
 *       corresponding Java types: `mvn generate-sources` (an IDE rebuild should also work). You can
 *       see generated code in `target/generated-sources/graphql-client`.
 * </ul>
 *
 * Other GraphQL tests generally use a more lightweight approach based on {@link GraphqlClient}.
 */
@NotThreadSafe
public class GraphqlApolloTest extends BaseOsgiIntegrationTest {
  private static final Logger logger = LoggerFactory.getLogger(GraphqlApolloTest.class);
  private static final Pattern GRAPHQL_OPERATIONS_METRIC_REGEXP =
      Pattern.compile(
          "(graphqlapi_io_dropwizard_jetty_MutableServletContextHandler_dispatches_count\\s*)(\\d+.\\d+)");

  private static CqlSession session;
  private static String authToken;
  private static StargateConnectionInfo stargate;
  private static final String keyspace = "betterbotz";
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static String host;

  @BeforeAll
  public static void setup(StargateConnectionInfo stargateInfo) throws Exception {
    stargate = stargateInfo;
    host = "http://" + stargateInfo.seedAddress();

    createSessionAndSchema();
    authToken = RestUtils.getAuthToken(stargate.seedAddress());
  }

  @AfterEach
  public void cleanUpProducts() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    getProducts(client, 100, Optional.empty())
        .flatMap(Products::getValues)
        .ifPresent(
            products ->
                products.forEach(p -> p.getId().ifPresent(id -> cleanupProduct(client, id))));
  }

  private static void createSessionAndSchema() throws Exception {
    session =
        CqlSession.builder()
            .withConfigLoader(
                DriverConfigLoader.programmaticBuilder()
                    .withDuration(DefaultDriverOption.REQUEST_TRACE_INTERVAL, Duration.ofSeconds(1))
                    .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMinutes(3))
                    .withDuration(
                        DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofMinutes(3))
                    .withDuration(
                        DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofMinutes(3))
                    .build())
            .withAuthCredentials("cassandra", "cassandra")
            .addContactPoint(new InetSocketAddress(stargate.seedAddress(), 9043))
            .withLocalDatacenter(stargate.datacenter())
            .build();

    // Create CQL schema using betterbotz.cql file
    InputStream inputStream =
        GraphqlApolloTest.class.getClassLoader().getResourceAsStream("betterbotz.cql");
    assertThat(inputStream).isNotNull();
    String queries = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
    assertThat(queries).isNotNull();

    for (String q : Splitter.on(';').split(queries)) {
      if (q.trim().equals("")) {
        continue;
      }
      session.execute(q);
    }

    PreparedStatement insert =
        session.prepare(
            String.format(
                "insert into %s.\"Orders\" (id, \"prodId\", \"prodName\", description, price,"
                    + "\"sellPrice\", \"customerName\", address) values (?, ?, ?, ?, ?, ?, ?, ?)",
                keyspace));

    session.execute(
        insert.bind(
            UUID.fromString("792d0a56-bb46-4bc2-bc41-5f4a94a83da9"),
            UUID.fromString("31047029-2175-43ce-9fdd-b3d568b19bb2"),
            "Medium Lift Arms",
            "Ordering some more arms for my construction bot.",
            BigDecimal.valueOf(3199.99),
            BigDecimal.valueOf(3119.99),
            "Janice Evernathy",
            "2101 Everplace Ave 3116"));

    session.execute(
        insert.bind(
            UUID.fromString("dd73afe2-9841-4ce1-b841-575b8be405c1"),
            UUID.fromString("31047029-2175-43ce-9fdd-b3d568b19bb5"),
            "Basic Task CPU",
            "Ordering replacement CPUs.",
            BigDecimal.valueOf(899.99),
            BigDecimal.valueOf(900.82),
            "John Doe",
            "123 Main St 67890"));
  }

  @Test
  public void getOrdersByValue() throws ExecutionException, InterruptedException {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    OrdersInput ordersInput = OrdersInput.builder().prodName("Medium Lift Arms").build();

    GetOrdersByValueQuery query = GetOrdersByValueQuery.builder().value(ordersInput).build();

    CompletableFuture<GetOrdersByValueQuery.Data> future = new CompletableFuture<>();
    ApolloQueryCall<Optional<GetOrdersByValueQuery.Data>> observable = client.query(query);
    observable.enqueue(queryCallback(future));

    GetOrdersByValueQuery.Data result = future.get();
    observable.cancel();

    assertThat(result.getOrders()).isPresent();

    GetOrdersByValueQuery.Orders orders = result.getOrders().get();

    assertThat(orders.getValues()).isPresent();
    List<GetOrdersByValueQuery.Value> valuesList = orders.getValues().get();

    GetOrdersByValueQuery.Value value = valuesList.get(0);
    assertThat(value.getId()).hasValue("792d0a56-bb46-4bc2-bc41-5f4a94a83da9");
    assertThat(value.getProdId()).hasValue("31047029-2175-43ce-9fdd-b3d568b19bb2");
    assertThat(value.getProdName()).hasValue("Medium Lift Arms");
    assertThat(value.getCustomerName()).hasValue("Janice Evernathy");
    assertThat(value.getAddress()).hasValue("2101 Everplace Ave 3116");
    assertThat(value.getDescription()).hasValue("Ordering some more arms for my construction bot.");
    assertThat(value.getPrice()).hasValue("3199.99");
    assertThat(value.getSellPrice()).hasValue("3119.99");
  }

  @Test
  public void getOrdersWithFilter() throws ExecutionException, InterruptedException {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    OrdersFilterInput filterInput =
        OrdersFilterInput.builder()
            .prodName(StringFilterInput.builder().eq("Basic Task CPU").build())
            .customerName(StringFilterInput.builder().eq("John Doe").build())
            .build();

    QueryOptions options =
        QueryOptions.builder().consistency(QueryConsistency.LOCAL_QUORUM).build();

    GetOrdersWithFilterQuery query =
        GetOrdersWithFilterQuery.builder().filter(filterInput).options(options).build();

    CompletableFuture<GetOrdersWithFilterQuery.Data> future = new CompletableFuture<>();
    ApolloQueryCall<Optional<GetOrdersWithFilterQuery.Data>> observable = client.query(query);
    observable.enqueue(queryCallback(future));

    GetOrdersWithFilterQuery.Data result = future.get();
    observable.cancel();

    assertThat(result.getOrders()).isPresent();

    GetOrdersWithFilterQuery.Orders orders = result.getOrders().get();

    assertThat(orders.getValues()).isPresent();
    List<GetOrdersWithFilterQuery.Value> valuesList = orders.getValues().get();

    GetOrdersWithFilterQuery.Value value = valuesList.get(0);
    assertThat(value.getId()).hasValue("dd73afe2-9841-4ce1-b841-575b8be405c1");
    assertThat(value.getProdId()).hasValue("31047029-2175-43ce-9fdd-b3d568b19bb5");
    assertThat(value.getProdName()).hasValue("Basic Task CPU");
    assertThat(value.getCustomerName()).hasValue("John Doe");
    assertThat(value.getAddress()).hasValue("123 Main St 67890");
    assertThat(value.getDescription()).hasValue("Ordering replacement CPUs.");
    assertThat(value.getPrice()).hasValue("899.99");
    assertThat(value.getSellPrice()).hasValue("900.82");
  }

  @Test
  public void getOrdersWithFilterAndLimit() throws ExecutionException, InterruptedException {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    OrdersFilterInput filterInput =
        OrdersFilterInput.builder()
            .prodName(StringFilterInput.builder().eq("Basic Task CPU").build())
            .customerName(StringFilterInput.builder().eq("John Doe").build())
            .build();

    QueryOptions options =
        QueryOptions.builder().consistency(QueryConsistency.LOCAL_QUORUM).limit(1).build();

    GetOrdersWithFilterQuery query =
        GetOrdersWithFilterQuery.builder().filter(filterInput).options(options).build();

    CompletableFuture<GetOrdersWithFilterQuery.Data> future = new CompletableFuture<>();
    ApolloQueryCall<Optional<GetOrdersWithFilterQuery.Data>> observable = client.query(query);
    observable.enqueue(queryCallback(future));

    GetOrdersWithFilterQuery.Data result = future.get();
    observable.cancel();

    assertThat(result.getOrders()).isPresent();

    GetOrdersWithFilterQuery.Orders orders = result.getOrders().get();

    assertThat(orders.getValues()).isPresent();
    List<GetOrdersWithFilterQuery.Value> valuesList = orders.getValues().get();

    GetOrdersWithFilterQuery.Value value = valuesList.get(0);
    assertThat(value.getId()).hasValue("dd73afe2-9841-4ce1-b841-575b8be405c1");
    assertThat(value.getProdId()).hasValue("31047029-2175-43ce-9fdd-b3d568b19bb5");
    assertThat(value.getProdName()).hasValue("Basic Task CPU");
    assertThat(value.getCustomerName()).hasValue("John Doe");
    assertThat(value.getAddress()).hasValue("123 Main St 67890");
    assertThat(value.getDescription()).hasValue("Ordering replacement CPUs.");
    assertThat(value.getPrice()).hasValue("899.99");
    assertThat(value.getSellPrice()).hasValue("900.82");
  }

  @Test
  public void insertProducts() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    InsertProductsMutation.InsertProducts result = insertProduct(client, input);
    assertThat(result.getApplied()).hasValue(true);
    assertThat(result.getValue())
        .hasValueSatisfying(
            product -> {
              assertThat(product.getId()).hasValue(productId);
              assertThat(product.getName()).hasValue(input.name());
              assertThat(product.getPrice()).hasValue(input.price());
              assertThat(product.getCreated()).hasValue(input.created());
              assertThat(product.getDescription()).hasValue(input.description());
            });

    GetProductsWithFilterQuery.Value product = getProduct(client, productId);

    assertThat(product.getId()).hasValue(productId);
    assertThat(product.getName()).hasValue(input.name());
    assertThat(product.getPrice()).hasValue(input.price());
    assertThat(product.getCreated()).hasValue(input.created());
    assertThat(product.getDescription()).hasValue(input.description());
  }

  @Test
  public void insertProductsWithIfNotExists() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    InsertProductsMutation mutation =
        InsertProductsMutation.builder().value(input).ifNotExists(true).build();
    InsertProductsMutation.Data result = getObservable(client.mutate(mutation));

    assertThat(result.getInsertProducts())
        .hasValueSatisfying(
            insertProducts -> {
              assertThat(insertProducts.getApplied()).hasValue(true);
              assertThat(insertProducts.getValue())
                  .hasValueSatisfying(
                      value -> {
                        assertThat(value.getId()).hasValue(productId);
                      });
            });
  }

  @Test
  public void insertProductsDuplicateWithIfNotExists() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    InsertProductsMutation mutation =
        InsertProductsMutation.builder().value(input).ifNotExists(true).build();
    InsertProductsMutation.Data insertResult = getObservable(client.mutate(mutation));

    assertThat(insertResult.getInsertProducts())
        .hasValueSatisfying(
            insertProducts -> {
              assertThat(insertProducts.getApplied()).hasValue(true);
            });

    // then duplicate (change desc)
    ProductsInput duplicate =
        ProductsInput.builder()
            .id(productId)
            .name(input.name())
            .price(input.price())
            .created(input.created())
            .description("Normal legs but super shiny.")
            .build();

    InsertProductsMutation duplicateMutation =
        InsertProductsMutation.builder().value(duplicate).ifNotExists(true).build();
    InsertProductsMutation.Data duplicateResult = getObservable(client.mutate(duplicateMutation));

    assertThat(duplicateResult.getInsertProducts())
        .hasValueSatisfying(
            insertProducts -> {
              assertThat(insertProducts.getApplied()).hasValue(false);
              assertThat(insertProducts.getValue())
                  .hasValueSatisfying(
                      value -> {
                        assertThat(value.getDescription())
                            .hasValue(input.description()); // existing value returned
                      });
            });
  }

  public GetProductsWithFilterQuery.Value getProduct(ApolloClient client, String productId) {
    List<GetProductsWithFilterQuery.Value> valuesList = getProductValues(client, productId);
    return valuesList.get(0);
  }

  public List<Value> getProductValues(ApolloClient client, String productId) {
    ProductsFilterInput filterInput =
        ProductsFilterInput.builder().id(UuidFilterInput.builder().eq(productId).build()).build();

    QueryOptions options =
        QueryOptions.builder().consistency(QueryConsistency.LOCAL_QUORUM).build();

    GetProductsWithFilterQuery query =
        GetProductsWithFilterQuery.builder().filter(filterInput).options(options).build();

    GetProductsWithFilterQuery.Data result = getObservable(client.query(query));
    assertThat(result.getProducts()).isPresent();
    GetProductsWithFilterQuery.Products products = result.getProducts().get();
    assertThat(products.getValues()).isPresent();
    return products.getValues().get();
  }

  @Test
  public void bulkInsertProducts() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId1 = UUID.randomUUID().toString();
    String productId2 = UUID.randomUUID().toString();
    ProductsInput product1 =
        ProductsInput.builder()
            .id(productId1)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();
    ProductsInput product2 =
        ProductsInput.builder()
            .id(productId2)
            .name("Non-Legs")
            .price("1000.99")
            .created(now())
            .description("Non-legs.")
            .build();

    List<BulkInsertProductsMutation.BulkInsertProduct> bulkInsertedProducts =
        bulkInsertProducts(client, Arrays.asList(product1, product2));

    BulkInsertProductsMutation.BulkInsertProduct firstInsertedProduct = bulkInsertedProducts.get(0);
    BulkInsertProductsMutation.BulkInsertProduct secondInsertedProduct =
        bulkInsertedProducts.get(1);

    assertThat(firstInsertedProduct.getApplied().get()).isTrue();
    assertThat(firstInsertedProduct.getValue())
        .hasValueSatisfying(
            value -> {
              assertThat(value.getId()).hasValue(productId1);
            });

    assertThat(secondInsertedProduct.getApplied().get()).isTrue();
    assertThat(secondInsertedProduct.getValue())
        .hasValueSatisfying(
            value -> {
              assertThat(value.getId()).hasValue(productId2);
            });

    // retrieve from db
    GetProductsWithFilterQuery.Value product1Result = getProduct(client, productId1);

    assertThat(product1Result.getId()).hasValue(productId1);
    assertThat(product1Result.getName()).hasValue(product1.name());
    assertThat(product1Result.getPrice()).hasValue(product1.price());
    assertThat(product1Result.getCreated()).hasValue(product1.created());
    assertThat(product1Result.getDescription()).hasValue(product1.description());

    GetProductsWithFilterQuery.Value product2Result = getProduct(client, productId2);

    assertThat(product2Result.getId()).hasValue(productId2);
    assertThat(product2Result.getName()).hasValue(product2.name());
    assertThat(product2Result.getPrice()).hasValue(product2.price());
    assertThat(product2Result.getCreated()).hasValue(product2.created());
    assertThat(product2Result.getDescription()).hasValue(product2.description());
  }

  @Test
  public void bulkInsertProductsWithAtomic() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId1 = UUID.randomUUID().toString();
    String productId2 = UUID.randomUUID().toString();
    ProductsInput product1 =
        ProductsInput.builder()
            .id(productId1)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();
    ProductsInput product2 =
        ProductsInput.builder()
            .id(productId2)
            .name("Non-Legs")
            .price("1000.99")
            .created(now())
            .description("Non-legs.")
            .build();

    bulkInsertProductsWithAtomic(client, Arrays.asList(product1, product2));

    GetProductsWithFilterQuery.Value product1Result = getProduct(client, productId1);

    assertThat(product1Result.getId()).hasValue(productId1);
    assertThat(product1Result.getName()).hasValue(product1.name());
    assertThat(product1Result.getPrice()).hasValue(product1.price());
    assertThat(product1Result.getCreated()).hasValue(product1.created());
    assertThat(product1Result.getDescription()).hasValue(product1.description());

    GetProductsWithFilterQuery.Value product2Result = getProduct(client, productId2);

    assertThat(product2Result.getId()).hasValue(productId2);
    assertThat(product2Result.getName()).hasValue(product2.name());
    assertThat(product2Result.getPrice()).hasValue(product2.price());
    assertThat(product2Result.getCreated()).hasValue(product2.created());
    assertThat(product2Result.getDescription()).hasValue(product2.description());
  }

  @Test
  @DisplayName("Should execute multiple mutations including bulk with atomic directive")
  public void bulkInsertNProductsUsingBulkAndOrderWithAtomic() {
    String productId1 = UUID.randomUUID().toString();
    String productId2 = UUID.randomUUID().toString();
    String productName = "Shiny Legs";
    String description = "Normal legs but shiny.";
    ProductsInput product1 =
        ProductsInput.builder()
            .id(productId1)
            .name(productName)
            .price("3199.99")
            .created(now())
            .description(description)
            .build();
    ProductsInput product2 =
        ProductsInput.builder()
            .id(productId2)
            .name("Non-Legs")
            .price("1000.99")
            .created(now())
            .description("Non-legs.")
            .build();

    String customerName = "c1";
    OrdersInput order =
        OrdersInput.builder()
            .prodName(productName)
            .customerName(customerName)
            .price("3199.99")
            .description(description)
            .build();

    ApolloClient client = getApolloClient("/graphql/betterbotz");
    BulkInsertProductsAndOrdersWithAtomicMutation mutation =
        BulkInsertProductsAndOrdersWithAtomicMutation.builder()
            .values(Arrays.asList(product1, product2))
            .orderValue(order)
            .build();

    List<BulkInsertProductsAndOrdersWithAtomicMutation.Product> result =
        bulkInsertProductsAndOrdersWithAtomic(client, mutation).getProducts().get();
    BulkInsertProductsAndOrdersWithAtomicMutation.Product firstInsertedProduct = result.get(0);
    BulkInsertProductsAndOrdersWithAtomicMutation.Product secondInsertedProduct = result.get(1);

    assertThat(firstInsertedProduct.getApplied().get()).isTrue();
    assertThat(firstInsertedProduct.getValue())
        .hasValueSatisfying(
            value -> {
              assertThat(value.getId()).hasValue(productId1);
            });

    assertThat(secondInsertedProduct.getApplied().get()).isTrue();
    assertThat(secondInsertedProduct.getValue())
        .hasValueSatisfying(
            value -> {
              assertThat(value.getId()).hasValue(productId2);
            });

    // retrieve from db
    GetProductsWithFilterQuery.Value product1Result = getProduct(client, productId1);

    assertThat(product1Result.getId()).hasValue(productId1);
    assertThat(product1Result.getName()).hasValue(product1.name());
    assertThat(product1Result.getPrice()).hasValue(product1.price());
    assertThat(product1Result.getCreated()).hasValue(product1.created());
    assertThat(product1Result.getDescription()).hasValue(product1.description());

    GetProductsWithFilterQuery.Value product2Result = getProduct(client, productId2);

    assertThat(product2Result.getId()).hasValue(productId2);
    assertThat(product2Result.getName()).hasValue(product2.name());
    assertThat(product2Result.getPrice()).hasValue(product2.price());
    assertThat(product2Result.getCreated()).hasValue(product2.created());
    assertThat(product2Result.getDescription()).hasValue(product2.description());

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customerName, description);
  }

  @Test
  @DisplayName(
      "Should execute multiple mutations including bulk with more elements than selections with atomic directive")
  public void bulkInsertMoreProductsThanSelectionsUsingBulkAndOrderWithAtomic() {
    String productName = "Shiny Legs";
    String description = "Normal legs but shiny.";
    List<ProductsInput> productsInputs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      productsInputs.add(
          ProductsInput.builder()
              .id(UUID.randomUUID().toString())
              .name(productName)
              .price("3199.99")
              .created(now())
              .description(description)
              .build());
    }

    String customerName = "c1";
    OrdersInput order =
        OrdersInput.builder()
            .prodName(productName)
            .customerName(customerName)
            .price("3199.99")
            .description(description)
            .build();

    ApolloClient client = getApolloClient("/graphql/betterbotz");
    BulkInsertProductsAndOrdersWithAtomicMutation mutation =
        BulkInsertProductsAndOrdersWithAtomicMutation.builder()
            .values(productsInputs)
            .orderValue(order)
            .build();

    bulkInsertProductsAndOrdersWithAtomic(client, mutation);

    for (ProductsInput product : productsInputs) {
      GetProductsWithFilterQuery.Value product1Result = getProduct(client, (String) product.id());

      assertThat(product1Result.getId()).hasValue(product.id());
      assertThat(product1Result.getName()).hasValue(product.name());
      assertThat(product1Result.getPrice()).hasValue(product.price());
      assertThat(product1Result.getCreated()).hasValue(product.created());
      assertThat(product1Result.getDescription()).hasValue(product.description());
    }

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customerName, description);
  }

  @Test
  @DisplayName("Should execute normal insert and multiple bulk mutations with atomic directive")
  public void insertOrderAndBulkInsertNProductsWithAtomic() {
    String productId1 = UUID.randomUUID().toString();
    String productId2 = UUID.randomUUID().toString();
    String productName = "Shiny Legs";
    String description = "Normal legs but shiny.";
    ProductsInput product1 =
        ProductsInput.builder()
            .id(productId1)
            .name(productName)
            .price("3199.99")
            .created(now())
            .description(description)
            .build();
    ProductsInput product2 =
        ProductsInput.builder()
            .id(productId2)
            .name("Non-Legs")
            .price("1000.99")
            .created(now())
            .description("Non-legs.")
            .build();

    String customerName = "c1";
    OrdersInput order =
        OrdersInput.builder()
            .prodName(productName)
            .customerName(customerName)
            .price("3199.99")
            .description(description)
            .build();

    ApolloClient client = getApolloClient("/graphql/betterbotz");
    InsertOrdersAndBulkInsertProductsWithAtomicMutation mutation =
        InsertOrdersAndBulkInsertProductsWithAtomicMutation.builder()
            .values(Arrays.asList(product1, product2))
            .orderValue(order)
            .build();

    insertOrdersAndBulkInsertProductsWthAtomic(client, mutation);

    GetProductsWithFilterQuery.Value product1Result = getProduct(client, productId1);

    assertThat(product1Result.getId()).hasValue(productId1);
    assertThat(product1Result.getName()).hasValue(product1.name());
    assertThat(product1Result.getPrice()).hasValue(product1.price());
    assertThat(product1Result.getCreated()).hasValue(product1.created());
    assertThat(product1Result.getDescription()).hasValue(product1.description());

    GetProductsWithFilterQuery.Value product2Result = getProduct(client, productId2);

    assertThat(product2Result.getId()).hasValue(productId2);
    assertThat(product2Result.getName()).hasValue(product2.name());
    assertThat(product2Result.getPrice()).hasValue(product2.price());
    assertThat(product2Result.getCreated()).hasValue(product2.created());
    assertThat(product2Result.getDescription()).hasValue(product2.description());

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customerName, description);
  }

  private InsertOrdersAndBulkInsertProductsWithAtomicMutation.Data
      insertOrdersAndBulkInsertProductsWthAtomic(
          ApolloClient client, InsertOrdersAndBulkInsertProductsWithAtomicMutation mutation) {
    InsertOrdersAndBulkInsertProductsWithAtomicMutation.Data result =
        getObservable(client.mutate(mutation));
    assertThat(result.getProducts()).isPresent();
    assertThat(result.getOrder()).isPresent();
    return result;
  }

  private BulkInsertProductsAndOrdersWithAtomicMutation.Data bulkInsertProductsAndOrdersWithAtomic(
      ApolloClient client, BulkInsertProductsAndOrdersWithAtomicMutation mutation) {
    BulkInsertProductsAndOrdersWithAtomicMutation.Data result =
        getObservable(client.mutate(mutation));
    assertThat(result.getProducts()).isPresent();
    assertThat(result.getOrder()).isPresent();
    return result;
  }

  public List<BulkInsertProductsMutation.BulkInsertProduct> bulkInsertProducts(
      ApolloClient client, List<ProductsInput> productsInputs) {
    BulkInsertProductsMutation mutation =
        BulkInsertProductsMutation.builder().values(productsInputs).build();
    BulkInsertProductsMutation.Data result = getObservable(client.mutate(mutation));
    assertThat(result.getBulkInsertProducts()).isPresent();
    assertThat(result.getBulkInsertProducts()).isPresent();
    assertThat(result.getBulkInsertProducts().get().size()).isEqualTo(productsInputs.size());
    return result.getBulkInsertProducts().get();
  }

  public List<BulkInsertProductsWithAtomicMutation.BulkInsertProduct> bulkInsertProductsWithAtomic(
      ApolloClient client, List<ProductsInput> productsInputs) {
    BulkInsertProductsWithAtomicMutation mutation =
        BulkInsertProductsWithAtomicMutation.builder().values(productsInputs).build();
    BulkInsertProductsWithAtomicMutation.Data result = getObservable(client.mutate(mutation));
    assertThat(result.getBulkInsertProducts()).isPresent();
    assertThat(result.getBulkInsertProducts()).isPresent();
    assertThat(result.getBulkInsertProducts().get().size()).isEqualTo(productsInputs.size());
    return result.getBulkInsertProducts().get();
  }

  public InsertProductsMutation.InsertProducts insertProduct(
      ApolloClient client, ProductsInput input) {
    InsertProductsMutation mutation = InsertProductsMutation.builder().value(input).build();
    InsertProductsMutation.Data result = getObservable(client.mutate(mutation));
    assertThat(result.getInsertProducts()).isPresent();
    return result.getInsertProducts().get();
  }

  @Test
  public void updateProducts() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput insertInput =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    insertProduct(client, insertInput);

    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name(insertInput.name())
            .price(insertInput.price())
            .created(insertInput.created())
            .description("Normal legs but shiny. Now available in different colors")
            .build();

    UpdateProductsMutation mutation = UpdateProductsMutation.builder().value(input).build();
    UpdateProductsMutation.Data result = getObservable(client.mutate(mutation));
    assertThat(result.getUpdateProducts())
        .hasValueSatisfying(
            updateProducts -> {
              assertThat(updateProducts.getApplied()).hasValue(true);
              assertThat(updateProducts.getValue())
                  .hasValueSatisfying(
                      product -> {
                        assertThat(product.getId()).hasValue(productId);
                        assertThat(product.getName()).hasValue(input.name());
                        assertThat(product.getPrice()).hasValue(input.price());
                        assertThat(product.getCreated()).hasValue(input.created());
                        assertThat(product.getDescription()).hasValue(input.description());
                      });
            });

    GetProductsWithFilterQuery.Value product = getProduct(client, productId);

    assertThat(product.getId()).hasValue(productId);
    assertThat(product.getName()).hasValue(input.name());
    assertThat(product.getPrice()).hasValue(input.price());
    assertThat(product.getCreated()).hasValue(input.created());
    assertThat(product.getDescription()).hasValue(input.description());
  }

  @Test
  public void updateProductsMissingIfExistsTrue() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    UpdateProductsMutation mutation =
        UpdateProductsMutation.builder().value(input).ifExists(true).build();
    UpdateProductsMutation.Data result = getObservable(client.mutate(mutation));

    assertThat(result.getUpdateProducts())
        .hasValueSatisfying(
            products -> {
              assertThat(products.getApplied()).hasValue(false);
              assertThat(products.getValue())
                  .hasValueSatisfying(
                      value -> {
                        assertThat(value.getId()).isEmpty();
                        assertThat(value.getName()).isEmpty();
                        assertThat(value.getPrice()).isEmpty();
                        assertThat(value.getCreated()).isEmpty();
                        assertThat(value.getDescription()).isEmpty();
                      });
            });
  }

  @Test
  public void deleteProducts() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput insertInput =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    insertProduct(client, insertInput);

    DeleteProductsMutation mutation =
        DeleteProductsMutation.builder()
            .value(ProductsInput.builder().id(productId).build())
            .build();

    DeleteProductsMutation.Data result = getObservable(client.mutate(mutation));

    assertThat(result.getDeleteProducts())
        .hasValueSatisfying(
            deleteProducts -> {
              assertThat(deleteProducts.getApplied()).hasValue(true);
              assertThat(deleteProducts.getValue())
                  .hasValueSatisfying(
                      product -> {
                        assertThat(product.getId()).hasValue(productId);
                        assertThat(product.getName()).isEmpty();
                        assertThat(product.getPrice()).isEmpty();
                        assertThat(product.getCreated()).isEmpty();
                        assertThat(product.getDescription()).isEmpty();
                      });
            });

    List<Value> remainingProductValues = getProductValues(client, productId);
    assertThat(remainingProductValues).isEmpty();
  }

  @Test
  public void deleteProductsIfExistsTrue() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput insertInput =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    insertProduct(client, insertInput);

    ProductsInput deleteInput =
        ProductsInput.builder()
            .id(productId)
            .name(insertInput.name())
            .price(insertInput.price())
            .created(insertInput.created())
            .build();
    DeleteProductsMutation mutation =
        DeleteProductsMutation.builder().value(deleteInput).ifExists(true).build();
    DeleteProductsMutation.Data result = getObservable(client.mutate(mutation));

    assertThat(result.getDeleteProducts())
        .hasValueSatisfying(
            deleteProducts -> {
              assertThat(deleteProducts.getApplied()).hasValue(true);
            });
  }

  @Test
  @DisplayName("Should execute multiple mutations with atomic directive")
  public void shouldSupportMultipleMutationsWithAtomicDirective() {
    UUID id = UUID.randomUUID();
    String productName = "prod " + id;
    String customer = "cust " + id;
    String price = "123";
    String description = "desc " + id;

    ApolloClient client = getApolloClient("/graphql/betterbotz");
    ProductsAndOrdersMutation mutation =
        ProductsAndOrdersMutation.builder()
            .productValue(
                ProductsInput.builder()
                    .id(id.toString())
                    .prodName(productName)
                    .price(price)
                    .name(productName)
                    .customerName(customer)
                    .created(now())
                    .description(description)
                    .build())
            .orderValue(
                OrdersInput.builder()
                    .prodName(productName)
                    .customerName(customer)
                    .price(price)
                    .description(description)
                    .build())
            .build();

    getObservable(client.mutate(mutation));

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Products\" WHERE id = ?", id))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"prodName\""), r -> r.getString("description"))
        .containsExactly(productName, description);

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customer, description);
  }

  @Test
  @DisplayName("Should execute single mutation with atomic directive")
  public void shouldSupportSingleMutationWithAtomicDirective() {
    UUID id = UUID.randomUUID();
    String productName = "prod " + id;
    String description = "desc " + id;
    String customer = "cust 1";

    ApolloClient client = getApolloClient("/graphql/betterbotz");
    InsertOrdersWithAtomicMutation mutation =
        InsertOrdersWithAtomicMutation.builder()
            .value(
                OrdersInput.builder()
                    .prodName(productName)
                    .customerName(customer)
                    .price("456")
                    .description(description)
                    .build())
            .build();

    getObservable(client.mutate(mutation));

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM betterbotz.\"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customer, description);
  }

  @Test
  @DisplayName(
      "When invalid, multiple mutations with atomic directive should return error response")
  public void multipleMutationsWithAtomicDirectiveShouldReturnErrorResponse() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");
    ProductsAndOrdersMutation mutation =
        ProductsAndOrdersMutation.builder()
            .productValue(
                // The mutation is invalid as parts of the primary key are missing
                ProductsInput.builder()
                    .id(UUID.randomUUID().toString())
                    .prodName("prodName sample")
                    .customerName("customer name")
                    .build())
            .orderValue(
                OrdersInput.builder().prodName("a").customerName("b").description("c").build())
            .build();

    GraphQLTestException ex =
        catchThrowableOfType(
            () -> getObservable(client.mutate(mutation)), GraphQLTestException.class);

    assertThat(ex).isNotNull();
    assertThat(ex.errors)
        // One error per query
        .hasSize(2)
        .first()
        .extracting(Error::getMessage)
        .asString()
        .contains("Some clustering keys are missing");
  }

  @Test
  @DisplayName("Multiple options with atomic directive should return error response")
  public void multipleOptionsWithAtomicDirectiveShouldReturnErrorResponse() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    ProductsAndOrdersMutation mutation =
        ProductsAndOrdersMutation.builder()
            .productValue(
                ProductsInput.builder()
                    .id(Uuids.random().toString())
                    .prodName("prod 1")
                    .price("1")
                    .name("prod1")
                    .created(now())
                    .build())
            .orderValue(
                OrdersInput.builder()
                    .prodName("prod 1")
                    .customerName("cust 1")
                    .description("my description")
                    .build())
            .options(MutationOptions.builder().consistency(MutationConsistency.ALL).build())
            .build();

    GraphQLTestException ex =
        catchThrowableOfType(
            () -> getObservable(client.mutate(mutation)), GraphQLTestException.class);

    assertThat(ex).isNotNull();
    assertThat(ex.errors)
        // One error per query
        .hasSize(2)
        .first()
        .extracting(Error::getMessage)
        .asString()
        .contains("options can only de defined once in an @atomic mutation selection");
  }

  @Test
  public void invalidTypeMappingReturnsErrorResponse() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");
    // Expected UUID format
    GraphQLTestException ex =
        catchThrowableOfType(() -> getProduct(client, "zzz"), GraphQLTestException.class);
    assertThat(ex.errors).hasSize(1);
    assertThat(ex.errors.get(0).getMessage()).contains("Invalid UUID string");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> executePost(String path, String query) throws IOException {
    OkHttpClient okHttpClient = getHttpClient();
    String url = String.format("http://%s:8080%s", stargate.seedAddress(), path);
    Map<String, Object> formData = new HashMap<>();
    formData.put("query", query);

    MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    okhttp3.Response response =
        okHttpClient
            .newCall(
                new Request.Builder()
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsBytes(formData)))
                    .url(url)
                    .build())
            .execute();
    assertThat(response.code()).isEqualTo(HttpStatus.SC_OK);
    Map<String, Object> result = objectMapper.readValue(response.body().string(), Map.class);
    response.close();
    return result;
  }

  @ParameterizedTest
  @MethodSource("getInvalidQueries")
  @DisplayName("Invalid GraphQL queries and mutations should return error response")
  public void invalidGraphQLParametersReturnsErrorResponse(
      String path, String query, String message1, String message2) throws IOException {
    Map<String, Object> mapResponse = executePost(path, query);
    assertThat(mapResponse).containsKey("errors");
    assertThat(mapResponse.get("errors")).isInstanceOf(List.class);
    List<Map<String, Object>> errors = (List<Map<String, Object>>) mapResponse.get("errors");
    assertThat(errors)
        .hasSize(1)
        .first()
        .extracting(i -> i.get("message"))
        .asString()
        .contains(message1, message2);
  }

  public static Stream<Arguments> getInvalidQueries() {
    String dmlPath = "/graphql/betterbotz";
    String ddlPath = "/graphql-schema";
    return Stream.of(
        arguments(
            dmlPath,
            "query { zzz { name } }",
            "Field 'zzz' in type 'Query' is undefined",
            "Validation error"),
        arguments(
            dmlPath,
            "invalidWrapper { zzz { name } }",
            "offending token 'invalidWrapper'",
            "Invalid Syntax"),
        arguments(
            dmlPath,
            "query { Products(filter: { name: { gt: \"a\"} }) { values { id } }}",
            "Cannot execute this query",
            "use ALLOW FILTERING"),
        arguments(
            ddlPath,
            "query { zzz { name } }",
            "Field 'zzz' in type 'Query' is undefined",
            "Validation error"),
        arguments(
            ddlPath,
            "query { keyspace (name: 1) { name } }",
            "WrongType: argument 'name'",
            "Validation error"),
        arguments(
            ddlPath,
            "query { keyspaces { name, nameInvalid } }",
            "Field 'nameInvalid' in type 'Keyspace' is undefined",
            "Validation error"));
  }

  @ParameterizedTest
  @MethodSource("getScalarValues")
  public void shouldSupportScalar(Column.Type type, Object value) throws IOException {
    String column = type.name().toLowerCase() + "value";
    UUID id = UUID.randomUUID();
    String mutation = "mutation { updateScalars(value: {id: \"%s\", %s: %s}) { applied } }";

    String graphQLValue = value.toString();
    if (value instanceof String) {
      graphQLValue = String.format("\"%s\"", value);
    }

    assertThat(
            executePost("/graphql/betterbotz", String.format(mutation, id, column, graphQLValue)))
        .doesNotContainKey("errors");

    String query = "query { Scalars(value: {id: \"%s\"}) { values { %s } } }";
    Map<String, Object> result =
        executePost("/graphql/betterbotz", String.format(query, id, column));

    assertThat(result).doesNotContainKey("errors");
    assertThat(result)
        .extractingByKey("data", InstanceOfAssertFactories.MAP)
        .extractingByKey("Scalars", InstanceOfAssertFactories.MAP)
        .extractingByKey("values", InstanceOfAssertFactories.LIST)
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .extractingByKey(column)
        .isEqualTo(value);
  }

  @SuppressWarnings("unused") // referenced by @MethodSource
  private static Stream<Arguments> getScalarValues() {
    return Stream.of(
        arguments(Column.Type.Ascii, "abc"),
        arguments(Column.Type.Bigint, "-9223372036854775807"),
        arguments(Column.Type.Blob, "AQID//7gEiMB"),
        arguments(Column.Type.Boolean, true),
        arguments(Column.Type.Boolean, false),
        arguments(Column.Type.Date, "2005-08-05"),
        arguments(Column.Type.Decimal, "-0.123456"),
        arguments(Column.Type.Double, -1D),
        arguments(Column.Type.Duration, "12h30m"),
        // Serialized as JSON numbers
        arguments(Column.Type.Float, 1.1234D),
        arguments(Column.Type.Inet, "8.8.8.8"),
        arguments(Column.Type.Int, 1),
        // Serialized as JSON Number
        arguments(Column.Type.Smallint, 32_767),
        arguments(Column.Type.Text, "abc123", "'abc123'"),
        arguments(Column.Type.Time, "23:59:31.123456789"),
        arguments(Column.Type.Timestamp, formatInstant(now())),
        arguments(Column.Type.Tinyint, -128),
        arguments(Column.Type.Tinyint, 1),
        arguments(Column.Type.Timeuuid, Uuids.timeBased().toString()),
        arguments(Column.Type.Uuid, "f3abdfbf-479f-407b-9fde-128145bd7bef"),
        arguments(Column.Type.Varchar, ""),
        arguments(Column.Type.Varint, "92233720368547758070000"));
  }

  @Test
  public void shouldInsertAndUpdateTuples() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");
    UUID id = UUID.randomUUID();
    long tuple1Value = 1L;
    float[] tuple2 = {1.3f, -90f};
    Object[] tuple3 = {Uuids.timeBased(), 2, true};

    getObservable(
        client.mutate(
            InsertTuplesMutation.builder()
                .value(TupleHelper.createTupleInput(id, tuple1Value, tuple2, tuple3))
                .build()));

    TupleHelper.assertTuples(
        getObservable(client.query(GetTuplesQuery.builder().id(id).build())),
        tuple1Value,
        tuple2,
        tuple3);

    tuple1Value = -1L;
    tuple2 = new float[] {0, Float.MAX_VALUE};
    tuple3 = new Object[] {Uuids.timeBased(), 3, false};

    getObservable(
        client.mutate(
            UpdateTuplesMutation.builder()
                .value(TupleHelper.createTupleInput(id, tuple1Value, tuple2, tuple3))
                .build()));

    TupleHelper.assertTuples(
        getObservable(client.query(GetTuplesQuery.builder().id(id).build())),
        tuple1Value,
        tuple2,
        tuple3);
  }

  @Test
  public void shouldSupportTuplesAsPartitionKey() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");
    Tuplx65_sPkInput input =
        Tuplx65_sPkInput.builder()
            .id(TupleIntIntInput.builder().item0(10).item1(20).build())
            .build();
    getObservable(client.mutate(InsertTuplesPkMutation.builder().value(input).build()));

    GetTuplesPkQuery.Data result =
        getObservable(client.query(GetTuplesPkQuery.builder().value(input).build()));

    assertThat(result.getTuplx65_sPk())
        .isPresent()
        .get()
        .extracting(v -> v.getValues(), InstanceOfAssertFactories.OPTIONAL)
        .isPresent()
        .get(InstanceOfAssertFactories.LIST)
        .hasSize(1);
  }

  @Test
  public void queryWithPaging() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    for (String name : ImmutableList.of("a", "b", "c")) {
      insertProduct(
          client,
          ProductsInput.builder()
              .id(UUID.randomUUID().toString())
              .name(name)
              .price("1.0")
              .created(now())
              .build());
    }

    List<String> names = new ArrayList<>();

    Optional<Products> products = Optional.empty();
    do {
      products = getProducts(client, 1, products.flatMap(r -> r.getPageState()));
      products.ifPresent(
          p -> {
            p.getValues()
                .ifPresent(
                    values -> {
                      for (Value value : values) {
                        value.getName().ifPresent(names::add);
                      }
                    });
          });
    } while (products
        .map(p -> p.getValues().map(v -> !v.isEmpty()).orElse(false))
        .orElse(false)); // Continue if there are still values

    assertThat(names).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  public void shouldIncrementMetricWhenExecutingGraphQlQuery() throws IOException {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    String productId = UUID.randomUUID().toString();
    ProductsInput input =
        ProductsInput.builder()
            .id(productId)
            .name("Shiny Legs")
            .price("3199.99")
            .created(now())
            .description("Normal legs but shiny.")
            .build();

    insertProduct(client, input);

    GetProductsWithFilterQuery.Value product = getProduct(client, productId);
    assertThat(product.getId()).hasValue(productId);

    // when
    String body = RestUtils.get("", String.format("%s:8084/metrics", host), HttpStatus.SC_OK);

    // then
    double numberOfGraphQlOperations = getGraphQlOperations(body);
    assertThat(numberOfGraphQlOperations).isGreaterThan(0);
  }

  private double getGraphQlOperations(String body) {
    return getMetricValue(body, "graphqlapi", GRAPHQL_OPERATIONS_METRIC_REGEXP);
  }

  private static Optional<Products> getProducts(
      ApolloClient client, int pageSize, Optional<String> pageState) {
    ProductsFilterInput filterInput = ProductsFilterInput.builder().build();

    QueryOptions.Builder optionsBuilder =
        QueryOptions.builder().pageSize(pageSize).consistency(QueryConsistency.LOCAL_QUORUM);

    pageState.ifPresent(optionsBuilder::pageState);
    QueryOptions options = optionsBuilder.build();

    GetProductsWithFilterQuery query =
        GetProductsWithFilterQuery.builder().filter(filterInput).options(options).build();

    GetProductsWithFilterQuery.Data result = getObservable(client.query(query));

    assertThat(result.getProducts())
        .hasValueSatisfying(
            products -> {
              assertThat(products.getValues())
                  .hasValueSatisfying(
                      values -> {
                        assertThat(values).hasSizeLessThanOrEqualTo(pageSize);
                      });
            });

    return result.getProducts();
  }

  @Test
  @DisplayName("Should insert and read back UDTs")
  public void udtsTest() {
    ApolloClient client = getApolloClient("/graphql/betterbotz");

    InsertUdtsMutation insert =
        InsertUdtsMutation.builder()
            .value(
                UdtsInput.builder()
                    .a(AUdtInput.builder().b(BUdtInput.builder().i(1).build()).build())
                    .bs(
                        ImmutableList.of(
                            BUdtInput.builder().i(2).build(), BUdtInput.builder().i(3).build()))
                    .build())
            .build();
    mutateAndGet(client, insert);

    GetUdtsQuery select =
        GetUdtsQuery.builder()
            .value(
                UdtsInput.builder()
                    .a(AUdtInput.builder().b(BUdtInput.builder().i(1).build()).build())
                    .build())
            .build();
    List<GetUdtsQuery.Value> values =
        getObservable(client.query(select))
            .getUdts()
            .flatMap(GetUdtsQuery.Udts::getValues)
            .orElseThrow(AssertionError::new);
    assertThat(values).hasSize(1);
    GetUdtsQuery.Value result = values.get(0);
    assertThat(result.getA().flatMap(GetUdtsQuery.A::getB))
        .flatMap(GetUdtsQuery.B::getI)
        .hasValue(1);
    assertThat(result.getBs())
        .hasValueSatisfying(
            bs -> {
              assertThat(bs).hasSize(2);
              assertThat(bs.get(0).getI()).hasValue(2);
              assertThat(bs.get(1).getI()).hasValue(3);
            });
  }

  private DeleteProductsMutation.Data cleanupProduct(ApolloClient client, Object productId) {
    DeleteProductsMutation mutation =
        DeleteProductsMutation.builder()
            .value(ProductsInput.builder().id(productId).build())
            .build();

    DeleteProductsMutation.Data result = getObservable(client.mutate(mutation));
    return result;
  }

  private static <T> T getObservable(ApolloCall<Optional<T>> observable) {
    CompletableFuture<T> future = new CompletableFuture<>();
    observable.enqueue(queryCallback(future));

    try {
      return future.get();
    } catch (ExecutionException e) {
      // Unwrap exception
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new RuntimeException("Unexpected exception", e);
    } catch (Exception e) {
      throw new RuntimeException("Operation could not be completed", e);
    } finally {
      observable.cancel();
    }
  }

  @SuppressWarnings("unchecked")
  private static <D extends Operation.Data, T, V extends Operation.Variables> D mutateAndGet(
      ApolloClient client, Mutation<D, T, V> mutation) {
    return getObservable((ApolloMutationCall<Optional<D>>) client.mutate(mutation));
  }

  private OkHttpClient getHttpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(Duration.ofMinutes(3))
        .callTimeout(Duration.ofMinutes(3))
        .readTimeout(Duration.ofMinutes(3))
        .writeTimeout(Duration.ofMinutes(3))
        .addInterceptor(
            chain ->
                chain.proceed(
                    chain.request().newBuilder().addHeader("X-Cassandra-Token", authToken).build()))
        .build();
  }

  private ApolloClient getApolloClient(String path) {
    return ApolloClient.builder()
        .serverUrl(String.format("http://%s:8080%s", stargate.seedAddress(), path))
        .okHttpClient(getHttpClient())
        .addCustomTypeAdapter(
            CustomType.TIMESTAMP,
            new CustomTypeAdapter<Instant>() {
              @NotNull
              @Override
              public CustomTypeValue<?> encode(Instant instant) {
                return new CustomTypeValue.GraphQLString(instant.toString());
              }

              @Override
              public Instant decode(@NotNull CustomTypeValue<?> customTypeValue) {
                return parseInstant(customTypeValue.value.toString());
              }
            })
        .build();
  }

  private static <U> ApolloCall.Callback<Optional<U>> queryCallback(CompletableFuture<U> future) {
    return new ApolloCall.Callback<Optional<U>>() {
      @Override
      public void onResponse(@NotNull Response<Optional<U>> response) {
        if (response.getErrors() != null && response.getErrors().size() > 0) {
          logger.info(
              "GraphQL error found in test: {}",
              response.getErrors().stream().map(Error::getMessage).collect(Collectors.toList()));
          future.completeExceptionally(
              new GraphQLTestException("GraphQL error response", response.getErrors()));
          return;
        }

        if (response.getData().isPresent()) {
          future.complete(response.getData().get());
          return;
        }

        future.completeExceptionally(
            new IllegalStateException("Unexpected empty data and errors properties"));
      }

      @Override
      public void onFailure(@NotNull ApolloException e) {
        future.completeExceptionally(e);
      }
    };
  }

  private static class GraphQLTestException extends RuntimeException {
    private final List<Error> errors;

    GraphQLTestException(String message, List<Error> errors) {
      super(message);
      this.errors = errors;
    }
  }

  private static Instant parseInstant(String source) {
    try {
      return TIMESTAMP_FORMAT.get().parse(source).toInstant();
    } catch (ParseException e) {
      throw new AssertionError("Unexpected error while parsing timestamp in response", e);
    }
  }

  private static String formatInstant(Instant instant) {
    return TIMESTAMP_FORMAT.get().format(Date.from(instant));
  }

  private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT =
      ThreadLocal.withInitial(
          () -> {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            parser.setTimeZone(TimeZone.getTimeZone(ZoneId.systemDefault()));
            return parser;
          });
}
