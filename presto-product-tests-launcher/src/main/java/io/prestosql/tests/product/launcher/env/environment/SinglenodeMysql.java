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
package io.prestosql.tests.product.launcher.env.environment;

import com.google.common.collect.ImmutableList;
import io.prestosql.tests.product.launcher.docker.DockerFiles;
import io.prestosql.tests.product.launcher.env.DockerContainer;
import io.prestosql.tests.product.launcher.env.Environment;
import io.prestosql.tests.product.launcher.env.common.AbstractEnvironmentProvider;
import io.prestosql.tests.product.launcher.env.common.Standard;
import io.prestosql.tests.product.launcher.env.common.TestsEnvironment;
import io.prestosql.tests.product.launcher.testcontainers.SelectedPortWaitStrategy;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;

import javax.inject.Inject;

import static io.prestosql.tests.product.launcher.env.common.Standard.CONTAINER_PRESTO_ETC;
import static io.prestosql.tests.product.launcher.testcontainers.TestcontainersUtil.exposePort;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.containers.BindMode.READ_ONLY;

@TestsEnvironment
public final class SinglenodeMysql
        extends AbstractEnvironmentProvider
{
    // Use non-default MySQL port to avoid conflicts with locally installed MySQL if any.
    public static final int MYSQL_PORT = 13306;

    private final DockerFiles dockerFiles;

    @Inject
    public SinglenodeMysql(Standard standard, DockerFiles dockerFiles)
    {
        super(ImmutableList.of(standard));
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
    }

    @Override
    protected void extendEnvironment(Environment.Builder builder)
    {
        builder.configureContainer("presto-master", container -> container
                .withFileSystemBind(
                        dockerFiles.getDockerFilesHostPath("conf/environment/singlenode-mysql/mysql.properties"),
                        CONTAINER_PRESTO_ETC + "/catalog/mysql.properties",
                        READ_ONLY));

        builder.addContainer("mysql", createMySql());
    }

    @SuppressWarnings("resource")
    private DockerContainer createMySql()
    {
        DockerContainer container = new DockerContainer("mysql:5.7")
                .withEnv("MYSQL_USER", "test")
                .withEnv("MYSQL_PASSWORD", "test")
                .withEnv("MYSQL_ROOT_PASSWORD", "test")
                .withEnv("MYSQL_DATABASE", "test")
                .withCommand("mysqld", "--port", Integer.toString(MYSQL_PORT))
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .waitingFor(new SelectedPortWaitStrategy(MYSQL_PORT));

        exposePort(container, MYSQL_PORT);

        return container;
    }
}
