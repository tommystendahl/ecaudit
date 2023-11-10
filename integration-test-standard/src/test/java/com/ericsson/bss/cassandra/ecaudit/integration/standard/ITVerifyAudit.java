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

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.ericsson.bss.cassandra.ecaudit.logger.Slf4jAuditLogger;
import com.ericsson.bss.cassandra.ecaudit.test.daemon.CassandraDaemonForAuditTest;

import net.jcip.annotations.NotThreadSafe;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * This test class provides a functional integration test with Cassandra itself.
 *
 * This is achieved by starting an embedded Cassandra server where the audit plug-in is used. Then each test case send
 * different requests and capture and verify that expected audit entries are produced.
 *
 * This class also works as a safe guard to changes on the public API of the plug-in. The plug-in has three different
 * interfaces that together make out its public API. It is Cassandra itself, the configuration, and the audit messages
 * sent to the supported log back ends. When a change is necessary here it indicates that the major or minor version
 * should be bumped as well. This class is mostly focused to verify that a correct audit logs are created based on a
 * specific configuration.
 */
@NotThreadSafe
@RunWith(MockitoJUnitRunner.class)
public class ITVerifyAudit
{
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static CassandraDaemonForAuditTest cdt;
    private static CqlSession session;

    @Mock
    private Appender<ILoggingEvent> mockAuditAppender;

    @BeforeClass
    public static void beforeClass() throws Exception
    {
        cdt = CassandraDaemonForAuditTest.getInstance();

        session = cdt.createSession();

        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system' }");
        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_schema' }");
        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_virtual_schema' }");

        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system' }");
        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_schema' }");
        session.execute("ALTER ROLE cassandra WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_virtual_schema' }");

        session.execute("CREATE KEYSPACE ecks_itva WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");
        session.execute("CREATE TABLE ecks_itva.ectbl (partk int PRIMARY KEY, clustk text, value text)");
        session.execute("CREATE TABLE ecks_itva.ectypetbl (partk int PRIMARY KEY, v0 text, v1 ascii, v2 bigint, v3 blob, v4 boolean, "
        + "v5 date, v6 decimal, v7 double, v8 float, v9 inet, v10 int, v11 smallint, v12 time, v13 timestamp, "
        + "v14 uuid, v15 varchar, v16 varint)");

        session.execute("CREATE ROLE ecuser WITH PASSWORD = 'secret' AND LOGIN = true");
        session.execute("GRANT CREATE ON ALL ROLES TO ecuser");

        session.execute("CREATE ROLE sam WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_schema'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/system_virtual_schema'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/ecks_itva/ectbl'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/nonexistingks'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data/ecks_itva/nonexistingtbl'}");
        session.execute("ALTER ROLE sam WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'connections'}");
        session.execute("GRANT MODIFY ON ecks_itva.ectbl TO sam");
        session.execute("GRANT SELECT ON ecks_itva.ectbl TO sam");

        session.execute("CREATE ROLE foo WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true");
        session.execute("ALTER ROLE foo WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data'}");
        session.execute("ALTER ROLE foo WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'roles'}");
        session.execute("ALTER ROLE foo WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'connections'}");

        session.execute("CREATE ROLE bar WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true");
        session.execute("CREATE ROLE mute WITH LOGIN = false");
        session.execute("ALTER ROLE mute WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data'}");
        session.execute("ALTER ROLE mute WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'roles'}");
        session.execute("ALTER ROLE mute WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'connections'}");
        session.execute("GRANT mute TO bar");

        session.execute("CREATE ROLE yser2 WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true");

        session.execute("CREATE KEYSPACE ecks2 WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");

        session.execute("CREATE KEYSPACE ecks3 WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");
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
        if(session != null)
        {
            session.execute("DROP KEYSPACE IF EXISTS ecks_itva");
            session.execute("DROP ROLE IF EXISTS ecuser");
            session.execute("DROP ROLE IF EXISTS foo");
            session.execute("DROP ROLE IF EXISTS bar");
            session.execute("DROP ROLE IF EXISTS mute");

            session.close();
            session.close();
        }
    }

