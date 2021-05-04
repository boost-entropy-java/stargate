package io.stargate.graphql.schema.cqlfirst.dml.fetchers.aggregations;

public class SupportedAggregationFunctions {
  public static final String INT_FUNCTION = "_int_function";
  public static final String DOUBLE_FUNCTION = "_double_function"; // corresponds to GraphQLFloat
  public static final String BIGINT_FUNCTION = "_bigint_function";
  public static final String DECIMAL_FUNCTION = "_decimal_function";
  public static final String VARINT_FUNCTION = "_varint_function";
  public static final String FLOAT_FUNCTION = "_float_function";
  public static final String SMALLINT_FUNCTION = "_smallint_function";
  public static final String TINYINT_FUNCTION = "_tinyint_function";

  public static final String COUNT = "count";
  public static final String AVG = "avg";
  public static final String MIN = "min";
  public static final String MAX = "max";
  public static final String SUM = "sum";
}
