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
package com.ericsson.bss.cassandra.ecaudit.integration.standard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.ericsson.bss.cassandra.ecaudit.logger.Slf4jAuditLogger;
import com.ericsson.bss.cassandra.ecaudit.test.daemon.CassandraDaemonForAuditTest;
import net.jcip.annotations.NotThreadSafe;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@NotThreadSafe
@RunWith(MockitoJUnitRunner.class)
public class ITVerifyThreadedAudit
{
    private final static String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final int USER_COUNT = 5;

    private static CassandraDaemonForAuditTest cdt;

    @Mock
    private Appender<ILoggingEvent> mockAuditAppender;

    @BeforeClass
    public static void beforeClass() throws Exception
    {
        cdt = CassandraDaemonForAuditTest.getInstance();

        try (CqlSession session = cdt.createSession())
        {
            session.execute("CREATE KEYSPACE ecks_itvta WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");
            session.execute("CREATE TABLE ecks_itvta.ectbl (partk int PRIMARY KEY, clustk text, value text)");

            session.execute("CREATE ROLE test_role WITH LOGIN = false");
            session.execute("ALTER ROLE test_role WITH OPTIONS = { 'grant_audit_whitelist_for_select' : 'data/system' }");
            session.execute("ALTER ROLE test_role WITH OPTIONS = { 'grant_audit_whitelist_for_select' : 'data/system_schema' }");
            session.execute("ALTER ROLE test_role WITH OPTIONS = { 'grant_audit_whitelist_for_select' : 'data/system_virtual_schema' }");
            session.execute("ALTER ROLE test_role WITH OPTIONS = { 'grant_audit_whitelist_for_execute' : 'connections' }");
            session.execute("GRANT MODIFY ON ecks_itvta.ectbl TO test_role");
            session.execute("GRANT SELECT ON ecks_itvta.ectbl TO test_role");

            for (int i = 0; i < USER_COUNT; i++)
            {
                session.execute("CREATE ROLE user" + i + " WITH PASSWORD = 'secret' AND LOGIN = true");
                session.execute("GRANT test_role TO user" + i);
            }
        }
    }

