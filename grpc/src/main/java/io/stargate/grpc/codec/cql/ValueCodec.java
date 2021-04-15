package io.stargate.grpc.codec.cql;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.StatusException;
import io.stargate.db.schema.Column.ColumnType;
import io.stargate.proto.QueryOuterClass.Value;
import java.nio.ByteBuffer;

public interface ValueCodec {

  @Nullable
  ByteBuffer encode(@Nullable Value value, @NonNull ColumnType type) throws StatusException;

  @NonNull
  Value decode(@Nullable ByteBuffer bytes) throws StatusException;
}
