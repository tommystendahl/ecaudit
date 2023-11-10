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
package com.ericsson.bss.cassandra.ecaudit.entry.factory;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ericsson.bss.cassandra.ecaudit.auth.ConnectionResource;
import com.ericsson.bss.cassandra.ecaudit.entry.AuditEntry;
import com.ericsson.bss.cassandra.ecaudit.entry.AuditEntry.Builder;
import com.ericsson.bss.cassandra.ecaudit.facade.CassandraAuditException;

import org.apache.cassandra.auth.DataResource;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.CQLStatement.Raw;
import org.apache.cassandra.cql3.QualifiedName;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UTName;
import org.apache.cassandra.cql3.statements.AlterRoleStatement;
import org.apache.cassandra.cql3.statements.AuthenticationStatement;
import org.apache.cassandra.cql3.statements.AuthorizationStatement;
import org.apache.cassandra.cql3.statements.BatchStatement;
import org.apache.cassandra.cql3.statements.CreateRoleStatement;
import org.apache.cassandra.cql3.statements.DescribeStatement;
import org.apache.cassandra.cql3.statements.DropRoleStatement;
import org.apache.cassandra.cql3.statements.ListPermissionsStatement;
import org.apache.cassandra.cql3.statements.ListRolesStatement;
import org.apache.cassandra.cql3.statements.ModificationStatement;
import org.apache.cassandra.cql3.statements.PermissionsManagementStatement;
import org.apache.cassandra.cql3.statements.QualifiedStatement;
import org.apache.cassandra.cql3.statements.RoleManagementStatement;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.cql3.statements.TruncateStatement;
import org.apache.cassandra.cql3.statements.UseStatement;
import org.apache.cassandra.cql3.statements.schema.AlterKeyspaceStatement;
import org.apache.cassandra.cql3.statements.schema.AlterTableStatement;
import org.apache.cassandra.cql3.statements.schema.AlterTypeStatement;
import org.apache.cassandra.cql3.statements.schema.AlterViewStatement;
import org.apache.cassandra.cql3.statements.schema.CreateAggregateStatement;
import org.apache.cassandra.cql3.statements.schema.CreateFunctionStatement;
import org.apache.cassandra.cql3.statements.schema.CreateIndexStatement;
import org.apache.cassandra.cql3.statements.schema.CreateKeyspaceStatement;
import org.apache.cassandra.cql3.statements.schema.CreateTableStatement;
import org.apache.cassandra.cql3.statements.schema.CreateTriggerStatement;
import org.apache.cassandra.cql3.statements.schema.CreateTypeStatement;
import org.apache.cassandra.cql3.statements.schema.CreateViewStatement;
import org.apache.cassandra.cql3.statements.schema.DropAggregateStatement;
import org.apache.cassandra.cql3.statements.schema.DropFunctionStatement;
import org.apache.cassandra.cql3.statements.schema.DropIndexStatement;
import org.apache.cassandra.cql3.statements.schema.DropKeyspaceStatement;
import org.apache.cassandra.cql3.statements.schema.DropTableStatement;
import org.apache.cassandra.cql3.statements.schema.DropTriggerStatement;
import org.apache.cassandra.cql3.statements.schema.DropTypeStatement;
import org.apache.cassandra.cql3.statements.schema.DropViewStatement;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.service.ClientState;

@SuppressWarnings("PMD")
public class AuditEntryBuilderFactory
{
    private static final String KEYSPACE_NAME = "keyspaceName";
    private static final String NAME = "name";
    private static final String TABLE_NAME = "tableName";

    private static final Logger LOG = LoggerFactory.getLogger(AuditEntryBuilderFactory.class);

