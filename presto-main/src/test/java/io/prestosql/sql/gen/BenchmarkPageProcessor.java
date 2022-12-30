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
package io.prestosql.sql.gen;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.metadata.Metadata;
import io.prestosql.operator.DriverYieldSignal;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.sql.relational.CallExpression;
import io.prestosql.sql.relational.RowExpression;
import io.prestosql.sql.relational.SpecialForm;
import io.prestosql.sql.relational.SpecialForm.Form;
import io.prestosql.tpch.LineItem;
import io.prestosql.tpch.LineItemGenerator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.MULTIPLY;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.relational.Expressions.constant;
import static io.prestosql.sql.relational.Expressions.field;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(5)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkPageProcessor
{
    private static final int EXTENDED_PRICE = 0;
    private static final int DISCOUNT = 1;
    private static final int SHIP_DATE = 2;
    private static final int QUANTITY = 3;

    private static final Slice MIN_SHIP_DATE = utf8Slice("1994-01-01");
    private static final Slice MAX_SHIP_DATE = utf8Slice("1995-01-01");

    private Page inputPage;
    private PageProcessor compiledProcessor;

    @Setup
    public void setup()
    {
        inputPage = createInputPage();

        Metadata metadata = createTestMetadataManager();
        RowExpression filterExpression = createFilterExpression(metadata);
        RowExpression projectExpression = createProjectExpression(metadata);
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(metadata, new PageFunctionCompiler(metadata, 0));
        compiledProcessor = expressionCompiler.compilePageProcessor(Optional.of(filterExpression), ImmutableList.of(projectExpression)).get();
    }

    @Benchmark
    public Page handCoded()
    {
        PageBuilder pageBuilder = new PageBuilder(ImmutableList.of(DOUBLE));
        int count = Tpch1FilterAndProject.process(inputPage, 0, inputPage.getPositionCount(), pageBuilder);
        checkState(count == inputPage.getPositionCount());
        return pageBuilder.build();
    }

    @Benchmark
    public List<Optional<Page>> compiled()
    {
        return ImmutableList.copyOf(
                compiledProcessor.process(
                        null,
                        new DriverYieldSignal(),
                        newSimpleAggregatedMemoryContext().newLocalMemoryContext(PageProcessor.class.getSimpleName()),
                        inputPage));
    }

    public static void main(String[] args)
            throws RunnerException
    {
        new BenchmarkPageProcessor().setup();

        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkPageProcessor.class.getSimpleName() + ".*")
                .build();

        new Runner(options).run();
    }

    private static Page createInputPage()
    {
        PageBuilder pageBuilder = new PageBuilder(ImmutableList.of(DOUBLE, DOUBLE, VARCHAR, DOUBLE));
        LineItemGenerator lineItemGenerator = new LineItemGenerator(1, 1, 1);
        Iterator<LineItem> iterator = lineItemGenerator.iterator();
        for (int i = 0; i < 10_000; i++) {
            pageBuilder.declarePosition();

            LineItem lineItem = iterator.next();
            DOUBLE.writeDouble(pageBuilder.getBlockBuilder(EXTENDED_PRICE), lineItem.getExtendedPrice());
            DOUBLE.writeDouble(pageBuilder.getBlockBuilder(DISCOUNT), lineItem.getDiscount());
            DATE.writeLong(pageBuilder.getBlockBuilder(SHIP_DATE), lineItem.getShipDate());
            DOUBLE.writeDouble(pageBuilder.getBlockBuilder(QUANTITY), lineItem.getQuantity());
        }
        return pageBuilder.build();
    }

    private static final class Tpch1FilterAndProject
    {
        public static int process(Page page, int start, int end, PageBuilder pageBuilder)
        {
            Block discountBlock = page.getBlock(DISCOUNT);
            int position = start;
            for (; position < end; position++) {
                // where shipdate >= '1994-01-01'
                //    and shipdate < '1995-01-01'
                //    and discount >= 0.05
                //    and discount <= 0.07
                //    and quantity < 24;
                if (filter(position, discountBlock, page.getBlock(SHIP_DATE), page.getBlock(QUANTITY))) {
                    project(position, pageBuilder, page.getBlock(EXTENDED_PRICE), discountBlock);
                }
            }

            return position;
        }

        private static void project(int position, PageBuilder pageBuilder, Block extendedPriceBlock, Block discountBlock)
        {
            pageBuilder.declarePosition();
            if (discountBlock.isNull(position) || extendedPriceBlock.isNull(position)) {
                pageBuilder.getBlockBuilder(0).appendNull();
            }
            else {
                DOUBLE.writeDouble(pageBuilder.getBlockBuilder(0), DOUBLE.getDouble(extendedPriceBlock, position) * DOUBLE.getDouble(discountBlock, position));
            }
        }

        private static boolean filter(int position, Block discountBlock, Block shipDateBlock, Block quantityBlock)
        {
            return !shipDateBlock.isNull(position) && VARCHAR.getSlice(shipDateBlock, position).compareTo(MIN_SHIP_DATE) >= 0 &&
                    !shipDateBlock.isNull(position) && VARCHAR.getSlice(shipDateBlock, position).compareTo(MAX_SHIP_DATE) < 0 &&
                    !discountBlock.isNull(position) && DOUBLE.getDouble(discountBlock, position) >= 0.05 &&
                    !discountBlock.isNull(position) && DOUBLE.getDouble(discountBlock, position) <= 0.07 &&
                    !quantityBlock.isNull(position) && DOUBLE.getDouble(quantityBlock, position) < 24;
        }
    }

    // where shipdate >= '1994-01-01'
    //    and shipdate < '1995-01-01'
    //    and discount >= 0.05
    //    and discount <= 0.07
    //    and quantity < 24;
    private static RowExpression createFilterExpression(Metadata metadata)
    {
        return new SpecialForm(
                Form.AND,
                BOOLEAN,
                new CallExpression(
                        metadata.resolveOperator(GREATER_THAN_OR_EQUAL, ImmutableList.of(VARCHAR, VARCHAR)),
                        BOOLEAN,
                        ImmutableList.of(field(SHIP_DATE, VARCHAR), constant(MIN_SHIP_DATE, VARCHAR))),
                new SpecialForm(
                        Form.AND,
                        BOOLEAN,
                        new CallExpression(
                                metadata.resolveOperator(LESS_THAN, ImmutableList.of(VARCHAR, VARCHAR)),
                                BOOLEAN,
                                ImmutableList.of(field(SHIP_DATE, VARCHAR), constant(MAX_SHIP_DATE, VARCHAR))),
                        new SpecialForm(
                                Form.AND,
                                BOOLEAN,
                                new CallExpression(
                                        metadata.resolveOperator(GREATER_THAN_OR_EQUAL, ImmutableList.of(DOUBLE, DOUBLE)),
                                        BOOLEAN,
                                        ImmutableList.of(field(DISCOUNT, DOUBLE), constant(0.05, DOUBLE))),
                                new SpecialForm(
                                        Form.AND,
                                        BOOLEAN,
                                        new CallExpression(
                                                metadata.resolveOperator(LESS_THAN_OR_EQUAL, ImmutableList.of(DOUBLE, DOUBLE)),
                                                BOOLEAN,
                                                ImmutableList.of(field(DISCOUNT, DOUBLE), constant(0.07, DOUBLE))),
                                        new CallExpression(
                                                metadata.resolveOperator(LESS_THAN, ImmutableList.of(DOUBLE, DOUBLE)),
                                                BOOLEAN,
                                                ImmutableList.of(field(QUANTITY, DOUBLE), constant(24.0, DOUBLE)))))));
    }

    private static RowExpression createProjectExpression(Metadata metadata)
    {
        return new CallExpression(
                metadata.resolveOperator(MULTIPLY, ImmutableList.of(DOUBLE, DOUBLE)),
                DOUBLE,
                ImmutableList.of(field(EXTENDED_PRICE, DOUBLE), field(DISCOUNT, DOUBLE)));
    }
}
