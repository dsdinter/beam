/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.schemas;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.coders.BigDecimalCoder;
import org.apache.beam.sdk.coders.BigEndianShortCoder;
import org.apache.beam.sdk.coders.BooleanCoder;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.ByteCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.DoubleCoder;
import org.apache.beam.sdk.coders.FloatCoder;
import org.apache.beam.sdk.coders.InstantCoder;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.MapCoder;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.coders.RowCoderGenerator;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.util.SerializableUtils;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;

/** {@link SchemaCoder} is used as the coder for types that have schemas registered. */
@Experimental(Kind.SCHEMAS)
public class SchemaCoder<T> extends CustomCoder<T> {
  // This contains a map of primitive types to their coders.
  public static final ImmutableMap<TypeName, Coder> CODER_MAP =
      ImmutableMap.<TypeName, Coder>builder()
          .put(TypeName.BYTE, ByteCoder.of())
          .put(TypeName.BYTES, ByteArrayCoder.of())
          .put(TypeName.INT16, BigEndianShortCoder.of())
          .put(TypeName.INT32, VarIntCoder.of())
          .put(TypeName.INT64, VarLongCoder.of())
          .put(TypeName.DECIMAL, BigDecimalCoder.of())
          .put(TypeName.FLOAT, FloatCoder.of())
          .put(TypeName.DOUBLE, DoubleCoder.of())
          .put(TypeName.STRING, StringUtf8Coder.of())
          .put(TypeName.DATETIME, InstantCoder.of())
          .put(TypeName.BOOLEAN, BooleanCoder.of())
          .build();

  protected final Schema schema;
  private final TypeDescriptor<T> typeDescriptor;
  private final SerializableFunction<T, Row> toRowFunction;
  private final SerializableFunction<Row, T> fromRowFunction;
  @Nullable private transient Coder<Row> delegateCoder;

  protected SchemaCoder(
      Schema schema,
      TypeDescriptor<T> typeDescriptor,
      SerializableFunction<T, Row> toRowFunction,
      SerializableFunction<Row, T> fromRowFunction) {
    checkArgument(
        !typeDescriptor.hasUnresolvedParameters(),
        "Cannot create SchemaCoder with a TypeDescriptor that has unresolved parameters: %s",
        typeDescriptor);
    if (schema.getUUID() == null) {
      // Clone the schema before modifying the Java object.
      schema = SerializableUtils.clone(schema);
      setSchemaIds(schema);
    }
    this.toRowFunction = toRowFunction;
    this.fromRowFunction = fromRowFunction;
    this.typeDescriptor = typeDescriptor;
    this.schema = schema;
  }

  /**
   * Returns a {@link SchemaCoder} for the specified class. If no schema is registered for this
   * class, then throws {@link NoSuchSchemaException}.
   */
  public static <T> SchemaCoder<T> of(
      Schema schema,
      TypeDescriptor<T> typeDescriptor,
      SerializableFunction<T, Row> toRowFunction,
      SerializableFunction<Row, T> fromRowFunction) {
    return new SchemaCoder<>(schema, typeDescriptor, toRowFunction, fromRowFunction);
  }

  /** Returns a {@link SchemaCoder} for {@link Row} instances with the given {@code schema}. */
  public static SchemaCoder<Row> of(Schema schema) {
    return RowCoder.of(schema);
  }

  /** Returns the coder used for a given primitive type. */
  public static <T> Coder<T> coderForFieldType(FieldType fieldType) {
    switch (fieldType.getTypeName()) {
      case ROW:
        return (Coder<T>) SchemaCoder.of(fieldType.getRowSchema());
      case ARRAY:
        return (Coder<T>) ListCoder.of(coderForFieldType(fieldType.getCollectionElementType()));
      case MAP:
        return (Coder<T>)
            MapCoder.of(
                coderForFieldType(fieldType.getMapKeyType()),
                coderForFieldType(fieldType.getMapValueType()));
      default:
        return (Coder<T>) CODER_MAP.get(fieldType.getTypeName());
    }
  }

  /** Returns the schema associated with this type. */
  public Schema getSchema() {
    return schema;
  }

  /** Returns the toRow conversion function. */
  public SerializableFunction<Row, T> getFromRowFunction() {
    return fromRowFunction;
  }

  /** Returns the fromRow conversion function. */
  public SerializableFunction<T, Row> getToRowFunction() {
    return toRowFunction;
  }

  private Coder<Row> getDelegateCoder() {
    if (delegateCoder == null) {
      // RowCoderGenerator caches based on id, so if a new instance of this RowCoder is
      // deserialized, we don't need to run ByteBuddy again to construct the class.
      delegateCoder = RowCoderGenerator.generate(schema);
    }
    return delegateCoder;
  }

  @Override
  public void encode(T value, OutputStream outStream) throws IOException {
    getDelegateCoder().encode(toRowFunction.apply(value), outStream);
  }

  @Override
  public T decode(InputStream inStream) throws IOException {
    return fromRowFunction.apply(getDelegateCoder().decode(inStream));
  }

  @Override
  public void verifyDeterministic()
      throws org.apache.beam.sdk.coders.Coder.NonDeterministicException {
    verifyDeterministic(schema);
  }

  private void verifyDeterministic(Schema schema)
      throws org.apache.beam.sdk.coders.Coder.NonDeterministicException {

    ImmutableList<Coder<?>> coders =
        schema.getFields().stream()
            .map(Field::getType)
            .map(SchemaCoder::coderForFieldType)
            .collect(ImmutableList.toImmutableList());

    Coder.verifyDeterministic(this, "All fields must have deterministic encoding", coders);
  }

  @Override
  public boolean consistentWithEquals() {
    return true;
  }

  @Override
  public String toString() {
    return "SchemaCoder<Schema: "
        + schema
        + "  UUID: "
        + schema.getUUID()
        + " delegateCoder: "
        + getDelegateCoder();
  }

  // Sets the schema id, and then recursively ensures that all schemas have ids set.
  private static void setSchemaIds(Schema schema) {
    if (schema.getUUID() == null) {
      schema.setUUID(UUID.randomUUID());
    }
    for (Field field : schema.getFields()) {
      setSchemaIds(field.getType());
    }
  }

  private static void setSchemaIds(FieldType fieldType) {
    switch (fieldType.getTypeName()) {
      case ROW:
        setSchemaIds(fieldType.getRowSchema());
        return;
      case MAP:
        setSchemaIds(fieldType.getMapKeyType());
        setSchemaIds(fieldType.getMapValueType());
        return;
      case LOGICAL_TYPE:
        setSchemaIds(fieldType.getLogicalType().getBaseType());
        return;

      case ARRAY:
        setSchemaIds(fieldType.getCollectionElementType());
        return;

      default:
        return;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SchemaCoder<?> that = (SchemaCoder<?>) o;
    return schema.equals(that.schema)
        && typeDescriptor.equals(that.typeDescriptor)
        && toRowFunction.equals(that.toRowFunction)
        && fromRowFunction.equals(that.fromRowFunction);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schema, typeDescriptor, toRowFunction, fromRowFunction);
  }

  private static RowIdentity identity() {
    return new RowIdentity();
  }

  private static class RowIdentity implements SerializableFunction<Row, Row> {
    @Override
    public Row apply(Row input) {
      return input;
    }

    @Override
    public int hashCode() {
      return Objects.hash(getClass());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      return o != null && getClass() == o.getClass();
    }
  }

  @Override
  public TypeDescriptor<T> getEncodedTypeDescriptor() {
    return this.typeDescriptor;
  }
}