    // This list of predefined and immutable permission sets are injected to the audit entries
    // created in this factory. They should stay immutable since the {@link AuditEntry} type and its builder
    // doesn't make copies of the sets.
    private static final Set<Permission> ALL_PERMISSIONS = Permission.ALL;
    private static final Set<Permission> USE_PERMISSIONS = ImmutableSet.of(Permission.CREATE,
                                                                           Permission.ALTER,
                                                                           Permission.DROP,
                                                                           Permission.SELECT,
                                                                           Permission.MODIFY,
                                                                           Permission.AUTHORIZE);
    private static final Set<Permission> SELECT_PERMISSIONS = ImmutableSet.of(Permission.SELECT);
    private static final Set<Permission> MODIFY_PERMISSIONS = ImmutableSet.of(Permission.MODIFY);
    private static final Set<Permission> CAS_PERMISSIONS = ImmutableSet.of(Permission.SELECT,
                                                                           Permission.MODIFY);
    private static final Set<Permission> EXECUTE_PERMISSIONS = ImmutableSet.of(Permission.EXECUTE);
    private static final Set<Permission> CREATE_PERMISSIONS = ImmutableSet.of(Permission.CREATE);
    private static final Set<Permission> ALTER_PERMISSIONS = ImmutableSet.of(Permission.ALTER);
    private static final Set<Permission> DROP_PERMISSIONS = ImmutableSet.of(Permission.DROP);
    private static final Set<Permission> DESCRIBE_PERMISSIONS = ImmutableSet.of(Permission.DESCRIBE);
    private static final Set<Permission> AUTHORIZE_PERMISSIONS = ImmutableSet.of(Permission.AUTHORIZE);

    private final StatementResourceAdapter statementResourceAdapter = new StatementResourceAdapter();

    public Builder createAuthenticationEntryBuilder()
    {
        return AuditEntry.newBuilder()
                         .permissions(EXECUTE_PERMISSIONS)
                         .resource(ConnectionResource.root());
    }

    public Builder createEntryBuilder(String operation, ClientState state)
    {
        try
        {
            return createEntryBuilderForUnpreparedStatement(operation, state);
        }
        catch (RuntimeException e)
        {
            LOG.debug("Failed to parse or prepare statement - assuming default permissions and resources", e);
            return createDefaultEntryBuilder().knownOperation(false);
        }
    }

    private Builder createEntryBuilderForUnpreparedStatement(String operation, ClientState state)
    {
        try
        {
            CQLStatement statement = QueryProcessor.getStatement(operation, state);
            return createEntryBuilder(statement);
        }
        catch (InvalidRequestException e)
        {
            LOG.trace("Failed to prepare statement - trying direct parsing", e);
            Raw parsedStatement = getParsedStatement(operation, state);
            return createEntryBuilder(parsedStatement);
        }
    }

    private Raw getParsedStatement(String operation, ClientState state)
    {
        Raw parsedStatement = QueryProcessor.parseStatement(operation);

        // Set keyspace for statement that require login
        if (parsedStatement instanceof QualifiedStatement)
        {
            ((QualifiedStatement) parsedStatement).setKeyspace(state);
        }
        return parsedStatement;
    }

    /**
     * Delegate builder creation for {@link ParsedStatement}s and {@link CFStatement}s.
     *
     * @param parsedStatement the {@link ParsedStatement} or {@link CFStatement}
     * @return the initialized builder with operation and resource assigned
     */
    @SuppressWarnings("PMD")
    private Builder createEntryBuilder(Raw parsedStatement)
    {
        if (parsedStatement instanceof SelectStatement.RawStatement)
        {
            return createSelectEntryBuilder((SelectStatement.RawStatement) parsedStatement);
        }
        if (parsedStatement instanceof ModificationStatement.Parsed)
        {
            return createModificationEntryBuilder((ModificationStatement.Parsed) parsedStatement);
        }
        if (parsedStatement instanceof TruncateStatement)
        {
            return createTruncateEntryBuilder((TruncateStatement) parsedStatement);
        }
        if (parsedStatement instanceof UseStatement)
        {
            return createUseEntryBuilder((UseStatement) parsedStatement);
        }
        if (isAlterSchemaStatement(parsedStatement))
        {
            return createSchemaAlteringEntryBuilder(parsedStatement);
        }
        if (parsedStatement instanceof AuthenticationStatement)
        {
            return createAuthenticationEntryBuilder((AuthenticationStatement) parsedStatement);
        }
        if (parsedStatement instanceof AuthorizationStatement)
        {
            return createAuthorizationEntryBuilder((AuthorizationStatement) parsedStatement);
        }
        if (parsedStatement instanceof BatchStatement.Parsed)
        {
            return createBatchEntryBuilder();
        }
        if (parsedStatement instanceof DescribeStatement)
        {
            return createDescribeEntryBuilder((DescribeStatement) parsedStatement);
        }

        LOG.warn("Detected unrecognized CQLStatement in audit mapping");
        return createDefaultEntryBuilder().knownOperation(false);
    }

