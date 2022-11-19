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
package io.prestosql.testing;

import com.google.common.collect.ImmutableSet;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.security.AccessControlConfig;
import io.prestosql.security.AccessControlManager;
import io.prestosql.security.SecurityContext;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;
import io.prestosql.transaction.TransactionManager;

import javax.inject.Inject;
import javax.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.spi.security.AccessDeniedException.denyAddColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyCommentTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateTable;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateView;
import static io.prestosql.spi.security.AccessDeniedException.denyCreateViewWithSelect;
import static io.prestosql.spi.security.AccessDeniedException.denyDeleteTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyDropSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyDropTable;
import static io.prestosql.spi.security.AccessDeniedException.denyDropView;
import static io.prestosql.spi.security.AccessDeniedException.denyExecuteFunction;
import static io.prestosql.spi.security.AccessDeniedException.denyExecuteQuery;
import static io.prestosql.spi.security.AccessDeniedException.denyGrantExecuteFunctionPrivilege;
import static io.prestosql.spi.security.AccessDeniedException.denyImpersonateUser;
import static io.prestosql.spi.security.AccessDeniedException.denyInsertTable;
import static io.prestosql.spi.security.AccessDeniedException.denyKillQuery;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameColumn;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameSchema;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameTable;
import static io.prestosql.spi.security.AccessDeniedException.denyRenameView;
import static io.prestosql.spi.security.AccessDeniedException.denySelectColumns;
import static io.prestosql.spi.security.AccessDeniedException.denySetCatalogSessionProperty;
import static io.prestosql.spi.security.AccessDeniedException.denySetSystemSessionProperty;
import static io.prestosql.spi.security.AccessDeniedException.denySetUser;
import static io.prestosql.spi.security.AccessDeniedException.denyShowColumns;
import static io.prestosql.spi.security.AccessDeniedException.denyShowCreateTable;
import static io.prestosql.spi.security.AccessDeniedException.denyViewQuery;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.ADD_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.COMMENT_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_SCHEMA;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.CREATE_VIEW_WITH_SELECT_COLUMNS;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DELETE_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_SCHEMA;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_VIEW;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.EXECUTE_FUNCTION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.EXECUTE_QUERY;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.GRANT_EXECUTE_FUNCTION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.IMPERSONATE_USER;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.INSERT_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.KILL_QUERY;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_SCHEMA;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.RENAME_VIEW;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SELECT_COLUMN;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SET_SESSION;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SET_USER;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SHOW_COLUMNS;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.SHOW_CREATE_TABLE;
import static io.prestosql.testing.TestingAccessControlManager.TestingPrivilegeType.VIEW_QUERY;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;

