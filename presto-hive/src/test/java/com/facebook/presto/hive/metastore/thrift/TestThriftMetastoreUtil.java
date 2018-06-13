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
package com.facebook.presto.hive.metastore.thrift;

import com.facebook.presto.hive.metastore.HiveColumnStatistics;
import org.apache.hadoop.hive.metastore.api.BinaryColumnStatsData;
import org.apache.hadoop.hive.metastore.api.BooleanColumnStatsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.Date;
import org.apache.hadoop.hive.metastore.api.DateColumnStatsData;
import org.apache.hadoop.hive.metastore.api.Decimal;
import org.apache.hadoop.hive.metastore.api.DecimalColumnStatsData;
import org.apache.hadoop.hive.metastore.api.DoubleColumnStatsData;
import org.apache.hadoop.hive.metastore.api.LongColumnStatsData;
import org.apache.hadoop.hive.metastore.api.StringColumnStatsData;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static com.facebook.presto.hive.metastore.thrift.ThriftMetastoreUtil.fromMetastoreApiColumnStatistics;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.binaryStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.booleanStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.dateStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.decimalStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.doubleStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.longStats;
import static org.apache.hadoop.hive.metastore.api.ColumnStatisticsData.stringStats;
import static org.apache.hadoop.hive.serde.serdeConstants.BIGINT_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.BINARY_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.BOOLEAN_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.DATE_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.DECIMAL_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.DOUBLE_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.STRING_TYPE_NAME;
import static org.testng.Assert.assertEquals;