    @Test
    public void testAuthenticateUserSuccessIsLogged()
    {
        try (CqlSession privateSession = cdt.createSession("ecuser", "secret"))
        {
            assertThat(privateSession.isClosed()).isFalse();
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .contains(
                                "client:'127.0.0.1'|user:'ecuser'|status:'ATTEMPT'|operation:'Authentication attempt'");
    }

    @Test
    public void testAuthenticateWhitelistedUserSuccessIsNotLogged()
    {
        List<String> unloggedUsers = Arrays.asList("foo", "bar");

        for (String user : unloggedUsers)
        {
            try (CqlSession privateSession = cdt.createSession(user, "secret"))
            {
                assertThat(privateSession.isClosed()).isFalse();
            }
        }
    }

    @Test(expected = AllNodesFailedException.class)
    public void testAuthenticateUserRejectIsLogged()
    {
        try (CqlSession privateSession = cdt.createSession("unknown", "secret"))
        {
            assertThat(privateSession.isClosed()).isFalse();
        }
        finally
        {

            ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
            verify(mockAuditAppender, atLeast(2)).doAppend(loggingEventCaptor.capture());
            List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();
            assertThat(loggingEvents
                    .stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList()))
                            .contains(
                                    "client:'127.0.0.1'|user:'unknown'|status:'ATTEMPT'|operation:'Authentication attempt'",
                                    "client:'127.0.0.1'|user:'unknown'|status:'FAILED'|operation:'Authentication failed'");
        }
    }

