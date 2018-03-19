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
package com.facebook.presto.ml;

import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.ScalarFunction;
import com.facebook.presto.spi.function.SqlType;
import com.facebook.presto.spi.function.TypeParameter;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

public final class MLFeaturesFunctions
{
    private static final Cache<HashCode, Model> MODEL_CACHE = CacheBuilder.newBuilder().maximumSize(5).build();
    private static final String MAP_BIGINT_DOUBLE = "map(bigint,double)";

    private final PageBuilder pageBuilder;

    public MLFeaturesFunctions(@TypeParameter("map(bigint,double)") Type mapType)
    {
        pageBuilder = new PageBuilder(ImmutableList.of(mapType));
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1)
    {
        return featuresHelper(f1);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2)
    {
        return featuresHelper(f1, f2);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3)
    {
        return featuresHelper(f1, f2, f3);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4)
    {
        return featuresHelper(f1, f2, f3, f4);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5)
    {
        return featuresHelper(f1, f2, f3, f4, f5);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5, @SqlType(StandardTypes.DOUBLE) double f6)
    {
        return featuresHelper(f1, f2, f3, f4, f5, f6);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5, @SqlType(StandardTypes.DOUBLE) double f6, @SqlType(StandardTypes.DOUBLE) double f7)
    {
        return featuresHelper(f1, f2, f3, f4, f5, f6, f7);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5, @SqlType(StandardTypes.DOUBLE) double f6, @SqlType(StandardTypes.DOUBLE) double f7, @SqlType(StandardTypes.DOUBLE) double f8)
    {
        return featuresHelper(f1, f2, f3, f4, f5, f6, f7, f8);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5, @SqlType(StandardTypes.DOUBLE) double f6, @SqlType(StandardTypes.DOUBLE) double f7, @SqlType(StandardTypes.DOUBLE) double f8, @SqlType(StandardTypes.DOUBLE) double f9)
    {
        return featuresHelper(f1, f2, f3, f4, f5, f6, f7, f8, f9);
    }

    @ScalarFunction
    @SqlType(MAP_BIGINT_DOUBLE)
    public Block features(@SqlType(StandardTypes.DOUBLE) double f1, @SqlType(StandardTypes.DOUBLE) double f2, @SqlType(StandardTypes.DOUBLE) double f3, @SqlType(StandardTypes.DOUBLE) double f4, @SqlType(StandardTypes.DOUBLE) double f5, @SqlType(StandardTypes.DOUBLE) double f6, @SqlType(StandardTypes.DOUBLE) double f7, @SqlType(StandardTypes.DOUBLE) double f8, @SqlType(StandardTypes.DOUBLE) double f9, @SqlType(StandardTypes.DOUBLE) double f10)
    {
        return featuresHelper(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10);
    }

    private Block featuresHelper(double... features)
    {
        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }

        BlockBuilder mapBlockBuilder = pageBuilder.getBlockBuilder(0);
        BlockBuilder blockBuilder = mapBlockBuilder.beginBlockEntry();

        for (int i = 0; i < features.length; i++) {
            BigintType.BIGINT.writeLong(blockBuilder, i);
            DoubleType.DOUBLE.writeDouble(blockBuilder, features[i]);
        }

        mapBlockBuilder.closeEntry();
        pageBuilder.declarePosition();
        return mapBlockBuilder.getObject(mapBlockBuilder.getPositionCount() - 1, Block.class);
    }
}
