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
package com.facebook.presto.hive.parquet;

import com.facebook.presto.spi.type.SqlDate;
import com.facebook.presto.spi.type.SqlDecimal;
import com.facebook.presto.spi.type.SqlTimestamp;
import com.facebook.presto.spi.type.SqlVarbinary;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.primitives.Shorts;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.joda.time.DateTimeZone;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import parquet.hadoop.ParquetOutputFormat;
import parquet.hadoop.codec.CodecConfig;
import parquet.schema.MessageType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.facebook.presto.hive.parquet.ParquetTester.HIVE_STORAGE_TIME_ZONE;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DecimalType.createDecimalType;
import static com.facebook.presto.spi.type.Decimals.MAX_PRECISION;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.RealType.REAL;
import static com.facebook.presto.spi.type.TimeZoneKey.UTC_KEY;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.google.common.base.Functions.compose;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.cycle;
import static com.google.common.collect.Iterables.limit;
import static com.google.common.collect.Iterables.transform;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteArrayObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDateObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaShortObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaTimestampObjectInspector;
import static org.testng.Assert.assertEquals;
import static parquet.schema.MessageTypeParser.parseMessageType;

public abstract class AbstractTestParquetReader
{
    private static final int MAX_PRECISION_INT32 = (int) maxPrecision(4);
    private static final int MAX_PRECISION_INT64 = (int) maxPrecision(8);

    private final ParquetTester tester;

    public AbstractTestParquetReader(ParquetTester tester)
    {
        this.tester = tester;
    }

    @BeforeClass
    public void setUp()
    {
        assertEquals(DateTimeZone.getDefault(), HIVE_STORAGE_TIME_ZONE);
        setParquetLogging();
    }

    @Test
    public void testBooleanSequence()
            throws Exception
    {
        tester.testRoundTrip(javaBooleanObjectInspector, limit(cycle(ImmutableList.of(true, false, false)), 30_000), BOOLEAN);
    }

    @Test
    public void testLongSequence()
            throws Exception
    {
        testRoundTripNumeric(intsBetween(0, 31_234));
    }

    @Test
    public void testLongSequenceWithHoles()
            throws Exception
    {
        testRoundTripNumeric(skipEvery(5, intsBetween(0, 31_234)));
    }

    @Test
    public void testLongDirect()
            throws Exception
    {
        testRoundTripNumeric(limit(cycle(ImmutableList.of(1, 3, 5, 7, 11, 13, 17)), 30_000));
    }

    @Test
    public void testLongDirect2()
            throws Exception
    {
        List<Integer> values = new ArrayList<>(31_234);
        for (int i = 0; i < 31_234; i++) {
            values.add(i);
        }
        Collections.shuffle(values, new Random(0));
        testRoundTripNumeric(values);
    }

    @Test
    public void testLongShortRepeat()
            throws Exception
    {
        testRoundTripNumeric(limit(repeatEach(4, cycle(ImmutableList.of(1, 3, 5, 7, 11, 13, 17))), 30_000));
    }

    @Test
    public void testLongPatchedBase()
            throws Exception
    {
        testRoundTripNumeric(limit(cycle(concat(intsBetween(0, 18), ImmutableList.of(30_000, 20_000))), 30_000));
    }

    // copied from Parquet code to determine the max decimal precision supported by INT32/INT64
    private static long maxPrecision(int numBytes)
    {
        return Math.round(Math.floor(Math.log10(Math.pow(2, 8 * numBytes - 1) - 1)));
    }

    @Test
    public void testDecimalBackedByINT32()
            throws Exception
    {
        for (int precision = 1; precision <= MAX_PRECISION_INT32; precision++) {
            int scale = ThreadLocalRandom.current().nextInt(precision);
            MessageType parquetSchema = parseMessageType(format("message hive_decimal { optional INT32 test (DECIMAL(%d, %d)); }", precision, scale));
            ContiguousSet<Integer> intValues = intsBetween(1, 1_000);
            ImmutableList.Builder<SqlDecimal> expectedValues = new ImmutableList.Builder<>();
            for (Integer value : intValues) {
                expectedValues.add(SqlDecimal.of(value, precision, scale));
            }
            tester.testRoundTrip(javaIntObjectInspector, intValues, expectedValues.build(), createDecimalType(precision, scale), Optional.of(parquetSchema));
        }
    }