    @SuppressWarnings("PMD")
    public Builder createEntryBuilder(CQLStatement statement)
    {
        if (statement instanceof SelectStatement)
        {
            return createSelectEntryBuilder((SelectStatement) statement);
        }
        if (statement instanceof ModificationStatement)
        {
            return createModificationEntryBuilder((ModificationStatement) statement);
        }
        if (statement instanceof TruncateStatement)
        {
            return createTruncateEntryBuilder((TruncateStatement) statement);
        }
        if (statement instanceof UseStatement)
        {
            return createUseEntryBuilder((UseStatement) statement);
        }
        if (isAlterSchemaStatement(statement))
        {
            return createSchemaAlteringEntryBuilder(statement);
        }
        if (statement instanceof AuthenticationStatement)
        {
            return createAuthenticationEntryBuilder((AuthenticationStatement) statement);
        }
        if (statement instanceof AuthorizationStatement)
        {
            return createAuthorizationEntryBuilder((AuthorizationStatement) statement);
        }
        if (statement instanceof BatchStatement)
        {
            return createBatchEntryBuilder();
        }
        if (statement instanceof DescribeStatement)
        {
            return createDescribeEntryBuilder((DescribeStatement) statement);
        }

        LOG.warn("Detected unrecognized CQLStatement in audit mapping");
        return createDefaultEntryBuilder().knownOperation(false);
    }

    public Builder createBatchEntryBuilder()
    {
        return AuditEntry.newBuilder()
                         .permissions(CAS_PERMISSIONS)
                         .resource(DataResource.root());
    }

    public Builder updateBatchEntryBuilder(Builder builder, String operation, ClientState state)
    {
        CQLStatement statement;
        try
        {
            statement = QueryProcessor.getStatement(operation, state);
        }
        catch (RuntimeException e)
        {
            // TODO: We should be able to fix this
            // This would be the result of a query towards a non-existing resource in a batch statement
            // But right now we don't get here since batch statements fail pre-processing
            LOG.error("Failed to parse and prepare BatchStatement when mapping single query for audit", e);
            throw new CassandraAuditException("Failed to parse and prepare BatchStatement when mapping single query for audit", e);
        }

        return updateBatchEntryBuilder(builder, (ModificationStatement) statement);
    }

