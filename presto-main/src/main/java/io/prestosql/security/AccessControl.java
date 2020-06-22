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
package io.prestosql.security;

import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.security.AccessDeniedException;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.Privilege;
import io.prestosql.spi.security.ViewExpression;
import io.prestosql.spi.type.Type;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccessControl
{
    /**
     * Check if the principal is allowed to be the specified user.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     * @deprecated replaced with user mapping during authentication and {@link #checkCanImpersonateUser}
     */
    @Deprecated
    void checkCanSetUser(Optional<Principal> principal, String userName);

    /**
     * Check if the identity is allowed impersonate the specified user.
     *
     * @throws AccessDeniedException if not allowed
     */
    void checkCanImpersonateUser(Identity identity, String userName);

    /**
     * Checks if identity can execute a query.
     *
     * @throws AccessDeniedException if not allowed
     */
    void checkCanExecuteQuery(Identity identity);

    /**
     * Checks if identity can view a query owned by the specified user.  The method
     * will not be called when the current user is the query owner.
     *
     * @throws AccessDeniedException if not allowed
     */
    void checkCanViewQueryOwnedBy(Identity identity, String queryOwner);

    /**
     * Filter the list of users to those the identity view query owned by the user.  The method
     * will not be called with the current user in the set.
     */
    Set<String> filterQueriesOwnedBy(Identity identity, Set<String> queryOwners);

    /**
     * Checks if identity can kill a query owned by the specified user.  The method
     * will not be called when the current user is the query owner.
     *
     * @throws AccessDeniedException if not allowed
     */
    void checkCanKillQueryOwnedBy(Identity identity, String queryOwner);

    /**
     * Filter the list of catalogs to those visible to the identity.
     */
    Set<String> filterCatalogs(Identity identity, Set<String> catalogs);

    /**
     * Check if identity is allowed to create the specified schema.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanCreateSchema(SecurityContext context, CatalogSchemaName schemaName);

    /**
     * Check if identity is allowed to drop the specified schema.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDropSchema(SecurityContext context, CatalogSchemaName schemaName);

    /**
     * Check if identity is allowed to rename the specified schema.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRenameSchema(SecurityContext context, CatalogSchemaName schemaName, String newSchemaName);

    /**
     * Check if identity is allowed to change the specified schema's user/role.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSetSchemaAuthorization(SecurityContext context, CatalogSchemaName schemaName, PrestoPrincipal principal);

    /**
     * Check if identity is allowed to execute SHOW SCHEMAS in a catalog.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterSchemas} method must filter all results for unauthorized users,
     * since there are multiple ways to list schemas.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowSchemas(SecurityContext context, String catalogName);

    /**
     * Filter the list of schemas in a catalog to those visible to the identity.
     */
    Set<String> filterSchemas(SecurityContext context, String catalogName, Set<String> schemaNames);

    /**
     * Check if identity is allowed to execute SHOW CREATE TABLE or SHOW CREATE VIEW.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowCreateTable(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to create the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanCreateTable(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to drop the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDropTable(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to rename the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRenameTable(SecurityContext context, QualifiedObjectName tableName, QualifiedObjectName newTableName);

    /**
     * Check if identity is allowed to comment the specified table.
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSetTableComment(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to show tables by executing SHOW TABLES, SHOW GRANTS etc. in a catalog schema.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterTables} method must filter all results for unauthorized users,
     * since there are multiple ways to list tables.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowTables(SecurityContext context, CatalogSchemaName schema);

    /**
     * Filter the list of tables and views to those visible to the identity.
     */
    Set<SchemaTableName> filterTables(SecurityContext context, String catalogName, Set<SchemaTableName> tableNames);

    /**
     * Check if identity is allowed to show columns of tables by executing SHOW COLUMNS, DESCRIBE etc.
     * <p>
     * NOTE: This method is only present to give users an error message when listing is not allowed.
     * The {@link #filterColumns} method must filter all results for unauthorized users,
     * since there are multiple ways to list columns.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowColumns(SecurityContext context, CatalogSchemaTableName table);

    /**
     * Filter the list of columns to those visible to the identity.
     */
    List<ColumnMetadata> filterColumns(SecurityContext context, CatalogSchemaTableName tableName, List<ColumnMetadata> columns);

    /**
     * Check if identity is allowed to add columns to the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanAddColumns(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to drop columns from the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDropColumn(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to rename a column in the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRenameColumn(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to insert into the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanInsertIntoTable(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to delete from the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDeleteFromTable(SecurityContext context, QualifiedObjectName tableName);

    /**
     * Check if identity is allowed to create the specified view.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanCreateView(SecurityContext context, QualifiedObjectName viewName);

    /**
     * Check if identity is allowed to rename the specified view.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRenameView(SecurityContext context, QualifiedObjectName viewName, QualifiedObjectName newViewName);

    /**
     * Check if identity is allowed to drop the specified view.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDropView(SecurityContext context, QualifiedObjectName viewName);

    /**
     * Check if identity is allowed to create a view that selects from the specified columns.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanCreateViewWithSelectFromColumns(SecurityContext context, QualifiedObjectName tableName, Set<String> columnNames);

    /**
     * Check if identity is allowed to create a view that executes the function.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanGrantExecuteFunctionPrivilege(SecurityContext context, String functionName, Identity grantee, boolean grantOption);

    /**
     * Check if identity is allowed to grant a privilege to the grantee on the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanGrantTablePrivilege(SecurityContext context, Privilege privilege, QualifiedObjectName tableName, PrestoPrincipal grantee, boolean grantOption);

    /**
     * Check if identity is allowed to revoke a privilege from the revokee on the specified table.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRevokeTablePrivilege(SecurityContext context, Privilege privilege, QualifiedObjectName tableName, PrestoPrincipal revokee, boolean grantOption);

    /**
     * Check if identity is allowed to set the specified system property.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSetSystemSessionProperty(Identity identity, String propertyName);

    /**
     * Check if identity is allowed to set the specified catalog property.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSetCatalogSessionProperty(SecurityContext context, String catalogName, String propertyName);

    /**
     * Check if identity is allowed to select from the specified columns.  The column set can be empty.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSelectFromColumns(SecurityContext context, QualifiedObjectName tableName, Set<String> columnNames);

    /**
     * Check if identity is allowed to create the specified role.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanCreateRole(SecurityContext context, String role, Optional<PrestoPrincipal> grantor, String catalogName);

    /**
     * Check if identity is allowed to drop the specified role.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanDropRole(SecurityContext context, String role, String catalogName);

    /**
     * Check if identity is allowed to grant the specified roles to the specified principals.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanGrantRoles(SecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOption, Optional<PrestoPrincipal> grantor, String catalogName);

    /**
     * Check if identity is allowed to revoke the specified roles from the specified principals.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanRevokeRoles(SecurityContext context, Set<String> roles, Set<PrestoPrincipal> grantees, boolean adminOption, Optional<PrestoPrincipal> grantor, String catalogName);

    /**
     * Check if identity is allowed to set role for specified catalog.
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanSetRole(SecurityContext context, String role, String catalogName);

    /**
     * Check if identity is allowed to show roles on the specified catalog.
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowRoles(SecurityContext context, String catalogName);

    /**
     * Check if identity is allowed to show current roles on the specified catalog.
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowCurrentRoles(SecurityContext context, String catalogName);

    /**
     * Check if identity is allowed to show its own role grants on the specified catalog.
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanShowRoleGrants(SecurityContext context, String catalogName);

    /**
     * Check if identity is allowed to execute procedure
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanExecuteProcedure(SecurityContext context, QualifiedObjectName procedureName);

    /**
     * Check if identity is allowed to execute function
     *
     * @throws io.prestosql.spi.security.AccessDeniedException if not allowed
     */
    void checkCanExecuteFunction(SecurityContext context, String functionName);

    default List<ViewExpression> getRowFilters(SecurityContext context, QualifiedObjectName tableName)
    {
        return ImmutableList.of();
    }

    default List<ViewExpression> getColumnMasks(SecurityContext context, QualifiedObjectName tableName, String columnName, Type type)
    {
        return ImmutableList.of();
    }
}
