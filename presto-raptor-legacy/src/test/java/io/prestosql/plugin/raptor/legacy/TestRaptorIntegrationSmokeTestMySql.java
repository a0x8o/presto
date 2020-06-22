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
package io.prestosql.plugin.raptor.legacy;

import com.google.common.collect.ImmutableMap;
import io.prestosql.plugin.tpch.TpchPlugin;
import io.prestosql.testing.DistributedQueryRunner;
import io.prestosql.testing.QueryRunner;
import org.testcontainers.containers.MySQLContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Map;

import static io.prestosql.plugin.raptor.legacy.RaptorQueryRunner.copyTables;
import static io.prestosql.plugin.raptor.legacy.RaptorQueryRunner.createSession;
import static java.lang.String.format;

@Test
public class TestRaptorIntegrationSmokeTestMySql
        extends TestRaptorIntegrationSmokeTest
{
    private MySQLContainer<?> mysqlContainer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        mysqlContainer = new MySQLContainer<>("mysql:8.0.12");
        mysqlContainer.start();
        return createRaptorMySqlQueryRunner(getJdbcUrl(mysqlContainer));
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
    {
        mysqlContainer.close();
    }

    private static String getJdbcUrl(MySQLContainer<?> container)
    {
        return format("%s?user=%s&password=%s&useSSL=false&allowPublicKeyRetrieval=true",
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword());
    }

    private static QueryRunner createRaptorMySqlQueryRunner(String mysqlUrl)
            throws Exception
    {
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(createSession("tpch")).build();

        queryRunner.installPlugin(new TpchPlugin());
        queryRunner.createCatalog("tpch", "tpch");

        queryRunner.installPlugin(new RaptorPlugin());
        File baseDir = queryRunner.getCoordinator().getBaseDataDir().toFile();
        Map<String, String> raptorProperties = ImmutableMap.<String, String>builder()
                .put("metadata.db.type", "mysql")
                .put("metadata.db.url", mysqlUrl)
                .put("storage.data-directory", new File(baseDir, "data").getAbsolutePath())
                .put("storage.max-shard-rows", "2000")
                .put("backup.provider", "file")
                .put("backup.directory", new File(baseDir, "backup").getAbsolutePath())
                .build();

        queryRunner.createCatalog("raptor", "raptor-legacy", raptorProperties);

        copyTables(queryRunner, "tpch", createSession(), false);

        return queryRunner;
    }
}