    public Builder updateBatchEntryBuilder(Builder builder, ModificationStatement statement)
    {
        return builder
               .permissions(statement.hasConditions() ? CAS_PERMISSIONS : MODIFY_PERMISSIONS)
               .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    private Builder createSelectEntryBuilder(SelectStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(SELECT_PERMISSIONS)
                         .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    private Builder createSelectEntryBuilder(SelectStatement.RawStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(SELECT_PERMISSIONS)
                         .resource(DataResource.table(statement.keyspace(), statement.name()));
    }

    private Builder createModificationEntryBuilder(ModificationStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(statement.hasConditions() ? CAS_PERMISSIONS : MODIFY_PERMISSIONS)
                         .resource(DataResource.table(statement.keyspace(), statement.columnFamily()));
    }

    private Builder createModificationEntryBuilder(ModificationStatement.Parsed statement)
    {
        Set<Permission> permissions;
        try
        {
            boolean hasCondition = !((List<?>) FieldUtils.readField(statement, "conditions", true)).isEmpty();
            permissions = hasCondition ? CAS_PERMISSIONS : MODIFY_PERMISSIONS;
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                         .permissions(permissions)
                         .resource(DataResource.table(statement.keyspace(), statement.name()));
    }

    private Builder createTruncateEntryBuilder(TruncateStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(MODIFY_PERMISSIONS)
                         .resource(DataResource.table(statement.keyspace(), statement.name()));
    }

    private Builder createDescribeEntryBuilder(DescribeStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DESCRIBE_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createUseEntryBuilder(UseStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(USE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveKeyspaceResource(statement));
    }

    private Builder createAuthenticationEntryBuilder(AuthenticationStatement statement)
    {
        if (statement instanceof CreateRoleStatement)
        {
            return createCreateRoleEntryBuilder((CreateRoleStatement) statement);
        }
        if (statement instanceof AlterRoleStatement)
        {
            return createAlterRoleEntryBuilder((AlterRoleStatement) statement);
        }
        if (statement instanceof DropRoleStatement)
        {
            return createDropRoleEntryBuilder((DropRoleStatement) statement);
        }
        if (statement instanceof RoleManagementStatement)
        {
            return createRoleManagementEntryBuilder((RoleManagementStatement) statement);
        }

        LOG.warn("Detected unrecognized AuthenticationStatement in audit mapping");
        return createDefaultEntryBuilder();
    }

    private Builder createCreateRoleEntryBuilder(CreateRoleStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveRoleResource(statement));
    }

    private Builder createAlterRoleEntryBuilder(AlterRoleStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveRoleResource(statement));
    }

    private Builder createDropRoleEntryBuilder(DropRoleStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveRoleResource(statement));
    }

    private Builder createRoleManagementEntryBuilder(RoleManagementStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(AUTHORIZE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveRoleResource(statement));
    }

    private Builder createAuthorizationEntryBuilder(AuthorizationStatement statement)
    {
        if (statement instanceof ListRolesStatement)
        {
            return createListRolesEntryBuilder((ListRolesStatement) statement);
        }
        if (statement instanceof ListPermissionsStatement)
        {
            return createListPermissionsEntryBuilder((ListPermissionsStatement) statement);
        }
        if (statement instanceof PermissionsManagementStatement)
        {
            return createPermissionsManagementEntryBuilder((PermissionsManagementStatement) statement);
        }

        LOG.warn("Detected unrecognized AuthorizationStatement in audit mapping");
        return createDefaultEntryBuilder();
    }

    private Builder createListRolesEntryBuilder(ListRolesStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DESCRIBE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveGranteeResource(statement));
    }

    private Builder createListPermissionsEntryBuilder(ListPermissionsStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(AUTHORIZE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveGranteeResource(statement));
    }

    private Builder createPermissionsManagementEntryBuilder(PermissionsManagementStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(AUTHORIZE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveManagedResource(statement));
    }

    private boolean isAlterSchemaStatement(CQLStatement statement)
    {
        return statement instanceof CreateKeyspaceStatement
                || statement instanceof AlterKeyspaceStatement
                || statement instanceof DropKeyspaceStatement
                || statement instanceof CreateTableStatement
                || statement instanceof AlterTableStatement
                || statement instanceof DropTableStatement
                || statement instanceof CreateViewStatement
                || statement instanceof AlterViewStatement
                || statement instanceof DropViewStatement
                || statement instanceof CreateTypeStatement
                || statement instanceof AlterTypeStatement
                || statement instanceof DropTypeStatement
                || statement instanceof CreateFunctionStatement
                || statement instanceof DropFunctionStatement
                || statement instanceof CreateAggregateStatement
                || statement instanceof DropAggregateStatement
                || statement instanceof CreateIndexStatement
                || statement instanceof DropIndexStatement
                || statement instanceof CreateTriggerStatement
                || statement instanceof DropTriggerStatement;
    }

    private boolean isAlterSchemaStatement(Raw parsedStatement)
    {
        return parsedStatement instanceof CreateKeyspaceStatement.Raw
                || parsedStatement instanceof AlterKeyspaceStatement.Raw
                || parsedStatement instanceof DropKeyspaceStatement.Raw
                || parsedStatement instanceof CreateTableStatement.Raw
                || parsedStatement instanceof AlterTableStatement.Raw
                || parsedStatement instanceof DropTableStatement.Raw
                || parsedStatement instanceof CreateViewStatement.Raw
                || parsedStatement instanceof AlterViewStatement.Raw
                || parsedStatement instanceof DropViewStatement.Raw
                || parsedStatement instanceof CreateTypeStatement.Raw
                || parsedStatement instanceof AlterTypeStatement.Raw
                || parsedStatement instanceof DropTypeStatement.Raw
                || parsedStatement instanceof CreateFunctionStatement.Raw
                || parsedStatement instanceof DropFunctionStatement.Raw
                || parsedStatement instanceof CreateAggregateStatement.Raw
                || parsedStatement instanceof DropAggregateStatement.Raw
                || parsedStatement instanceof CreateIndexStatement.Raw
                || parsedStatement instanceof DropIndexStatement.Raw
                || parsedStatement instanceof CreateTriggerStatement.Raw
                || parsedStatement instanceof DropTriggerStatement.Raw;
    }

