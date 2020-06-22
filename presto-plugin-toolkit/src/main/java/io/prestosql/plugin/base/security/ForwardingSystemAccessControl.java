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
package io.prestosql.plugin.base.security;

import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaRoutineName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.security.SystemAccessControl;
import io.prestosql.spi.security.SystemSecurityContext;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public abstract class ForwardingSystemAccessControl
        implements SystemAccessControl
{
    public static SystemAccessControl of(Supplier<SystemAccessControl> systemAccessControlSupplier)
    {
        requireNonNull(systemAccessControlSupplier, "systemAccessControlSupplier is null");
        return new ForwardingSystemAccessControl()
        {
            @Override
            protected SystemAccessControl delegate()
            {
                return systemAccessControlSupplier.get();
            }
        };
    }

    protected abstract SystemAccessControl delegate();

    @Override
    public void checkCanImpersonateUser(SystemSecurityContext context, String userName)
    {
        delegate().checkCanImpersonateUser(context, userName);
    }

    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        delegate().checkCanSetUser(principal, userName);
    }

    @Override
    public void checkCanExecuteQuery(SystemSecurityContext context)
    {
        delegate().checkCanExecuteQuery(context);
    }

    @Override
    public void checkCanViewQueryOwnedBy(SystemSecurityContext context, String queryOwner)
    {
        delegate().checkCanViewQueryOwnedBy(context, queryOwner);
    }

    @Override
    public Set<String> filterViewQueryOwnedBy(SystemSecurityContext context, Set<String> queryOwners)
    {
        return delegate().filterViewQueryOwnedBy(context, queryOwners);
    }

    @Override
    public void checkCanKillQueryOwnedBy(SystemSecurityContext context, String queryOwner)
    {
        delegate().checkCanKillQueryOwnedBy(context, queryOwner);
    }

    @Override
    public void checkCanSetSystemSessionProperty(SystemSecurityContext context, String propertyName)
    {
        delegate().checkCanSetSystemSessionProperty(context, propertyName);
    }

    @Override
    public void checkCanAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        delegate().checkCanAccessCatalog(context, catalogName);
    }

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        return delegate().filterCatalogs(context, catalogs);
    }

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        delegate().checkCanCreateSchema(context, schema);
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        delegate().checkCanDropSchema(context, schema);
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schema, String newSchemaName)
    {
        delegate().checkCanRenameSchema(context, schema, newSchemaName);
    }

    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schema, PrestoPrincipal principal)
    {
        delegate().checkCanSetSchemaAuthorization(context, schema, principal);
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
        delegate().checkCanShowSchemas(context, catalogName);
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        return delegate().filterSchemas(context, catalogName, schemaNames);
    }

    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanShowCreateTable(context, table);
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanCreateTable(context, table);
    }

    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanDropTable(context, table);
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
    {
        delegate().checkCanRenameTable(context, table, newTable);
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanSetTableComment(context, table);
    }

    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schema)
    {
        delegate().checkCanShowTables(context, schema);
    }

    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        return delegate().filterTables(context, catalogName, tableNames);
    }

    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName tableName)
    {
        delegate().checkCanShowColumns(context, tableName);
    }

    @Override
    public List<ColumnMetadata> filterColumns(SystemSecurityContext context, CatalogSchemaTableName tableName, List<ColumnMetadata> columns)
    {
        return delegate().filterColumns(context, tableName, columns);
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanAddColumn(context, table);
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanDropColumn(context, table);
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanRenameColumn(context, table);
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        delegate().checkCanSelectFromColumns(context, table, columns);
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanInsertIntoTable(context, table);
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        delegate().checkCanDeleteFromTable(context, table);
    }

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        delegate().checkCanCreateView(context, view);
    }

    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        delegate().checkCanRenameView(context, view, newView);
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        delegate().checkCanDropView(context, view);
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        delegate().checkCanCreateViewWithSelectFromColumns(context, table, columns);
    }

    @Override
    public void checkCanGrantExecuteFunctionPrivilege(SystemSecurityContext context, String functionName, PrestoPrincipal grantee, boolean grantOption)
    {
        delegate().checkCanGrantExecuteFunctionPrivilege(context, functionName, grantee, grantOption);
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
        delegate().checkCanSetCatalogSessionProperty(context, catalogName, propertyName);
    }

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal grantee, boolean grantOption)
    {
        delegate().checkCanGrantTablePrivilege(context, privilege, table, grantee, grantOption);
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, PrestoPrincipal revokee, boolean grantOption)
    {
        delegate().checkCanRevokeTablePrivilege(context, privilege, table, revokee, grantOption);
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context, String catalogName)
    {
        delegate().checkCanShowRoles(context, catalogName);
    }

    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName procedure)
    {
        delegate().checkCanExecuteProcedure(systemSecurityContext, procedure);
    }

    @Override
    public void checkCanExecuteFunction(SystemSecurityContext systemSecurityContext, String functionName)
    {
        delegate().checkCanExecuteFunction(systemSecurityContext, functionName);
    }

    @Override
    public Optional<ViewExpression> getRowFilter(SystemSecurityContext context, CatalogSchemaTableName tableName)
    {
        return delegate().getRowFilter(context, tableName);
    }

    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName tableName, String columnName, Type type)
    {
        return delegate().getColumnMask(context, tableName, columnName, type);
    }
}