    @Test
    public void testValidSimpleStatementsAreLogged()
    {
        List<String> statements = Arrays.asList(
                "CREATE ROLE validuser WITH PASSWORD = 'secret' AND LOGIN = true",
                "CREATE KEYSPACE validks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false",
                "CREATE TYPE validks.validtype (birthday timestamp, nationality text)",
                "CREATE TABLE validks.validtbl (partk int PRIMARY KEY, clustk text, value text, type frozen <validtype>)",
                "CREATE INDEX valididx ON validks.validtbl (value)",
                "ALTER ROLE validuser WITH PASSWORD = 'secret' AND LOGIN = false",
                "GRANT SELECT ON KEYSPACE validks TO validuser",
                "INSERT INTO validks.validtbl (partk, clustk, value, type) VALUES (1, 'one', 'valid', { birthday : '1976-02-25', nationality : 'Swedish' })",
                "SELECT * FROM validks.validtbl",
                "DELETE FROM validks.validtbl WHERE partk = 2",
                "LIST ROLES OF validuser",
                "LIST ALL PERMISSIONS",
                "LIST ALL PERMISSIONS OF validuser",
                "LIST SELECT OF validuser",
                "LIST SELECT ON KEYSPACE validks",
                "LIST SELECT ON KEYSPACE validks OF validuser",
                "REVOKE SELECT ON KEYSPACE validks FROM validuser",
                "TRUNCATE TABLE validks.validtbl",
                "DROP INDEX validks.valididx",
                "DROP KEYSPACE IF EXISTS validks",
                "DROP ROLE IF EXISTS validuser");

        for (String statement : statements)
        {
            session.execute(statement);
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(statements.size())).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(statements));
    }

    @Test
    public void testValidPreparedStatementsAreLogged()
    {
        PreparedStatement preparedInsertStatement = session
                .prepare("INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)");
        PreparedStatement preparedSelectStatement = session
                .prepare("SELECT * FROM ecks_itva.ectbl WHERE partk = ?");
        PreparedStatement preparedDeleteStatement = session.prepare("DELETE FROM ecks_itva.ectbl WHERE partk = ?");

        List<String> expectedStatements = Arrays.asList(
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)[1, '1', 'valid']",
                "SELECT * FROM ecks_itva.ectbl WHERE partk = ?[1]",
                "DELETE FROM ecks_itva.ectbl WHERE partk = ?[1]",
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)[2, '2', 'valid']",
                "SELECT * FROM ecks_itva.ectbl WHERE partk = ?[2]",
                "DELETE FROM ecks_itva.ectbl WHERE partk = ?[2]");

        for (int i = 1; i <= 2; i++)
        {
            session.execute(preparedInsertStatement.bind(i, Integer.toString(i), "valid"));
            session.execute(preparedSelectStatement.bind(i));
            session.execute(preparedDeleteStatement.bind(i));
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(expectedStatements.size())).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(expectedStatements));
    }

    @Test

    public void testValidBatchStatementsAreLogged()
    {
        PreparedStatement preparedInsertStatement1 = session
                .prepare("INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)");
        PreparedStatement preparedInsertStatement2 = session
                .prepare("INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, 'valid')");

        List<String> expectedStatements = Arrays.asList(
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)[1, '1', 'valid']",
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?)[2, '2', 'valid']",
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, 'valid')[3, '3']",
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (4, '4', 'valid')",
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, 'valid')[5, '5']");

        BatchStatement batch = BatchStatement.builder(DefaultBatchType.UNLOGGED)
                .addStatement(preparedInsertStatement1.bind(1, "1", "valid"))
                .addStatement(preparedInsertStatement1.bind(2, "2", "valid"))
                .addStatement(preparedInsertStatement2.bind(3, "3"))
                .addStatement(SimpleStatement.newInstance("INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (4, '4', 'valid')"))
                .addStatement(preparedInsertStatement2.bind(5, "5"))
                .build();
        session.execute(batch);

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(expectedStatements.size())).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .flatExtracting(e -> Arrays.asList(e.split(UUID_REGEX)))
                        .containsAll(expectedBatchAttemptSegments(expectedStatements));
    }

    @Test
    public void testValidNonPreparedBatchStatementsAreLogged()
    {
        List<String> allStatements = Arrays.asList(
        "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (1, '1', 'valid')",
        "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (2, '2', 'valid')",
        "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (3, '3', 'valid')",
        "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (4, '4', 'valid')",
        "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (5, '5', 'valid')");

        StringBuilder batchStatementBuilder = new StringBuilder("BEGIN UNLOGGED BATCH ");
        for (String statement : allStatements)
        {
            batchStatementBuilder.append(statement).append("; ");
        }
        batchStatementBuilder.append("APPLY BATCH;");

        List<String> expectedStatements = Collections.singletonList(batchStatementBuilder.toString());

        session.execute(batchStatementBuilder.toString());

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeastOnce()).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                   .stream()
                   .map(ILoggingEvent::getFormattedMessage)
                   .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(expectedStatements));
    }

    @Test
    public void testValidPreparedBatchStatementsAreLogged()
    {
        String statement = "BEGIN UNLOGGED BATCH USING TIMESTAMP ? " +
                           "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, ?); " +
                           "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (?, ?, 'valid'); " +
                           "APPLY BATCH;";

        List<String> expectedStatements = Collections.singletonList(statement + "[1234, 1, '1', 'valid', 3, '3']");

        PreparedStatement preparedBatchStatement = session.prepare(statement);
        session.execute(preparedBatchStatement.bind(1234L, 1, "1", "valid", 3, "3"));

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeastOnce()).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                   .stream()
                   .map(ILoggingEvent::getFormattedMessage)
                   .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(expectedStatements));
    }

    @Test
    public void testValidPreparedStatementTypesAreLogged() throws Exception
    {
        PreparedStatement preparedStatement = session
                .prepare("INSERT INTO ecks_itva.ectypetbl "
                        + "(partk, v0, v1, v2, v4, v5, v9, v13, v15)"
                        + " VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?)");

        String expectedStatement = "INSERT INTO ecks_itva.ectypetbl "
                + "(partk, v0, v1, v2, v4, v5, v9, v13, v15)"
                + " VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?)[1, 'text', 'ascii', 123123123123123123, true, 1976-02-25, 8.8.8.8, 2004-05-29T14:29:00.000Z, 'varchar']";
        // TODO: Are these bugs in Cassandra?
        // Was expecting "v5 date" to get quotes
        // Was expecting "v9 inet" to get quotes
        // Was expecting "v13 timestamp" to get quotes

        session.execute(preparedStatement.bind(1, "text", "ascii", 123123123123123123L,
                                               Boolean.TRUE, LocalDate.of(1976, 2, 25), InetAddress.getByName("8.8.8.8"),
                                               Instant.parse("2004-05-29T14:29:00.000Z"), "varchar"));

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(Arrays.asList(expectedStatement)));
    }

    @Test
    public void testValidSimpleStatementTypesAreLogged()
    {
        String statement = "INSERT INTO ecks_itva.ectypetbl "
                + "(partk, v0, v1, v2, v4, v5, v9, v13, v15)"
                + " VALUES "
                + "(1, 'text', 'ascii', 123123123123123123, true, '1976-02-25', '8.8.8.8', '2004-05-29T14:29:00.000Z', 'varchar')";

        session.execute(statement);

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .containsAll(expectedAttempts(Arrays.asList(statement)));
    }

    @Test
    public void testWhitelistedUserValidStatementsAreNotLogged()
    {
        List<String> statements = Arrays.asList(
                "CREATE ROLE validuser WITH PASSWORD = 'secret' AND LOGIN = true",
                "CREATE KEYSPACE validks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false",
                "CREATE TABLE validks.validtbl (partk int PRIMARY KEY, clustk text, value text)",
                "INSERT INTO validks.validtbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "SELECT * FROM validks.validtbl",
                "DELETE FROM validks.validtbl WHERE partk = 2",
                "DROP KEYSPACE IF EXISTS validks",
                "DROP ROLE IF EXISTS validuser");

        List<String> unloggedUsers = Arrays.asList("foo", "bar");

        for (String user : unloggedUsers)
        {
            try (CqlSession privateSession = cdt.createSession(user, "secret"))
            {
                for (String statement : statements)
                {
                    privateSession.execute(statement);
                }
            }
        }
    }

    @Test
    public void testWhitelistedUserValidStatementsWithUseAreNotLogged()
    {
        // Driver or Cassandra will add double-quotes to ks on one of the connections if statemens doesn't have it here.
        // TODO: Research if this is "bug" in Cassandra, driver or ecAudit?
        List<String> statements = Arrays.asList(
                "INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "SELECT * FROM ecks_itva.ectbl",
                "USE \"ecks_itva\"",
                "INSERT INTO ectbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "SELECT * FROM ectbl");

        String user = "sam";
        try (CqlSession privateSession = cdt.createSession(user, "secret"))
        {
            for (String statement : statements)
            {
                privateSession.execute(statement);
            }
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        // Will typically see 2 USE statements, assuming this is one for ordinary connection and one for control connection
        // TODO: Research if assumption above is correct
        verify(mockAuditAppender, atLeast(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                .containsOnlyElementsOf(expectedAttemptsAsUser(Arrays.asList("USE \"ecks_itva\""), user));
    }

    /**
     * Each USE statement will typically result in two log entries. Further, the second USE log entry
     * will only appear just before the next statement is issued from the client. Though this is a bit
     * unexpected it still seem as if the order is preserved which is verified by this test case.
     *
     * TODO: Research why we get this behavior.
     */
    @Test
    public void testMultipleUseStatementsPreserveOrder()
    {
        String user = "sam";
        try (CqlSession privateSession = cdt.createSession(user, "secret"))
        {
            executeOneUseWithFollowingSelect(user, privateSession, "USE \"ecks_itva\"");
            executeOneUseWithFollowingSelect(user, privateSession, "USE \"ecks2\"");
            executeOneUseWithFollowingSelect(user, privateSession, "USE \"ecks3\"");
        }
    }

    private void executeOneUseWithFollowingSelect(String user, CqlSession privateSession, String useStatement) {
        ArgumentCaptor<ILoggingEvent> loggingEventCaptor1 = ArgumentCaptor.forClass(ILoggingEvent.class);
        privateSession.execute(useStatement);
        privateSession.execute("SELECT * FROM ecks_itva.ectypetbl");
        verify(mockAuditAppender, atLeast(2)).doAppend(loggingEventCaptor1.capture());
        List<ILoggingEvent> loggingEvents1 = loggingEventCaptor1.getAllValues();
        assertThat(loggingEvents1
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                .containsOnlyElementsOf(expectedAttemptsAsUser(Arrays.asList(useStatement, "SELECT * FROM ecks_itva.ectypetbl"), user));
        reset(mockAuditAppender);
    }

    @Test
    public void testFailedStatementsAreLogged()
    {
        List<String> statements = Arrays.asList(
                "CREATE ROLE ecuser WITH PASSWORD = 'secret' AND LOGIN = true",
                "CREATE KEYSPACE ecks_itva WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false",
                "CREATE TABLE ecks_itva.ectbl (partk int PRIMARY KEY, clustk text, value text)",
                "INSERT INTO invalidks.invalidtbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "SELECT * FROM invalidks.invalidtbl",
                "SELECT * FROM ecks_itva.invalidtbl",
                "DELETE FROM invalidks.invalidtbl WHERE partk = 2",
                "DROP KEYSPACE invalidks",
                "DROP ROLE invaliduser",
                "CREATE ROLE invaliduser \nWITH PASSWORD = 'secret' _unknown_");

        for (String statement : statements)
        {
            assertThatExceptionOfType(DriverException.class).isThrownBy(() -> session.execute(statement));
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, atLeast(statements.size() * 2)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .containsAll(expectedAttemptsAndFails(statements));
    }

    @Test
    public void testFailedWhitelistedStatementsAreNotLogged()
    {
        List<String> statements = Arrays.asList(
                "SELECT * FROM nonexistingks.nonexistingtbl",
                "SELECT * FROM ecks_itva.nonexistingtbl",
                "INSERT INTO nonexistingks.nonexistingtbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "INSERT INTO ecks_itva.nonexistingtbl (partk, clustk, value) VALUES (1, 'one', 'valid')");

        try (CqlSession privateSession = cdt.createSession("sam", "secret"))
        {
            for (String statement : statements)
            {
                assertThatExceptionOfType(InvalidQueryException.class).isThrownBy(() -> privateSession.execute(statement));
            }
        }
    }

    @Test
    public void testFailedWhitelistedBatchStatementIsNotLogged()
    {
            List<String> statements = Arrays.asList(
                "INSERT INTO nonexistingks.nonexistingtbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "INSERT INTO validks.nonexistingtbl (partk, clustk, value) VALUES (1, 'one', 'valid')");

        try (CqlSession privateSession = cdt.createSession("sam", "secret"))
        {
            for (String statement : statements)
            {
                BatchStatement batch = BatchStatement.builder(DefaultBatchType.UNLOGGED)
                        .addStatement(SimpleStatement.newInstance("INSERT INTO ecks_itva.ectbl (partk, clustk, value) VALUES (4, '4', 'valid')"))
                        .addStatement(SimpleStatement.newInstance(statement))
                        .build();
                assertThatExceptionOfType(InvalidQueryException.class).isThrownBy(() -> privateSession.execute(batch));
            }
        }
    }

    @Test
    public void testYamlWhitelistedUserShowConnectionAttemptsButValidStatementsAreNotLogged()
    {
        List<String> statements = Arrays.asList(
                "CREATE ROLE validuser WITH PASSWORD = 'secret' AND LOGIN = true",
                "CREATE KEYSPACE validks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false",
                "CREATE TABLE validks.validtbl (partk int PRIMARY KEY, clustk text, value text)",
                "INSERT INTO validks.validtbl (partk, clustk, value) VALUES (1, 'one', 'valid')",
                "SELECT * FROM validks.validtbl",
                "DELETE FROM validks.validtbl WHERE partk = 2",
                "DROP KEYSPACE IF EXISTS validks",
                "DROP ROLE IF EXISTS validuser");

        List<String> unloggedUsers = Arrays.asList("yser2");

        for (String user : unloggedUsers)
        {
            try (CqlSession privateSession = cdt.createSession(user, "secret"))
            {
                for (String statement : statements)
                {
                    privateSession.execute(statement);
                }
            }
        }

        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, times(2)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents
                .stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList()))
                        .contains(
                                "client:'127.0.0.1'|user:'yser2'|status:'ATTEMPT'|operation:'Authentication attempt'");
    }

    private List<String> expectedAttempts(List<String> statements)
    {
        return statements
                .stream()
                .map(s -> s.replaceAll("secret", "*****"))
                .map(s -> String.format("client:'127.0.0.1'|user:'cassandra'|status:'ATTEMPT'|operation:'%s'", s))
                .collect(Collectors.toList());
    }

    private List<String> expectedAttemptsAsUser(List<String> statements, String user)
    {
        return statements
                .stream()
                .map(s -> s.replaceAll("secret", "*****"))
                .map(s -> String.format("client:'127.0.0.1'|user:'%s'|status:'ATTEMPT'|operation:'%s'", user, s))
                .collect(Collectors.toList());
    }

    private List<String> expectedBatchAttemptSegments(List<String> statements)
    {
        List<String> result = new ArrayList<>();
        for (String statement : statements)
        {
            result.add("client:'127.0.0.1'|user:'cassandra'|batchId:'");
            result.add(String.format("'|status:'ATTEMPT'|operation:'%s'", statement));
        }
        return result;
    }

    private List<String> expectedAttemptsAndFails(List<String> statements)
    {
        List<String> obfuscatedStatements = statements
                .stream()
                .map(s -> s.replaceAll("secret", "*****"))
                .collect(Collectors.toList());

        List<String> expectedLogPairs = new ArrayList<>();
        for (String statement : obfuscatedStatements)
        {
            expectedLogPairs.add(
                    String.format("client:'127.0.0.1'|user:'cassandra'|status:'ATTEMPT'|operation:'%s'", statement));
            expectedLogPairs.add(
                    String.format("client:'127.0.0.1'|user:'cassandra'|status:'FAILED'|operation:'%s'", statement));
        }

        return expectedLogPairs;
    }
}