    @SuppressWarnings("PMD")
    private Builder createSchemaAlteringEntryBuilder(CQLStatement statement)
    {
        if (statement instanceof CreateKeyspaceStatement)
        {
            return createCreateKeyspaceEntryBuilder((CreateKeyspaceStatement) statement);
        }
        if (statement instanceof AlterKeyspaceStatement)
        {
            return createAlterKeyspaceEntryBuilder((AlterKeyspaceStatement) statement);
        }
        if (statement instanceof DropKeyspaceStatement)
        {
            return createDropKeyspaceEntryBuilder((DropKeyspaceStatement) statement);
        }

        if (statement instanceof CreateTableStatement)
        {
            return createCreateTableEntryBuilder((CreateTableStatement) statement);
        }
        if (statement instanceof AlterTableStatement)
        {
            return createAlterTableEntryBuilder((AlterTableStatement) statement);
        }
        if (statement instanceof DropTableStatement)
        {
            return createDropTableEntryBuilder((DropTableStatement) statement);
        }

        if (statement instanceof CreateViewStatement)
        {
            return createCreateViewEntryBuilder((CreateViewStatement) statement);
        }
        if (statement instanceof AlterViewStatement)
        {
            return createAlterViewEntryBuilder((AlterViewStatement) statement);
        }
        if (statement instanceof DropViewStatement)
        {
            return createDropViewEntryBuilder((DropViewStatement) statement);
        }

        if (statement instanceof CreateTypeStatement)
        {
            return createCreateTypeEntryBuilder((CreateTypeStatement) statement);
        }
        if (statement instanceof AlterTypeStatement)
        {
            return createAlterTypeEntryBuilder((AlterTypeStatement) statement);
        }
        if (statement instanceof DropTypeStatement)
        {
            return createDropTypeEntryBuilder((DropTypeStatement) statement);
        }

        if (statement instanceof CreateFunctionStatement)
        {
            return createCreateFunctionEntryBuilder((CreateFunctionStatement) statement);
        }
        if (statement instanceof DropFunctionStatement)
        {
            return createDropFunctionEntryBuilder((DropFunctionStatement) statement);
        }

        if (statement instanceof CreateAggregateStatement)
        {
            return createCreateAggregateEntryBuilder((CreateAggregateStatement) statement);
        }
        if (statement instanceof DropAggregateStatement)
        {
            return createDropAggregateEntryBuilder((DropAggregateStatement) statement);
        }

        if (statement instanceof CreateIndexStatement)
        {
            return createCreateIndexEntryBuilder((CreateIndexStatement) statement);
        }
        if (statement instanceof DropIndexStatement)
        {
            return createDropIndexEntryBuilder((DropIndexStatement) statement);
        }

        if (statement instanceof CreateTriggerStatement)
        {
            return createCreateTriggerEntryBuilder((CreateTriggerStatement) statement);
        }
        if (statement instanceof DropTriggerStatement)
        {
            return createDropTriggerEntryBuilder((DropTriggerStatement) statement);
        }

        LOG.warn("Detected unrecognized SchemaAlteringStatement in audit mapping");
        return createDefaultEntryBuilder();
    }

