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
package com.facebook.presto.sql.analyzer;

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.ConfigurationFactory;
import io.airlift.configuration.testing.ConfigAssertions;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;

import static com.facebook.presto.operator.aggregation.histogram.HistogramGroupImplementation.LEGACY;
import static com.facebook.presto.operator.aggregation.histogram.HistogramGroupImplementation.NEW;
import static com.facebook.presto.sql.analyzer.FeaturesConfig.SPILLER_SPILL_PATH;
import static com.facebook.presto.sql.analyzer.FeaturesConfig.SPILL_ENABLED;
import static com.facebook.presto.sql.analyzer.RegexLibrary.JONI;
import static com.facebook.presto.sql.analyzer.RegexLibrary.RE2J;
import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.KILOBYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestFeaturesConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(ConfigAssertions.recordDefaults(FeaturesConfig.class)
                .setCpuCostWeight(75)
                .setMemoryCostWeight(10)
                .setNetworkCostWeight(15)
                .setResourceGroupsEnabled(false)
                .setDistributedIndexJoinsEnabled(false)
                .setDistributedJoinsEnabled(true)
                .setFastInequalityJoins(true)
                .setColocatedJoinsEnabled(false)
                .setSpatialJoinsEnabled(true)
                .setJoinReorderingEnabled(true)
                .setRedistributeWrites(true)
                .setScaleWriters(false)
                .setWriterMinSize(new DataSize(32, MEGABYTE))
                .setOptimizeMetadataQueries(false)
                .setOptimizeHashGeneration(true)
                .setOptimizeSingleDistinct(true)
                .setPushTableWriteThroughUnion(true)
                .setDictionaryAggregation(false)
                .setLegacyArrayAgg(false)
                .setLegacyMapSubscript(false)
                .setRegexLibrary(JONI)
                .setRe2JDfaStatesLimit(Integer.MAX_VALUE)
                .setRe2JDfaRetries(5)
                .setSpillEnabled(false)
                .setAggregationOperatorUnspillMemoryLimit(DataSize.valueOf("4MB"))
                .setSpillerSpillPaths("")
                .setSpillerThreads(4)
                .setSpillMaxUsedSpaceThreshold(0.9)
                .setMemoryRevokingThreshold(0.9)
                .setMemoryRevokingTarget(0.5)
                .setOptimizeMixedDistinctAggregations(false)
                .setLegacyOrderBy(false)
                .setIterativeOptimizerEnabled(true)
                .setIterativeOptimizerTimeout(new Duration(3, MINUTES))
                .setEnableNewStatsCalculator(false)
                .setExchangeCompressionEnabled(false)
                .setLegacyTimestamp(true)
                .setLegacyJoinUsing(false)
                .setEnableIntermediateAggregations(false)
                .setPushAggregationThroughJoin(true)
                .setParseDecimalLiteralsAsDouble(true)
                .setForceSingleNodeOutput(true)
                .setPagesIndexEagerCompactionEnabled(false)
                .setFilterAndProjectMinOutputPageSize(new DataSize(25, KILOBYTE))
                .setFilterAndProjectMinOutputPageRowCount(256)
                .setHistogramGroupImplementation(NEW));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("cpu-cost-weight", "0.4")
                .put("memory-cost-weight", "0.3")
                .put("network-cost-weight", "0.2")
                .put("experimental.resource-groups-enabled", "true")
                .put("experimental.iterative-optimizer-enabled", "false")
                .put("experimental.iterative-optimizer-timeout", "10s")
                .put("experimental.enable-new-stats-calculator", "true")
                .put("deprecated.legacy-array-agg", "true")
                .put("deprecated.legacy-order-by", "true")
                .put("deprecated.legacy-map-subscript", "true")
                .put("deprecated.legacy-join-using", "true")
                .put("distributed-index-joins-enabled", "true")
                .put("distributed-joins-enabled", "false")
                .put("fast-inequality-joins", "false")
                .put("colocated-joins-enabled", "true")
                .put("spatial-joins-enabled", "false")
                .put("reorder-joins", "false")
                .put("redistribute-writes", "false")
                .put("scale-writers", "true")
                .put("writer-min-size", "42GB")
                .put("optimizer.optimize-metadata-queries", "true")
                .put("optimizer.optimize-hash-generation", "false")
                .put("optimizer.optimize-single-distinct", "false")
                .put("optimizer.optimize-mixed-distinct-aggregations", "true")
                .put("optimizer.push-table-write-through-union", "false")
                .put("optimizer.dictionary-aggregation", "true")
                .put("optimizer.push-aggregation-through-join", "false")
                .put("regex-library", "RE2J")
                .put("re2j.dfa-states-limit", "42")
                .put("re2j.dfa-retries", "42")
                .put("experimental.spill-enabled", "true")
                .put("experimental.aggregation-operator-unspill-memory-limit", "100MB")
                .put("experimental.spiller-spill-path", "/tmp/custom/spill/path1,/tmp/custom/spill/path2")
                .put("experimental.spiller-threads", "42")
                .put("experimental.spiller-max-used-space-threshold", "0.8")
                .put("experimental.memory-revoking-threshold", "0.2")
                .put("experimental.memory-revoking-target", "0.8")
                .put("exchange.compression-enabled", "true")
                .put("deprecated.legacy-timestamp", "false")
                .put("optimizer.enable-intermediate-aggregations", "true")
                .put("parse-decimal-literals-as-double", "false")
                .put("optimizer.force-single-node-output", "false")
                .put("pages-index.eager-compaction-enabled", "true")
                .put("experimental.filter-and-project-min-output-page-size", "1MB")
                .put("experimental.filter-and-project-min-output-page-row-count", "2048")
                .put("histogram.implemenation", "LEGACY")
                .build();

        FeaturesConfig expected = new FeaturesConfig()
                .setCpuCostWeight(0.4)
                .setMemoryCostWeight(0.3)
                .setNetworkCostWeight(0.2)
                .setResourceGroupsEnabled(true)
                .setIterativeOptimizerEnabled(false)
                .setIterativeOptimizerTimeout(new Duration(10, SECONDS))
                .setEnableNewStatsCalculator(true)
                .setDistributedIndexJoinsEnabled(true)
                .setDistributedJoinsEnabled(false)
                .setFastInequalityJoins(false)
                .setColocatedJoinsEnabled(true)
                .setSpatialJoinsEnabled(false)
                .setJoinReorderingEnabled(false)
                .setRedistributeWrites(false)
                .setScaleWriters(true)
                .setWriterMinSize(new DataSize(42, GIGABYTE))
                .setOptimizeMetadataQueries(true)
                .setOptimizeHashGeneration(false)
                .setOptimizeSingleDistinct(false)
                .setOptimizeMixedDistinctAggregations(true)
                .setPushTableWriteThroughUnion(false)
                .setDictionaryAggregation(true)
                .setPushAggregationThroughJoin(false)
                .setLegacyArrayAgg(true)
                .setLegacyMapSubscript(true)
                .setRegexLibrary(RE2J)
                .setRe2JDfaStatesLimit(42)
                .setRe2JDfaRetries(42)
                .setSpillEnabled(true)
                .setAggregationOperatorUnspillMemoryLimit(DataSize.valueOf("100MB"))
                .setSpillerSpillPaths("/tmp/custom/spill/path1,/tmp/custom/spill/path2")
                .setSpillerThreads(42)
                .setSpillMaxUsedSpaceThreshold(0.8)
                .setMemoryRevokingThreshold(0.2)
                .setMemoryRevokingTarget(0.8)
                .setLegacyOrderBy(true)
                .setExchangeCompressionEnabled(true)
                .setLegacyTimestamp(false)
                .setLegacyJoinUsing(true)
                .setEnableIntermediateAggregations(true)
                .setParseDecimalLiteralsAsDouble(false)
                .setForceSingleNodeOutput(false)
                .setPagesIndexEagerCompactionEnabled(true)
                .setFilterAndProjectMinOutputPageSize(new DataSize(1, MEGABYTE))
                .setFilterAndProjectMinOutputPageRowCount(2048)
                .setHistogramGroupImplementation(LEGACY);
        assertFullMapping(properties, expected);
    }

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = ".*\\Q" + SPILLER_SPILL_PATH + " must be configured when " + SPILL_ENABLED + " is set to true\\E.*")
    public void testValidateSpillConfiguredIfEnabled()
    {
        new ConfigurationFactory(ImmutableMap.of(SPILL_ENABLED, "true"))
                .build(FeaturesConfig.class);
    }
}