    @Test
    public void testDecimalBackedByINT64()
            throws Exception
    {
        for (int precision = MAX_PRECISION_INT32 + 1; precision <= MAX_PRECISION_INT64; precision++) {
            int scale = ThreadLocalRandom.current().nextInt(precision);
            MessageType parquetSchema = parseMessageType(format("message hive_decimal { optional INT64 test (DECIMAL(%d, %d)); }", precision, scale));
            ContiguousSet<Long> longValues = longsBetween(1, 1_000);
            ImmutableList.Builder<SqlDecimal> expectedValues = new ImmutableList.Builder<>();
            for (Long value : longValues) {
                expectedValues.add(SqlDecimal.of(value, precision, scale));
            }
            tester.testRoundTrip(javaLongObjectInspector, longValues, expectedValues.build(), createDecimalType(precision, scale), Optional.of(parquetSchema));
        }
    }

    @Test
    public void testDecimalBackedByFixedLenByteArray()
            throws Exception
    {
        for (int precision = MAX_PRECISION_INT64 + 1; precision < MAX_PRECISION; precision++) {
            int scale = ThreadLocalRandom.current().nextInt(precision);
            MessageType parquetSchema = parseMessageType(format("message hive_decimal { optional FIXED_LEN_BYTE_ARRAY(16) test (DECIMAL(%d, %d)); }", precision, scale));
            ContiguousSet<BigInteger> values = bigIntegersBetween(BigDecimal.valueOf(Math.pow(10, precision - 1)).toBigInteger(), BigDecimal.valueOf(Math.pow(10, precision)).toBigInteger());
            ImmutableList.Builder<SqlDecimal> expectedValues = new ImmutableList.Builder<>();
            ImmutableList.Builder<HiveDecimal> writeValues = new ImmutableList.Builder<>();
            for (BigInteger value : limit(values, 1_000)) {
                writeValues.add(HiveDecimal.create(value, scale));
                expectedValues.add(new SqlDecimal(value, precision, scale));
            }
            tester.testRoundTrip(new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(precision, scale)),
                    writeValues.build(),
                    expectedValues.build(),
                    createDecimalType(precision, scale),
                    Optional.of(parquetSchema));
        }
    }

    @Test
    public void testLongStrideDictionary()
            throws Exception
    {
        testRoundTripNumeric(concat(ImmutableList.of(1), Collections.nCopies(9999, 123), ImmutableList.of(2), Collections.nCopies(9999, 123)));
    }

    private void testRoundTripNumeric(Iterable<Integer> writeValues)
            throws Exception
    {
        tester.testRoundTrip(javaByteObjectInspector,
                transform(writeValues, AbstractTestParquetReader::intToByte),
                AbstractTestParquetReader::byteToInt,
                INTEGER);

        tester.testRoundTrip(javaShortObjectInspector,
                transform(writeValues, AbstractTestParquetReader::intToShort),
                AbstractTestParquetReader::shortToInt,
                INTEGER);

        tester.testRoundTrip(javaIntObjectInspector, writeValues, writeValues, INTEGER);
        tester.testRoundTrip(javaLongObjectInspector, transform(writeValues, AbstractTestParquetReader::intToLong), BIGINT);
        tester.testRoundTrip(javaTimestampObjectInspector,
                transform(writeValues, AbstractTestParquetReader::intToTimestamp),
                transform(writeValues, AbstractTestParquetReader::intToSqlTimestamp),
                TIMESTAMP);

        tester.testRoundTrip(javaDateObjectInspector,
                transform(writeValues, AbstractTestParquetReader::intToDate),
                transform(writeValues, AbstractTestParquetReader::intToSqlDate),
                DATE);
    }

    @Test
    public void testFloatSequence()
            throws Exception
    {
        tester.testRoundTrip(javaFloatObjectInspector, floatSequence(0.0f, 0.1f, 30_000), REAL);
    }

    @Test
    public void testFloatNaNInfinity()
            throws Exception
    {
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(1000.0f, -1.23f, Float.POSITIVE_INFINITY), REAL);
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(-1000.0f, Float.NEGATIVE_INFINITY, 1.23f), REAL);
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(0.0f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY), REAL);

        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(Float.NaN, -0.0f, 1.0f), REAL);
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(Float.NaN, -1.0f, Float.POSITIVE_INFINITY), REAL);
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(Float.NaN, Float.NEGATIVE_INFINITY, 1.0f), REAL);
        tester.testRoundTrip(javaFloatObjectInspector, ImmutableList.of(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY), REAL);
    }

    @Test
    public void testDoubleSequence()
            throws Exception
    {
        tester.testRoundTrip(javaDoubleObjectInspector, doubleSequence(0, 0.1, 30_000), DOUBLE);
    }

    @Test
    public void testDoubleNaNInfinity()
            throws Exception
    {
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(1000.0, -1.0, Double.POSITIVE_INFINITY), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(-1000.0, Double.NEGATIVE_INFINITY, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(0.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), DOUBLE);

        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, -1.0, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, -1.0, Double.POSITIVE_INFINITY), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, Double.NEGATIVE_INFINITY, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), DOUBLE);
    }

    @Test
    public void testStringUnicode()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(ImmutableList.of("apple", "apple pie", "apple\uD835\uDC03", "apple\uFFFD")), 30_000), createUnboundedVarcharType());
    }

    @Test
    public void testStringDirectSequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, transform(intsBetween(0, 30_000), Object::toString), createUnboundedVarcharType());
    }

    @Test
    public void testStringDictionarySequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(transform(ImmutableList.of(1, 3, 5, 7, 11, 13, 17), Object::toString)), 30_000), createUnboundedVarcharType());
    }

    @Test
    public void testStringStrideDictionary()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, concat(ImmutableList.of("a"), Collections.nCopies(9999, "123"), ImmutableList.of("b"), Collections.nCopies(9999, "123")), createUnboundedVarcharType());
    }

    @Test
    public void testEmptyStringSequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(""), 30_000), createUnboundedVarcharType());
    }

    @Test
    public void testBinaryDirectSequence()
            throws Exception
    {
        Iterable<byte[]> writeValues = transform(intsBetween(0, 30_000), compose(AbstractTestParquetReader::stringToByteArray, Object::toString));
        tester.testRoundTrip(javaByteArrayObjectInspector,
                writeValues,
                transform(writeValues, AbstractTestParquetReader::byteArrayToVarbinary),
                VARBINARY);
    }

    @Test
    public void testBinaryDictionarySequence()
            throws Exception
    {
        Iterable<byte[]> writeValues = limit(cycle(transform(ImmutableList.of(1, 3, 5, 7, 11, 13, 17), compose(AbstractTestParquetReader::stringToByteArray, Object::toString))), 30_000);
        tester.testRoundTrip(javaByteArrayObjectInspector,
                writeValues,
                transform(writeValues, AbstractTestParquetReader::byteArrayToVarbinary),
                VARBINARY);
    }

    @Test
    public void testEmptyBinarySequence()
            throws Exception
    {
        tester.testRoundTrip(javaByteArrayObjectInspector, limit(cycle(new byte[0]), 30_000), AbstractTestParquetReader::byteArrayToVarbinary, VARBINARY);
    }

    private static <T> Iterable<T> skipEvery(int n, Iterable<T> iterable)
    {
        return () -> new AbstractIterator<T>()
        {
            private final Iterator<T> delegate = iterable.iterator();
            private int position;

            @Override
            protected T computeNext()
            {
                while (true) {
                    if (!delegate.hasNext()) {
                        return endOfData();
                    }

                    T next = delegate.next();
                    position++;
                    if (position <= n) {
                        return next;
                    }
                    position = 0;
                }
            }
        };
    }

    // parquet has excessive logging at INFO level, set them to WARNING
    private void setParquetLogging()
    {
        Logger.getLogger(ParquetOutputFormat.class.getName()).setLevel(Level.WARNING);
        Logger.getLogger(CodecConfig.class.getName()).setLevel(Level.WARNING);
        // these logging classes are not public, use class name directly
        Logger.getLogger("parquet.hadoop.InternalParquetRecordWriter").setLevel(Level.WARNING);
        Logger.getLogger("parquet.hadoop.ColumnChunkPageWriteStore").setLevel(Level.WARNING);
    }

    private static <T> Iterable<T> repeatEach(int n, Iterable<T> iterable)
    {
        return () -> new AbstractIterator<T>()
        {
            private final Iterator<T> delegate = iterable.iterator();
            private int position;
            private T value;

            @Override
            protected T computeNext()
            {
                if (position == 0) {
                    if (!delegate.hasNext()) {
                        return endOfData();
                    }
                    value = delegate.next();
                }

                position++;
                if (position >= n) {
                    position = 0;
                }
                return value;
            }
        };
    }

    private static Iterable<Float> floatSequence(double start, double step, int items)
    {
        return transform(doubleSequence(start, step, items), input -> {
            if (input == null) {
                return null;
            }
            return input.floatValue();
        });
    }

    private static Iterable<Double> doubleSequence(double start, double step, int items)
    {
        return () -> new AbstractSequentialIterator<Double>(start)
        {
            private int item;

            @Override
            protected Double computeNext(Double previous)
            {
                if (item >= items) {
                    return null;
                }
                item++;
                return previous + step;
            }
        };
    }

    private static ContiguousSet<Integer> intsBetween(int lowerInclusive, int upperExclusive)
    {
        return ContiguousSet.create(Range.openClosed(lowerInclusive, upperExclusive), DiscreteDomain.integers());
    }

    private static ContiguousSet<Long> longsBetween(long lowerInclusive, long upperExclusive)
    {
        return ContiguousSet.create(Range.openClosed(lowerInclusive, upperExclusive), DiscreteDomain.longs());
    }

    private static ContiguousSet<BigInteger> bigIntegersBetween(BigInteger lowerInclusive, BigInteger upperExclusive)
    {
        return ContiguousSet.create(Range.openClosed(lowerInclusive, upperExclusive), DiscreteDomain.bigIntegers());
    }

    private static Byte intToByte(Integer input)
    {
        if (input == null) {
            return null;
        }
        return input.byteValue();
    }

    private static Short intToShort(Integer input)
    {
        if (input == null) {
            return null;
        }
        return Shorts.checkedCast(input);
    }

    private static Integer byteToInt(Byte input)
    {
        return toInteger(input);
    }

    private static Integer shortToInt(Short input)
    {
        return toInteger(input);
    }

    private static Long intToLong(Integer input)
    {
        return toLong(input);
    }

    private static <N extends Number> Integer toInteger(N input)
    {
        if (input == null) {
            return null;
        }
        return input.intValue();
    }

    private static <N extends Number> Long toLong(N input)
    {
        if (input == null) {
            return null;
        }
        return input.longValue();
    }

    private static byte[] stringToByteArray(String input)
    {
        return input.getBytes(UTF_8);
    }

    private static SqlVarbinary byteArrayToVarbinary(byte[] input)
    {
        if (input == null) {
            return null;
        }
        return new SqlVarbinary(input);
    }

    private static Timestamp intToTimestamp(Integer input)
    {
        if (input == null) {
            return null;
        }
        Timestamp timestamp = new Timestamp(0);
        long seconds = (input / 1000);
        int nanos = ((input % 1000) * 1_000_000);

        // add some junk nanos to the timestamp, which will be truncated
        nanos += 888_888;

        if (nanos < 0) {
            nanos += 1_000_000_000;
            seconds -= 1;
        }
        if (nanos > 1_000_000_000) {
            nanos -= 1_000_000_000;
            seconds += 1;
        }
        timestamp.setTime(seconds * 1000);
        timestamp.setNanos(nanos);
        return timestamp;
    }

    private static SqlTimestamp intToSqlTimestamp(Integer input)
    {
        if (input == null) {
            return null;
        }
        return new SqlTimestamp(input, UTC_KEY);
    }

    private static Date intToDate(Integer input)
    {
        if (input == null) {
            return null;
        }
        return Date.valueOf(LocalDate.ofEpochDay(input));
    }

    private static SqlDate intToSqlDate(Integer input)
    {
        if (input == null) {
            return null;
        }
        return new SqlDate(input);
    }
}