    @SuppressWarnings("PMD")
    private Builder createSchemaAlteringEntryBuilder(Raw parsedStatement)
    {
        if (parsedStatement instanceof CreateKeyspaceStatement.Raw)
        {
            return createCreateKeyspaceEntryBuilder((CreateKeyspaceStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof AlterKeyspaceStatement.Raw)
        {
            return createAlterKeyspaceEntryBuilder((AlterKeyspaceStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropKeyspaceStatement.Raw)
        {
            return createDropKeyspaceEntryBuilder((DropKeyspaceStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateTableStatement.Raw)
        {
            return createCreateTableEntryBuilder((CreateTableStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof AlterTableStatement.Raw)
        {
            return createAlterTableEntryBuilder((AlterTableStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropTableStatement.Raw)
        {
            return createDropTableEntryBuilder((DropTableStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateViewStatement.Raw)
        {
            return createCreateViewEntryBuilder((CreateViewStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof AlterViewStatement.Raw)
        {
            return createAlterViewEntryBuilder((AlterViewStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropViewStatement.Raw)
        {
            return createDropViewEntryBuilder((DropViewStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateTypeStatement.Raw)
        {
            return createCreateTypeEntryBuilder((CreateTypeStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof AlterTypeStatement.Raw)
        {
            return createAlterTypeEntryBuilder((AlterTypeStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropTypeStatement.Raw)
        {
            return createDropTypeEntryBuilder((DropTypeStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateFunctionStatement.Raw)
        {
            return createCreateFunctionEntryBuilder((CreateFunctionStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropFunctionStatement.Raw)
        {
            return createDropFunctionEntryBuilder((DropFunctionStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateAggregateStatement.Raw)
        {
            return createCreateAggregateEntryBuilder((CreateAggregateStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropAggregateStatement.Raw)
        {
            return createDropAggregateEntryBuilder((DropAggregateStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateIndexStatement.Raw)
        {
            return createCreateIndexEntryBuilder((CreateIndexStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropIndexStatement.Raw)
        {
            return createDropIndexEntryBuilder((DropIndexStatement.Raw) parsedStatement);
        }

        if (parsedStatement instanceof CreateTriggerStatement.Raw)
        {
            return createCreateTriggerEntryBuilder((CreateTriggerStatement.Raw) parsedStatement);
        }
        if (parsedStatement instanceof DropTriggerStatement.Raw)
        {
            return createDropTriggerEntryBuilder((DropTriggerStatement.Raw) parsedStatement);
        }

        LOG.warn("Detected unrecognized SchemaAlteringStatement in audit mapping");
        return createDefaultEntryBuilder();
    }

    private Builder createCreateKeyspaceEntryBuilder(CreateKeyspaceStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createCreateKeyspaceEntryBuilder(CreateKeyspaceStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.keyspaceName));
    }

    private Builder createAlterKeyspaceEntryBuilder(AlterKeyspaceStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createAlterKeyspaceEntryBuilder(AlterKeyspaceStatement.Raw statement)
    {
        String keyspaceName;
        try
        {
            keyspaceName = (String) FieldUtils.readField(statement, KEYSPACE_NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.keyspace(keyspaceName));
    }

    private Builder createDropKeyspaceEntryBuilder(DropKeyspaceStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createDropKeyspaceEntryBuilder(DropKeyspaceStatement.Raw statement)
    {
        String keyspaceName;
        try
        {
            keyspaceName = (String) FieldUtils.readField(statement, KEYSPACE_NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.keyspace(keyspaceName));
    }

    private Builder createCreateTableEntryBuilder(CreateTableStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createCreateTableEntryBuilder(CreateTableStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.table(statement.keyspace(), statement.table()));
    }

    private Builder createAlterTableEntryBuilder(AlterTableStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createAlterTableEntryBuilder(AlterTableStatement.Raw statement)
    {
        QualifiedName name;
        try
        {
            name = (QualifiedName) FieldUtils.readField(statement, NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.table(name.getKeyspace(), name.getName()));
    }

    private Builder createDropTableEntryBuilder(DropTableStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createDropTableEntryBuilder(DropTableStatement.Raw statement)
    {
        QualifiedName name;
        try
        {
            name = (QualifiedName) FieldUtils.readField(statement, NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.table(name.getKeyspace(), name.getName()));
    }

    private Builder createCreateViewEntryBuilder(CreateViewStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createCreateViewEntryBuilder(CreateViewStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createAlterViewEntryBuilder(AlterViewStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createAlterViewEntryBuilder(AlterViewStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createDropViewEntryBuilder(DropViewStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createDropViewEntryBuilder(DropViewStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createCreateTypeEntryBuilder(CreateTypeStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createCreateTypeEntryBuilder(CreateTypeStatement.Raw statement)
    {
        UTName name;
        try
        {
            name = (UTName) FieldUtils.readField(statement, NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(DataResource.keyspace(name.getKeyspace()));
    }

    private Builder createAlterTypeEntryBuilder(AlterTypeStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createAlterTypeEntryBuilder(AlterTypeStatement.Raw statement)
    {
        UTName name;
        try
        {
            name = (UTName) FieldUtils.readField(statement, NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(DataResource.keyspace(name.getKeyspace()));
    }

    private Builder createDropTypeEntryBuilder(DropTypeStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.keyspace(statement.getAuditLogContext().keyspace));
    }

    private Builder createDropTypeEntryBuilder(DropTypeStatement.Raw statement)
    {
        UTName name;
        try
        {
            name = (UTName) FieldUtils.readField(statement, NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(DataResource.keyspace(name.getKeyspace()));
    }

    private Builder createCreateFunctionEntryBuilder(CreateFunctionStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveFunctionKeyspaceResource(statement));
    }

    private Builder createCreateFunctionEntryBuilder(CreateFunctionStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveFunctionKeyspaceResource(statement));
    }

    private Builder createDropFunctionEntryBuilder(DropFunctionStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveFunctionResource(statement));
    }

    private Builder createDropFunctionEntryBuilder(DropFunctionStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveFunctionResource(statement));
    }

    private Builder createCreateAggregateEntryBuilder(CreateAggregateStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveAggregateKeyspaceResource(statement));
    }

    private Builder createCreateAggregateEntryBuilder(CreateAggregateStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(CREATE_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveAggregateKeyspaceResource(statement));
    }

    private Builder createDropAggregateEntryBuilder(DropAggregateStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveAggregateResource(statement));
    }

    private Builder createDropAggregateEntryBuilder(DropAggregateStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(DROP_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveAggregateResource(statement));
    }

    private Builder createCreateIndexEntryBuilder(CreateIndexStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createCreateIndexEntryBuilder(CreateIndexStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createDropIndexEntryBuilder(DropIndexStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createDropIndexEntryBuilder(DropIndexStatement.Raw statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(ALTER_PERMISSIONS)
                         .resource(statementResourceAdapter.resolveBaseTableResource(statement));
    }

    private Builder createCreateTriggerEntryBuilder(CreateTriggerStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(USE_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createCreateTriggerEntryBuilder(CreateTriggerStatement.Raw statement)
    {
        QualifiedName name;
        try
        {
            name = (QualifiedName) FieldUtils.readField(statement, TABLE_NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                         .permissions(USE_PERMISSIONS)
                         .resource(DataResource.table(name.getKeyspace(), name.getName()));
    }

    private Builder createDropTriggerEntryBuilder(DropTriggerStatement statement)
    {
        return AuditEntry.newBuilder()
                         .permissions(USE_PERMISSIONS)
                         .resource(DataResource.table(statement.getAuditLogContext().keyspace, statement.getAuditLogContext().scope));
    }

    private Builder createDropTriggerEntryBuilder(DropTriggerStatement.Raw statement)
    {
        QualifiedName name;
        try
        {
            name = (QualifiedName) FieldUtils.readField(statement, TABLE_NAME, true);
        }
        catch (IllegalAccessException e)
        {
            throw new CassandraAuditException("Failed to resolve resource", e);
        }

        return AuditEntry.newBuilder()
                         .permissions(USE_PERMISSIONS)
                         .resource(DataResource.table(name.getKeyspace(), name.getName()));
    }

    private Builder createDefaultEntryBuilder()
    {
        return AuditEntry.newBuilder()
                         .permissions(ALL_PERMISSIONS)
                         .resource(DataResource.root());
    }
}
