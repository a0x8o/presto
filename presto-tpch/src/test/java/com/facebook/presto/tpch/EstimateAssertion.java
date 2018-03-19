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
package com.facebook.presto.tpch;

import com.facebook.presto.spi.statistics.Estimate;
import io.airlift.slice.Slice;

import java.util.Optional;

import static java.lang.String.format;
import static java.util.Optional.empty;
import static org.testng.Assert.assertEquals;

class EstimateAssertion
{
    private final double tolerance;

    public EstimateAssertion(double tolerance)
    {
        this.tolerance = tolerance;
    }

    public void assertClose(Estimate actual, Estimate expected, String message)
    {
        assertClose(toOptional(actual), toOptional(expected), message);
    }

    private Optional<Double> toOptional(Estimate estimate)
    {
        return estimate.isValueUnknown() ? empty() : Optional.of(estimate.getValue());
    }

    public void assertClose(Optional<?> actual, Optional<?> expected, String message)
    {
        assertEquals(actual.isPresent(), expected.isPresent(), message);
        if (actual.isPresent()) {
            Object actualValue = actual.get();
            Object expectedValue = expected.get();
            assertClose(actualValue, expectedValue, message);
        }
    }

    private void assertClose(Object actual, Object expected, String message)
    {
        if (actual instanceof Slice) {
            assertEquals(actual.getClass(), expected.getClass(), message);
            assertEquals(((Slice) actual).toStringUtf8(), ((Slice) expected).toStringUtf8());
        }
        else {
            double actualDouble = toDouble(actual);
            double expectedDouble = toDouble(expected);
            assertEquals(actualDouble, expectedDouble, expectedDouble * tolerance, message);
        }
    }

    private double toDouble(Object object)
    {
        if (object instanceof Number) {
            return ((Number) object).doubleValue();
        }
        else {
            String message = "Can't compare with tolerance objects of class %s. Use assertEquals.";
            throw new UnsupportedOperationException(format(message, object.getClass()));
        }
    }
}
