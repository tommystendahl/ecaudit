/*
 * Copyright 2018 Telefonaktiebolaget LM Ericsson
 *
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
package com.ericsson.bss.cassandra.ecaudit.test.daemon;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Random;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.CqlSession;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.service.CassandraDaemon;

/**
 * Singleton for creating a Cassandra Daemon for Test.
 */
@SuppressWarnings("PMD")
public class CassandraDaemonForAuditTest // NOSONAR
{
    private static final Logger LOG = LoggerFactory.getLogger(CassandraDaemonForAuditTest.class);

    private static CassandraDaemonForAuditTest cdtSingleton;

    private final CassandraDaemon cassandraDaemon;

    private File tempDir;

    private volatile int storagePort = -1;
    private volatile int sslStoragePort = -1;
    private volatile int nativePort = -1;
    private volatile int jmxPort = -1;

    public static CassandraDaemonForAuditTest getInstance() throws IOException
    {
        synchronized (CassandraDaemonForAuditTest.class)
        {
            if (cdtSingleton == null)
            {
                cdtSingleton = new CassandraDaemonForAuditTest();
            }

            cdtSingleton.activate();
        }

        return cdtSingleton;
    }

    private CassandraDaemonForAuditTest() throws IOException
    {
        setupConfiguration();
        cassandraDaemon = new CassandraDaemon(true);
    }

    /**
     * Setup the Cassandra configuration for this instance.
     */
    private void setupConfiguration() throws IOException
    {
        randomizePorts();

        tempDir = com.google.common.io.Files.createTempDir();
        tempDir.deleteOnExit();
        Files.createDirectory(getAuditDirectory());

        Path cassandraYamlPath = moveResourceFileToTempDirWithSubstitution("cassandra.yaml");
        System.setProperty("cassandra.config", cassandraYamlPath.toUri().toURL().toExternalForm());

        System.setProperty("cassandra.jmx.local.port", String.valueOf(jmxPort));

        Path cassandraRackDcPath = moveResourceFileToTempDirWithSubstitution("cassandra-rackdc.properties");
        System.setProperty("cassandra-rackdc.properties", cassandraRackDcPath.toUri().toURL().toExternalForm());

        System.setProperty("cassandra-foreground", "true");
        System.setProperty("cassandra.superuser_setup_delay_ms", "1");

        System.setProperty("cassandra.custom_query_handler_class", "com.ericsson.bss.cassandra.ecaudit.handler.AuditQueryHandler");
        System.setProperty("ecaudit.filter_type", "YAML_AND_ROLE");

        Path auditYamlPath = moveResourceFileToTempDirWithSubstitution("integration_audit.yaml");
        System.setProperty("com.ericsson.bss.cassandra.ecaudit.config", auditYamlPath.toString());

        LOG.info("Using temporary cassandra directory: " + tempDir);
    }


    private void activate()
    {
        if (!cassandraDaemon.setupCompleted() && !cassandraDaemon.isNativeTransportRunning())
        {
            Runtime.getRuntime().addShutdownHook(new Thread(this::deactivate));
            cassandraDaemon.activate();
        }
        else if (!cassandraDaemon.isNativeTransportRunning())
        {
            cassandraDaemon.start();
        }

        // Cassandra create default super user in a setup task with a small delay
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void deactivate()
    {
        try
        {
            cassandraDaemon.deactivate();

            Thread.sleep(1000);

            if (tempDir != null && tempDir.exists())
            {
                try
                {
                    FileUtils.deleteDirectory(tempDir);
                }
                catch (IOException e)
                {
                    LOG.error("Failed to delete temp files", e);
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public CqlSession createSession()
    {
        return createSession("cassandra", "cassandra");
    }

    public CqlSession createSession(String username, String password)
    {
        DriverConfigLoader loader =
                DriverConfigLoader.programmaticBuilder()
                    .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5))
                    .build();

        return CqlSession.builder().addContactPoint(new InetSocketAddress(DatabaseDescriptor.getListenAddress(), nativePort))
                      .withCredentials(username, password).withLocalDatacenter("datacenter1").withConfigLoader(loader).build();
    }

    public Path getAuditDirectory()
    {
        return tempDir.toPath().resolve("audit");
    }

    private void randomizePorts()
    {
        storagePort = randomAvailablePort();
        sslStoragePort = randomAvailablePort();
        nativePort = randomAvailablePort();
        jmxPort = randomAvailablePort();
    }

    private int randomAvailablePort()
    {
        int port = -1;
        while (port < 0)
        {
            port = (new Random().nextInt(16300) + 49200);
            if (storagePort == port
                || sslStoragePort == port
                || nativePort == port
                || jmxPort == port)
            {
                port = -1;
            }
            else
            {
                try (ServerSocket socket = new ServerSocket(port))
                {
                    break;
                }
                catch (IOException e)
                {
                    port = -1;
                }
            }
        }
        return port;
    }

    private Path moveResourceFileToTempDirWithSubstitution(String filename) throws IOException
    {
        InputStream inStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(filename);
        String content = readStream(inStream);

        Path outPath = Paths.get(tempDir.getPath(), filename);
        content = content.replaceAll("###tmp###", tempDir.getPath().replace("\\", "\\\\"));
        content = content.replaceAll("###storage_port###", String.valueOf(storagePort));
        content = content.replaceAll("###ssl_storage_port###", String.valueOf(sslStoragePort));
        content = content.replaceAll("###native_transport_port###", String.valueOf(nativePort));
        Files.write(outPath, content.getBytes(StandardCharsets.UTF_8));

        LOG.debug("Created temporary resource at: {}", outPath);

        return outPath;
    }

    private static String readStream(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1)
        {
            out.write(buffer, 0, length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
