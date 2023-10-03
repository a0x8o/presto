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
package io.trino.plugin.deltalake;

import io.trino.plugin.hive.containers.HiveHadoop;
import io.trino.plugin.hive.containers.HiveMinioDataLake;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;

public abstract class BaseDeltaLakeAwsConnectorSmokeTest
        extends BaseDeltaLakeConnectorSmokeTest
{
    protected HiveMinioDataLake hiveMinioDataLake;

    @Override
    protected HiveHadoop createHiveHadoop()
    {
        hiveMinioDataLake = closeAfterClass(new HiveMinioDataLake(bucketName));
        hiveMinioDataLake.start();
        return hiveMinioDataLake.getHiveHadoop();  // closed by superclass
    }

    @Override
    @AfterClass(alwaysRun = true)
    public void cleanUp()
    {
        hiveMinioDataLake = null; // closed by closeAfterClass
        super.cleanUp();
    }

    @Override
    protected void registerTableFromResources(String table, String resourcePath, QueryRunner queryRunner)
    {
        hiveMinioDataLake.copyResources(resourcePath, table);
        queryRunner.execute(format(
                "CALL system.register_table('%s', '%s', '%s')",
                SCHEMA,
                table,
                getLocationForTable(bucketName, table)));
    }

    @Override
    protected String getLocationForTable(String bucketName, String tableName)
    {
        return format("s3://%s/%s", bucketName, tableName);
    }

    @Override
    protected List<String> getTableFiles(String tableName)
    {
        return hiveMinioDataLake.listFiles(tableName).stream()
                .map(path -> format("s3://%s/%s", bucketName, path))
                .collect(toImmutableList());
    }

    @Override
    protected List<String> listFiles(String directory)
    {
        return hiveMinioDataLake.listFiles(directory).stream()
                .map(path -> format("s3://%s/%s", bucketName, path))
                .collect(toImmutableList());
    }

    @Override
    protected void deleteFile(String filePath)
    {
        String key = filePath.substring(bucketUrl().length());
        hiveMinioDataLake.getMinioClient()
                .removeObject(bucketName, key);
    }

    @Override
    protected String bucketUrl()
    {
        return format("s3://%s/", bucketName);
    }
}
