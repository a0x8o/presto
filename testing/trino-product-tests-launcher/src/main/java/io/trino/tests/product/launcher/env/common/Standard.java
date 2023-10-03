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
package io.trino.tests.product.launcher.env.common;

import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.tests.product.launcher.docker.DockerFiles;
import io.trino.tests.product.launcher.env.Debug;
import io.trino.tests.product.launcher.env.DockerContainer;
import io.trino.tests.product.launcher.env.Environment;
import io.trino.tests.product.launcher.env.EnvironmentConfig;
import io.trino.tests.product.launcher.env.EnvironmentContainers;
import io.trino.tests.product.launcher.env.ServerPackage;
import io.trino.tests.product.launcher.env.jdk.JdkProvider;
import io.trino.tests.product.launcher.testcontainers.PortBinder;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.COORDINATOR;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.TESTS;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.WORKER;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.WORKER_NTH;
import static io.trino.tests.product.launcher.testcontainers.PortBinder.unsafelyExposePort;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.wait.strategy.Wait.forHealthcheck;
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage;
import static org.testcontainers.utility.MountableFile.forHostPath;

/**
 * @deprecated Product tests should mimic production like use case.
 * Single node environment does not do it well enough and some issues are
 * only exposed with multi node installations. Use {@link StandardMultinode} instead.
 */