public class TestingAccessControlManager
        extends AccessControlManager
{
    private final Set<TestingPrivilege> denyPrivileges = new HashSet<>();
    private final Map<RowFilterKey, List<ViewExpression>> rowFilters = new HashMap<>();
    private final Map<ColumnMaskKey, List<ViewExpression>> columnMasks = new HashMap<>();
    private Predicate<String> deniedCatalogs = s -> true;
    private Predicate<SchemaTableName> deniedTables = s -> true;

    @Inject
    public TestingAccessControlManager(TransactionManager transactionManager)
    {
        super(transactionManager, new AccessControlConfig());
    }

    public void loadSystemAccessControl(String name, Map<String, String> properties)
    {
        setSystemAccessControl(name, properties);
    }

    public static TestingPrivilege privilege(String entityName, TestingPrivilegeType type)
    {
        return new TestingPrivilege(Optional.empty(), entityName, type);
    }

    public static TestingPrivilege privilege(String userName, String entityName, TestingPrivilegeType type)
    {
        return new TestingPrivilege(Optional.of(userName), entityName, type);
    }

    public void deny(TestingPrivilege... deniedPrivileges)
    {
        Collections.addAll(this.denyPrivileges, deniedPrivileges);
    }

    public void rowFilter(QualifiedObjectName table, String identity, ViewExpression filter)
    {
        rowFilters.computeIfAbsent(new RowFilterKey(identity, table), key -> new ArrayList<>())
                .add(filter);
    }

    public void columnMask(QualifiedObjectName table, String column, String identity, ViewExpression mask)
    {
        columnMasks.computeIfAbsent(new ColumnMaskKey(identity, table, column), key -> new ArrayList<>())
                .add(mask);
    }

    public void reset()
    {
        denyPrivileges.clear();
        deniedCatalogs = s -> true;
        deniedTables = s -> true;
        rowFilters.clear();
        columnMasks.clear();
    }

    public void denyCatalogs(Predicate<String> deniedCatalogs)
    {
        this.deniedCatalogs = this.deniedCatalogs.and(deniedCatalogs);
    }

    public void denyTables(Predicate<SchemaTableName> deniedTables)
    {
        this.deniedTables = this.deniedTables.and(deniedTables);
    }

    @Override
    public Set<String> filterCatalogs(Identity identity, Set<String> catalogs)
    {
        return super.filterCatalogs(
                identity,
                catalogs.stream()
                        .filter(this.deniedCatalogs)
                        .collect(toImmutableSet()));
    }

    @Override
    public Set<SchemaTableName> filterTables(SecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        return super.filterTables(
                context,
                catalogName,
                tableNames.stream()
                        .filter(this.deniedTables)
                        .collect(toImmutableSet()));
    }

    @Override
    public void checkCanImpersonateUser(Identity identity, String userName)
    {
        if (shouldDenyPrivilege(userName, userName, IMPERSONATE_USER)) {
            denyImpersonateUser(identity.getUser(), userName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanImpersonateUser(identity, userName);
        }
    }

    @Override
    @Deprecated
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        if (shouldDenyPrivilege(userName, userName, SET_USER)) {
            denySetUser(principal, userName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetUser(principal, userName);
        }
    }

    @Override
    public void checkCanExecuteQuery(Identity identity)
    {
        if (shouldDenyPrivilege(identity.getUser(), "query", EXECUTE_QUERY)) {
            denyExecuteQuery();
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanExecuteQuery(identity);
        }
    }

    @Override
    public void checkCanViewQueryOwnedBy(Identity identity, String queryOwner)
    {
        if (shouldDenyPrivilege(identity.getUser(), "query", VIEW_QUERY)) {
            denyViewQuery();
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanViewQueryOwnedBy(identity, queryOwner);
        }
    }

    @Override
    public Set<String> filterQueriesOwnedBy(Identity identity, Set<String> owners)
    {
        if (shouldDenyPrivilege(identity.getUser(), "query", VIEW_QUERY)) {
            return ImmutableSet.of();
        }
        if (denyPrivileges.isEmpty()) {
            return super.filterQueriesOwnedBy(identity, owners);
        }
        return owners;
    }

    @Override
    public void checkCanKillQueryOwnedBy(Identity identity, String queryOwner)
    {
        if (shouldDenyPrivilege(identity.getUser(), "query", KILL_QUERY)) {
            denyKillQuery();
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanKillQueryOwnedBy(identity, queryOwner);
        }
    }

    @Override
    public void checkCanCreateSchema(SecurityContext context, CatalogSchemaName schemaName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), schemaName.getSchemaName(), CREATE_SCHEMA)) {
            denyCreateSchema(schemaName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateSchema(context, schemaName);
        }
    }

    @Override
    public void checkCanDropSchema(SecurityContext context, CatalogSchemaName schemaName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), schemaName.getSchemaName(), DROP_SCHEMA)) {
            denyDropSchema(schemaName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropSchema(context, schemaName);
        }
    }

    @Override
    public void checkCanRenameSchema(SecurityContext context, CatalogSchemaName schemaName, String newSchemaName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), schemaName.getSchemaName(), RENAME_SCHEMA)) {
            denyRenameSchema(schemaName.toString(), newSchemaName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameSchema(context, schemaName, newSchemaName);
        }
    }

    @Override
    public void checkCanShowCreateTable(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), SHOW_CREATE_TABLE)) {
            denyShowCreateTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanShowCreateTable(context, tableName);
        }
    }

    @Override
    public void checkCanCreateTable(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), CREATE_TABLE)) {
            denyCreateTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateTable(context, tableName);
        }
    }

    @Override
    public void checkCanDropTable(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), DROP_TABLE)) {
            denyDropTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropTable(context, tableName);
        }
    }

    @Override
    public void checkCanRenameTable(SecurityContext context, QualifiedObjectName tableName, QualifiedObjectName newTableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), RENAME_TABLE)) {
            denyRenameTable(tableName.toString(), newTableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameTable(context, tableName, newTableName);
        }
    }

    @Override
    public void checkCanSetTableComment(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), COMMENT_TABLE)) {
            denyCommentTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetTableComment(context, tableName);
        }
    }

    @Override
    public void checkCanAddColumns(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), ADD_COLUMN)) {
            denyAddColumn(tableName.toString());
        }
        super.checkCanAddColumns(context, tableName);
    }

    @Override
    public void checkCanDropColumn(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), DROP_COLUMN)) {
            denyDropColumn(tableName.toString());
        }
        super.checkCanDropColumn(context, tableName);
    }

    @Override
    public void checkCanRenameColumn(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), RENAME_COLUMN)) {
            denyRenameColumn(tableName.toString());
        }
        super.checkCanRenameColumn(context, tableName);
    }

    @Override
    public void checkCanInsertIntoTable(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), INSERT_TABLE)) {
            denyInsertTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanInsertIntoTable(context, tableName);
        }
    }

    @Override
    public void checkCanDeleteFromTable(SecurityContext context, QualifiedObjectName tableName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), DELETE_TABLE)) {
            denyDeleteTable(tableName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDeleteFromTable(context, tableName);
        }
    }

    @Override
    public void checkCanCreateView(SecurityContext context, QualifiedObjectName viewName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), viewName.getObjectName(), CREATE_VIEW)) {
            denyCreateView(viewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateView(context, viewName);
        }
    }

    @Override
    public void checkCanRenameView(SecurityContext context, QualifiedObjectName viewName, QualifiedObjectName newViewName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), viewName.getObjectName(), RENAME_VIEW)) {
            denyRenameView(viewName.toString(), newViewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanRenameView(context, viewName, newViewName);
        }
    }

    @Override
    public void checkCanDropView(SecurityContext context, QualifiedObjectName viewName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), viewName.getObjectName(), DROP_VIEW)) {
            denyDropView(viewName.toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanDropView(context, viewName);
        }
    }

    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, String propertyName)
    {
        if (shouldDenyPrivilege(identity.getUser(), propertyName, SET_SESSION)) {
            denySetSystemSessionProperty(propertyName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetSystemSessionProperty(identity, propertyName);
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SecurityContext context, QualifiedObjectName tableName, Set<String> columnNames)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), CREATE_VIEW_WITH_SELECT_COLUMNS)) {
            denyCreateViewWithSelect(tableName.toString(), context.getIdentity());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanCreateViewWithSelectFromColumns(context, tableName, columnNames);
        }
    }

    @Override
    public void checkCanGrantExecuteFunctionPrivilege(SecurityContext context, String functionName, Identity grantee, boolean grantOption)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), functionName, GRANT_EXECUTE_FUNCTION)) {
            denyGrantExecuteFunctionPrivilege(functionName, context.getIdentity(), grantee);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanGrantExecuteFunctionPrivilege(context, functionName, grantee, grantOption);
        }
    }

    @Override
    public void checkCanShowColumns(SecurityContext context, CatalogSchemaTableName table)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), table.getSchemaTableName().getTableName(), SHOW_COLUMNS)) {
            denyShowColumns(table.getSchemaTableName().toString());
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanShowColumns(context, table);
        }
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SecurityContext context, String catalogName, String propertyName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), catalogName + "." + propertyName, SET_SESSION)) {
            denySetCatalogSessionProperty(catalogName, propertyName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSetCatalogSessionProperty(context, catalogName, propertyName);
        }
    }

    @Override
    public void checkCanSelectFromColumns(SecurityContext context, QualifiedObjectName tableName, Set<String> columns)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), tableName.getObjectName(), SELECT_COLUMN)) {
            denySelectColumns(tableName.toString(), columns);
        }
        for (String column : columns) {
            if (shouldDenyPrivilege(context.getIdentity().getUser(), column, SELECT_COLUMN)) {
                denySelectColumns(tableName.toString(), columns);
            }
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanSelectFromColumns(context, tableName, columns);
        }
    }

    @Override
    public void checkCanExecuteFunction(SecurityContext context, String functionName)
    {
        if (shouldDenyPrivilege(context.getIdentity().getUser(), functionName, EXECUTE_FUNCTION)) {
            denyExecuteFunction(functionName);
        }
        if (denyPrivileges.isEmpty()) {
            super.checkCanExecuteFunction(context, functionName);
        }
    }

    @Override
    public List<ViewExpression> getRowFilters(SecurityContext context, QualifiedObjectName tableName)
    {
        List<ViewExpression> viewExpressions = rowFilters.get(new RowFilterKey(context.getIdentity().getUser(), tableName));
        if (viewExpressions != null) {
            return viewExpressions;
        }
        return super.getRowFilters(context, tableName);
    }

    @Override
    public List<ViewExpression> getColumnMasks(SecurityContext context, QualifiedObjectName tableName, String column, Type type)
    {
        List<ViewExpression> viewExpressions = columnMasks.get(new ColumnMaskKey(context.getIdentity().getUser(), tableName, column));
        if (viewExpressions != null) {
            return viewExpressions;
        }
        return super.getColumnMasks(context, tableName, column, type);
    }

    private boolean shouldDenyPrivilege(String userName, String entityName, TestingPrivilegeType type)
    {
        return shouldDenyPrivilege(privilege(userName, entityName, type));
    }

    private boolean shouldDenyPrivilege(TestingPrivilege privilege)
    {
        TestingPrivilege testPrivilege = privilege;
        for (TestingPrivilege denyPrivilege : denyPrivileges) {
            if (denyPrivilege.matches(testPrivilege)) {
                return true;
            }
        }
        return false;
    }

    public enum TestingPrivilegeType
    {
        SET_USER, IMPERSONATE_USER,
        EXECUTE_QUERY, VIEW_QUERY, KILL_QUERY,
        EXECUTE_FUNCTION,
        CREATE_SCHEMA, DROP_SCHEMA, RENAME_SCHEMA,
        SHOW_CREATE_TABLE, CREATE_TABLE, DROP_TABLE, RENAME_TABLE, COMMENT_TABLE, INSERT_TABLE, DELETE_TABLE, SHOW_COLUMNS,
        ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, SELECT_COLUMN,
        CREATE_VIEW, RENAME_VIEW, DROP_VIEW, CREATE_VIEW_WITH_SELECT_COLUMNS,
        GRANT_EXECUTE_FUNCTION,
        SET_SESSION
    }

    public static class TestingPrivilege
    {
        private final Optional<String> userName;
        private final String entityName;
        private final TestingPrivilegeType type;

        private TestingPrivilege(Optional<String> userName, String entityName, TestingPrivilegeType type)
        {
            this.userName = requireNonNull(userName, "userName is null");
            this.entityName = requireNonNull(entityName, "entityName is null");
            this.type = requireNonNull(type, "type is null");
        }

        public boolean matches(TestingPrivilege testPrivilege)
        {
            return userName.map(name -> testPrivilege.userName.get().equals(name)).orElse(true) &&
                    entityName.equals(testPrivilege.entityName) &&
                    type == testPrivilege.type;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TestingPrivilege that = (TestingPrivilege) o;
            return Objects.equals(entityName, that.entityName) &&
                    type == that.type;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(entityName, type);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("userName", userName)
                    .add("entityName", entityName)
                    .add("type", type)
                    .toString();
        }
    }

    private static class RowFilterKey
    {
        private final String identity;
        private final QualifiedObjectName table;

        public RowFilterKey(String identity, QualifiedObjectName table)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.table = requireNonNull(table, "table is null");
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            RowFilterKey that = (RowFilterKey) o;
            return identity.equals(that.identity) &&
                    table.equals(that.table);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, table);
        }
    }

    private static class ColumnMaskKey
    {
        private final String identity;
        private final QualifiedObjectName table;
        private final String column;

        public ColumnMaskKey(String identity, QualifiedObjectName table, String column)
        {
            this.identity = identity;
            this.table = table;
            this.column = column;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ColumnMaskKey that = (ColumnMaskKey) o;
            return identity.equals(that.identity) &&
                    table.equals(that.table) &&
                    column.equals(that.column);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(identity, table, column);
        }
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @Qualifier
    public @interface ForSystemAccessControl {}
}