    @Before
    public void before()
    {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Slf4jAuditLogger.AUDIT_LOGGER_NAME).addAppender(mockAuditAppender);
    }

    @After
    public void after()
    {
        verifyNoMoreInteractions(mockAuditAppender);
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Slf4jAuditLogger.AUDIT_LOGGER_NAME).detachAppender(mockAuditAppender);
    }

    @AfterClass
    public static void afterClass()
    {
        try (CqlSession session = cdt.createSession())
        {
            session.execute("DROP KEYSPACE IF EXISTS ecks_itvta");
            session.execute("DROP ROLE IF EXISTS test_role");

            for (int i = 0; i < USER_COUNT; i++)
            {
                session.execute("DROP ROLE IF EXISTS user" + i);
            }

        }
    }

    @Test
    public void testValidPreparedStatementsAreLogged() throws Exception
    {
        ExecutorService executorService = Executors.newFixedThreadPool(USER_COUNT * 2);
        List<Future<List<String>>> jobResults = new ArrayList<>();

        for (int i = 0; i < USER_COUNT; i++)
        {
            jobResults.add(executorService.submit(new PreparedStatementClient("user" + i)));
        }

        for (int i = 0; i < USER_COUNT; i++)
        {
            jobResults.add(executorService.submit(new BatchStatementClient("user" + i)));
        }

        for (int i = 0; i < USER_COUNT; i++)
        {
            jobResults.add(executorService.submit(new NonPreparedBatchStatementClient("user" + i)));
        }

        for (int i = 0; i < USER_COUNT; i++)
        {
            jobResults.add(executorService.submit(new PreparedBatchStatementClient("user" + i)));
        }

        List<String> expectedAttempts = new ArrayList<>();
        for (Future<List<String>> jobResult : jobResults)
        {
            expectedAttempts.addAll(jobResult.get());
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .flatExtracting(e -> Arrays.asList(e.split(UUID_REGEX)))
                        .containsOnlyElementsOf(expectedAttempts);
    }

    public class PreparedStatementClient implements Callable<List<String>>
    {
        private final String username;

        public PreparedStatementClient(String username)
        {
            this.username = username;
        }

        @Override
        public List<String> call()
        {
            try (CqlSession privateSession = cdt.createSession(username, "secret"))
            {
                PreparedStatement preparedInsertStatement = privateSession
                        .prepare("INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?)");
                PreparedStatement preparedSelectStatement = privateSession
                        .prepare("SELECT * FROM ecks_itvta.ectbl WHERE partk = ?");
                PreparedStatement preparedDeleteStatement = privateSession
                        .prepare("DELETE FROM ecks_itvta.ectbl WHERE partk = ?");

                List<String> expectedStatements = new ArrayList<>();

                for (int i = 0; i < 100; i++)
                {
                    expectedStatements.addAll(Arrays.asList(
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?)[" + i + ", '" + i
                                    + "', 'valid']",
                            "SELECT * FROM ecks_itvta.ectbl WHERE partk = ?[" + i + "]",
                            "DELETE FROM ecks_itvta.ectbl WHERE partk = ?[" + i + "]"));

                    privateSession.execute(preparedInsertStatement.bind(i, Integer.toString(i), "valid"));
                    privateSession.execute(preparedSelectStatement.bind(i));
                    privateSession.execute(preparedDeleteStatement.bind(i));
                }

                return expectedAttemptsAsUser(expectedStatements, username);
            }
        }
    }

    public class BatchStatementClient implements Callable<List<String>>
    {
        private final String username;

        public BatchStatementClient(String username)
        {
            this.username = username;
        }

        @Override
        public List<String> call()
        {
            try (CqlSession session = cdt.createSession(username, "secret"))
            {
                PreparedStatement preparedInsertStatement1 = session
                        .prepare("INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?)");
                PreparedStatement preparedInsertStatement2 = session
                        .prepare("INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, 'static')");
                PreparedStatement preparedInsertStatement3 = session
                        .prepare("INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (?, ?)");

                List<String> expectedStatements = new ArrayList<>();

                for (int i = 0; i < 10; i++)
                {
                    expectedStatements.addAll(Arrays.asList(
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?)[" + (100 + i) + ", '1', 'b1']",
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?)[" + (200 + i) + ", '2', 'b2']",
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, 'static')[" + (300 + i) + ", '3']",
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (?, ?)[" + (400 + i) + ", '4']",
                            "INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (?, ?)[" + (500 + i) + ", '5']"));
                    BatchStatement batch = BatchStatement.builder(DefaultBatchType.UNLOGGED)
                            .addStatement(preparedInsertStatement1.bind(100 + i, "1", "b1"))
                            .addStatement(preparedInsertStatement1.bind(200 + i, "2", "b2"))
                            .addStatement(preparedInsertStatement2.bind(300 + i, "3"))
                            .addStatement(preparedInsertStatement3.bind(400 + i, "4"))
                            .addStatement(preparedInsertStatement3.bind(500 + i, "5"))
                            .build();
                    session.execute(batch);
                }

                return expectedBatchAttemptFragmentsAsUser(expectedStatements, username);
            }
        }
    }

    public class NonPreparedBatchStatementClient implements Callable<List<String>>
    {
        private final String username;

        public NonPreparedBatchStatementClient(String username)
        {
            this.username = username;
        }

        @Override
        public List<String> call()
        {
            try (CqlSession session = cdt.createSession(username, "secret"))
            {
                List<String> expectedStatements = new ArrayList<>();

                for (int i = 0; i < 10; i++)
                {
                    String batchStatement = "BEGIN UNLOGGED BATCH " +
                                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (" + (100 + i) + ", '1', 'b1'); " +
                                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (" + (200 + i) + ", '2', 'b2'); " +
                                            "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (" + (300 + i) + ", '3', 'static'); " +
                                            "INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (" + (400 + i) + ", '4'); " +
                                            "INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (" + (500 + i) + ", '5'); " +
                                            "APPLY BATCH;";
                    expectedStatements.add(batchStatement);
                    session.execute(batchStatement);
                }

                return expectedAttemptsAsUser(expectedStatements, username);
            }
        }
    }

    public class PreparedBatchStatementClient implements Callable<List<String>>
    {
        private final String username;

        public PreparedBatchStatementClient(String username)
        {
            this.username = username;
        }

        @Override
        public List<String> call()
        {
            try (CqlSession session = cdt.createSession(username, "secret"))
            {
                String batch = "BEGIN UNLOGGED BATCH USING TIMESTAMP ? " +
                               "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, ?); " +
                               "INSERT INTO ecks_itvta.ectbl (partk, clustk, value) VALUES (?, ?, 'static'); " +
                               "INSERT INTO ecks_itvta.ectbl (partk, clustk) VALUES (?, ?); " +
                               "APPLY BATCH;";

                PreparedStatement preparedBatchStatement = session.prepare(batch);

                List<String> expectedStatements = new ArrayList<>();

                for (int i = 0; i < 10; i++)
                {
                    expectedStatements.add(batch + String.format("[1234, %d, '1', 'b1', %d, '3', %d, '5']", 100 + i, 200 + i, 300 + i));
                    session.execute(preparedBatchStatement.bind(1234L, 100 + i, "1", "b1", 200 + i, "3", 300 + i, "5"));
                }

                return expectedAttemptsAsUser(expectedStatements, username);
            }
        }
    }

    private List<String> expectedAttemptsAsUser(List<String> statements, String user)
    {
        return statements
                .stream()
                .map(s -> s.replaceAll("secret", "*****"))
                .map(s -> String.format("client:'127.0.0.1'|user:'%s'|status:'ATTEMPT'|operation:'%s'", user, s))
                .collect(Collectors.toList());
    }

    private List<String> expectedBatchAttemptFragmentsAsUser(List<String> statements, String user)
    {
        List<String> result = new ArrayList<>();
        for (String statement : statements)
        {
            result.add(String.format("client:'127.0.0.1'|user:'%s'|batchId:'", user));
            result.add(String.format("'|status:'ATTEMPT'|operation:'%s'", statement));
        }
        return result;
    }
}