public class TestThriftMetastoreUtil
{
    @Test
    public void testLongStatsToColumnStatistics()
    {
        LongColumnStatsData longColumnStatsData = new LongColumnStatsData();
        longColumnStatsData.setLowValue(0);
        longColumnStatsData.setHighValue(100);
        longColumnStatsData.setNumNulls(1);
        longColumnStatsData.setNumDVs(20);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BIGINT_TYPE_NAME, longStats(longColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.of(0L));
        assertEquals(actual.getHighValue(), Optional.of(100L));
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(1));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.of(19));
    }

    @Test
    public void testEmptyLongStatsToColumnStatistics()
    {
        LongColumnStatsData emptyLongColumnStatsData = new LongColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BIGINT_TYPE_NAME, longStats(emptyLongColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testDoubleStatsToColumnStatistics()
    {
        DoubleColumnStatsData doubleColumnStatsData = new DoubleColumnStatsData();
        doubleColumnStatsData.setLowValue(0);
        doubleColumnStatsData.setHighValue(100);
        doubleColumnStatsData.setNumNulls(1);
        doubleColumnStatsData.setNumDVs(20);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DOUBLE_TYPE_NAME, doubleStats(doubleColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.of(0.0));
        assertEquals(actual.getHighValue(), Optional.of(100.0));
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(1));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.of(19));
    }

    @Test
    public void testEmptyDoubleStatsToColumnStatistics()
    {
        DoubleColumnStatsData emptyDoubleColumnStatsData = new DoubleColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DOUBLE_TYPE_NAME, doubleStats(emptyDoubleColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testDecimalStatsToColumnStatistics()
    {
        DecimalColumnStatsData decimalColumnStatsData = new DecimalColumnStatsData();
        BigDecimal low = new BigDecimal("0");
        decimalColumnStatsData.setLowValue(new Decimal(ByteBuffer.wrap(low.unscaledValue().toByteArray()), (short) low.scale()));
        BigDecimal high = new BigDecimal("100");
        decimalColumnStatsData.setHighValue(new Decimal(ByteBuffer.wrap(high.unscaledValue().toByteArray()), (short) high.scale()));
        decimalColumnStatsData.setNumNulls(1);
        decimalColumnStatsData.setNumDVs(20);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DECIMAL_TYPE_NAME, decimalStats(decimalColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.of(low));
        assertEquals(actual.getHighValue(), Optional.of(high));
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(1));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.of(19));
    }

    @Test
    public void testEmptyDecimalStatsToColumnStatistics()
    {
        DecimalColumnStatsData emptyDecimalColumnStatsData = new DecimalColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DECIMAL_TYPE_NAME, decimalStats(emptyDecimalColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testBooleanStatsToColumnStatistics()
    {
        BooleanColumnStatsData booleanColumnStatsData = new BooleanColumnStatsData();
        booleanColumnStatsData.setNumTrues(100);
        booleanColumnStatsData.setNumFalses(10);
        booleanColumnStatsData.setNumNulls(0);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BOOLEAN_TYPE_NAME, booleanStats(booleanColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.empty());
        assertEquals(actual.getHighValue(), Optional.empty());
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.of(100));
        assertEquals(actual.getFalseCount(), OptionalLong.of(10));
        assertEquals(actual.getNullsCount(), OptionalLong.of(0));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.empty());
    }

    @Test
    public void testEmptyBooleanStatsToColumnStatistics()
    {
        BooleanColumnStatsData emptyBooleanColumnStatsData = new BooleanColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BOOLEAN_TYPE_NAME, booleanStats(emptyBooleanColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testDateStatsToColumnStatistics()
    {
        DateColumnStatsData dateColumnStatsData = new DateColumnStatsData();
        dateColumnStatsData.setLowValue(new Date(1000));
        dateColumnStatsData.setHighValue(new Date(2000));
        dateColumnStatsData.setNumNulls(1);
        dateColumnStatsData.setNumDVs(20);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DATE_TYPE_NAME, dateStats(dateColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.of(LocalDate.ofEpochDay(1000)));
        assertEquals(actual.getHighValue(), Optional.of(LocalDate.ofEpochDay(2000)));
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(1));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.of(19));
    }

    @Test
    public void testEmptyDateStatsToColumnStatistics()
    {
        DateColumnStatsData emptyDateColumnStatsData = new DateColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", DATE_TYPE_NAME, dateStats(emptyDateColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testStringStatsToColumnStatistics()
    {
        StringColumnStatsData stringColumnStatsData = new StringColumnStatsData();
        stringColumnStatsData.setMaxColLen(100);
        stringColumnStatsData.setAvgColLen(40);
        stringColumnStatsData.setNumNulls(1);
        stringColumnStatsData.setNumDVs(20);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", STRING_TYPE_NAME, stringStats(stringColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.empty());
        assertEquals(actual.getHighValue(), Optional.empty());
        assertEquals(actual.getMaxColumnLength(), OptionalLong.of(100));
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.of(40));
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(1));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.of(19));
    }

    @Test
    public void testEmptyStringColumnStatsData()
    {
        StringColumnStatsData emptyStringColumnStatsData = new StringColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", STRING_TYPE_NAME, stringStats(emptyStringColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    @Test
    public void testBinaryStatsToColumnStatistics()
    {
        BinaryColumnStatsData binaryColumnStatsData = new BinaryColumnStatsData();
        binaryColumnStatsData.setMaxColLen(100);
        binaryColumnStatsData.setAvgColLen(40);
        binaryColumnStatsData.setNumNulls(0);
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BINARY_TYPE_NAME, binaryStats(binaryColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);

        assertEquals(actual.getLowValue(), Optional.empty());
        assertEquals(actual.getHighValue(), Optional.empty());
        assertEquals(actual.getMaxColumnLength(), OptionalLong.of(100));
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.of(40));
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.of(0));
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.empty());
    }

    @Test
    public void testEmptyBinaryStatsToColumnStatistics()
    {
        BinaryColumnStatsData emptyBinaryColumnStatsData = new BinaryColumnStatsData();
        ColumnStatisticsObj columnStatisticsObj = new ColumnStatisticsObj("my_col", BINARY_TYPE_NAME, binaryStats(emptyBinaryColumnStatsData));
        HiveColumnStatistics actual = fromMetastoreApiColumnStatistics(columnStatisticsObj);
        assertEmptyColumnStats(actual);
    }

    private static void assertEmptyColumnStats(HiveColumnStatistics actual)
    {
        assertEquals(actual.getLowValue(), Optional.empty());
        assertEquals(actual.getHighValue(), Optional.empty());
        assertEquals(actual.getMaxColumnLength(), OptionalLong.empty());
        assertEquals(actual.getAverageColumnLength(), OptionalDouble.empty());
        assertEquals(actual.getTrueCount(), OptionalLong.empty());
        assertEquals(actual.getFalseCount(), OptionalLong.empty());
        assertEquals(actual.getNullsCount(), OptionalLong.empty());
        assertEquals(actual.getDistinctValuesCount(), OptionalLong.empty());
    }
}