@Deprecated
public final class Standard
        implements EnvironmentExtender
{
    private static final Logger log = Logger.get(Standard.class);

    public static final String CONTAINER_HEALTH_D = "/etc/health.d/";
    public static final String CONTAINER_CONF_ROOT = "/docker/presto-product-tests/";
    public static final String CONTAINER_TRINO_ETC = CONTAINER_CONF_ROOT + "conf/presto/etc";
    public static final String CONTAINER_TRINO_JVM_CONFIG = CONTAINER_TRINO_ETC + "/jvm.config";
    public static final String CONTAINER_TRINO_ACCESS_CONTROL_PROPERTIES = CONTAINER_TRINO_ETC + "/access-control.properties";
    public static final String CONTAINER_TRINO_CONFIG_PROPERTIES = CONTAINER_TRINO_ETC + "/config.properties";
    /**
     * @deprecated please use {@link EnvironmentContainers#configureTempto} instead.
     */
    public static final String CONTAINER_TEMPTO_PROFILE_CONFIG = "/docker/presto-product-tests/conf/tempto/tempto-configuration-profile-config-file.yaml";

    private final DockerFiles dockerFiles;
    private final PortBinder portBinder;

    private final String imagesVersion;
    private final JdkProvider jdkProvider;
    private final File serverPackage;
    private final boolean debug;

    @Inject
    public Standard(
            DockerFiles dockerFiles,
            PortBinder portBinder,
            EnvironmentConfig environmentConfig,
            @ServerPackage File serverPackage,
            JdkProvider jdkProvider,
            @Debug boolean debug)
    {
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
        this.portBinder = requireNonNull(portBinder, "portBinder is null");
        this.imagesVersion = environmentConfig.getImagesVersion();
        this.jdkProvider = requireNonNull(jdkProvider, "jdkProvider is null");
        this.serverPackage = requireNonNull(serverPackage, "serverPackage is null");
        this.debug = debug;
        checkArgument(serverPackage.getName().endsWith(".tar.gz"), "Currently only server .tar.gz package is supported");
    }

    @Override
    public void extendEnvironment(Environment.Builder builder)
    {
        builder.addContainers(createTrinoCoordinator(), createTestsContainer());
        // default catalogs copied from /docker/presto-product-tests
        builder.addConnector("blackhole");
        builder.addConnector("jmx");
        builder.addConnector("system");
        builder.addConnector("tpch");
    }

    @SuppressWarnings("resource")
    private DockerContainer createTrinoCoordinator()
    {
        DockerContainer container =
                createTrinoContainer(dockerFiles, serverPackage, jdkProvider, debug, "ghcr.io/trinodb/testing/centos7-oj17:" + imagesVersion, COORDINATOR)
                        .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/standard/access-control.properties")), CONTAINER_TRINO_ACCESS_CONTROL_PROPERTIES)
                        .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/standard/config.properties")), CONTAINER_TRINO_CONFIG_PROPERTIES);

        portBinder.exposePort(container, 8080); // Trino default port
        return container;
    }

    @SuppressWarnings("resource")
    private DockerContainer createTestsContainer()
    {
        return new DockerContainer("ghcr.io/trinodb/testing/centos7-oj17:" + imagesVersion, TESTS)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath()), "/docker/presto-product-tests")
                .withCommand("bash", "-xeuc", "echo 'No command provided' >&2; exit 69")
                .waitingFor(new WaitAllStrategy()) // don't wait
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy());
    }

    @SuppressWarnings("resource")
    public static DockerContainer createTrinoContainer(DockerFiles dockerFiles, File serverPackage, JdkProvider jdkProvider, boolean debug, String dockerImageName, String logicalName)
    {
        DockerContainer container = new DockerContainer(dockerImageName, logicalName)
                .withNetworkAliases(logicalName + ".docker.cluster")
                .withExposedLogPaths("/var/trino/var/log", "/var/log/container-health.log")
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath()), "/docker/presto-product-tests")
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("conf/presto/etc/jvm.config")), CONTAINER_TRINO_JVM_CONFIG)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("health-checks/trino-health-check.sh")), CONTAINER_HEALTH_D + "trino-health-check.sh")
                // the server package is hundreds MB and file system bind is much more efficient
                .withFileSystemBind(serverPackage.getPath(), "/docker/presto-server.tar.gz", READ_ONLY)
                .withEnv("JAVA_HOME", jdkProvider.getJavaHome())
                .withCommand("/docker/presto-product-tests/run-presto.sh")
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .waitingForAll(forLogMessage(".*======== SERVER STARTED ========.*", 1), forHealthcheck())
                .withStartupTimeout(Duration.ofMinutes(5));
        if (debug) {
            enableTrinoJavaDebugger(container);
            enableTrinoJmxRmi(container);
        }
        else {
            container.withHealthCheck(dockerFiles.getDockerFilesHostPath("health-checks/health.sh"));
        }

        return jdkProvider.applyTo(container);
    }

    private static void enableTrinoJavaDebugger(DockerContainer dockerContainer)
    {
        String logicalName = dockerContainer.getLogicalName();

        int debugPort;
        if (logicalName.equals(COORDINATOR)) {
            debugPort = 5005;
        }
        else if (logicalName.equals(WORKER)) {
            debugPort = 5009;
        }
        else if (logicalName.startsWith(WORKER_NTH)) {
            int workerNumber = parseInt(logicalName.substring(WORKER_NTH.length()));
            debugPort = 5008 + workerNumber;
        }
        else {
            throw new IllegalStateException("Cannot enable Java debugger for: " + logicalName);
        }

        enableTrinoJavaDebugger(dockerContainer, logicalName, debugPort);
    }

    private static void enableTrinoJavaDebugger(DockerContainer container, String containerName, int debugPort)
    {
        log.info("Enabling Java debugger for container: '%s' on port %d", containerName, debugPort);

        try {
            FileAttribute<Set<PosixFilePermission>> rwx = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
            Path script = Files.createTempFile("enable-java-debugger", ".sh", rwx);
            script.toFile().deleteOnExit();
            Files.writeString(
                    script,
                    format(
                            "#!/bin/bash\n" +
                                    "printf '%%s\\n' '%s' >> '%s'\n",
                            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:" + debugPort,
                            CONTAINER_TRINO_JVM_CONFIG),
                    UTF_8);
            container.withCopyFileToContainer(forHostPath(script), "/docker/presto-init.d/enable-java-debugger.sh");

            // expose debug port unconditionally when debug is enabled
            unsafelyExposePort(container, debugPort);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void enableTrinoJmxRmi(DockerContainer dockerContainer)
    {
        String logicalName = dockerContainer.getLogicalName();

        int jmxPort;
        if (logicalName.equals(COORDINATOR)) {
            jmxPort = 6005;
        }
        else if (logicalName.equals(WORKER)) {
            jmxPort = 6009;
        }
        else if (logicalName.startsWith(WORKER_NTH)) {
            int workerNumber = parseInt(logicalName.substring(WORKER_NTH.length()));
            jmxPort = 6008 + workerNumber;
        }
        else {
            throw new IllegalStateException("Cannot enable Java JMX RMI for: " + logicalName);
        }

        enableTrinoJmxRmi(dockerContainer, logicalName, jmxPort);
    }

    private static void enableTrinoJmxRmi(DockerContainer container, String containerName, int jmxPort)
    {
        log.info("Enabling Java JMX RMI for container: '%s' on port %d", containerName, jmxPort);

        try {
            FileAttribute<Set<PosixFilePermission>> rwx = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
            Path script = Files.createTempFile("enable-java-jmx-rmi", ".sh", rwx);
            script.toFile().deleteOnExit();
            Files.writeString(
                    script,
                        """
                                #!/bin/bash
                                printf '%%s\\n' '-Dcom.sun.management.jmxremote=true' >> '%2$s'
                                printf '%%s\\n' '-Dcom.sun.management.jmxremote.port=%1$s' >> '%2$s'
                                printf '%%s\\n' '-Dcom.sun.management.jmxremote.rmi.port=%1$s' >> '%2$s'
                                printf '%%s\\n' '-Dcom.sun.management.jmxremote.authenticate=false' >> '%2$s'
                                printf '%%s\\n' '-Djava.rmi.server.hostname=0.0.0.0' >> '%2$s'
                                printf '%%s\\n' '-Dcom.sun.management.jmxremote.ssl=false' >> '%2$s'
                                printf '%%s\\n' '-XX:FlightRecorderOptions=stackdepth=256' >> '%2$s'
                                """.formatted(Integer.toString(jmxPort), CONTAINER_TRINO_JVM_CONFIG),
                    UTF_8);
            container.withCopyFileToContainer(forHostPath(script), "/docker/presto-init.d/enable-java-jmx-rmi.sh");

            // expose JMX port unconditionally when debug is enabled
            unsafelyExposePort(container, jmxPort);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
