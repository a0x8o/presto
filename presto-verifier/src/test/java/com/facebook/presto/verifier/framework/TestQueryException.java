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
package com.facebook.presto.verifier.framework;

import org.testng.annotations.Test;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Optional;

import static com.facebook.presto.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static com.facebook.presto.verifier.framework.QueryStage.CONTROL_MAIN;
import static org.testng.Assert.assertEquals;

public class TestQueryException
{
    private static final QueryStage QUERY_STAGE = CONTROL_MAIN;

    @Test
    public void testErrorCode()
    {
        assertEquals(
                QueryException.forClusterConnection(new SocketTimeoutException(), QUERY_STAGE).getErrorCode(),
                "CLUSTER_CONNECTION(SocketTimeoutException)");
        assertEquals(
                QueryException.forPresto(new SQLException(), Optional.of(REMOTE_TASK_ERROR), false, Optional.empty(), QUERY_STAGE).getErrorCode(),
                "PRESTO(REMOTE_TASK_ERROR)");
        assertEquals(
                QueryException.forPresto(new SQLException(), Optional.empty(), false, Optional.empty(), QUERY_STAGE).getErrorCode(),
                "PRESTO(UNKNOWN)");
    }
}
