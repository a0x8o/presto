# Hive connector security configuration

(hive-security-impersonation)=

## Overview

The Hive connector supports both authentication and authorization.

Trino can impersonate the end user who is running a query. In the case of a
user running a query from the command line interface, the end user is the
username associated with the Trino CLI process or argument to the optional
`--user` option.

Authentication can be configured with or without user impersonation on
Kerberized Hadoop clusters.

## Requirements

End user authentication limited to Kerberized Hadoop clusters. Authentication
user impersonation is available for both Kerberized and non-Kerberized clusters.

You must ensure that you meet the Kerberos, user impersonation and keytab
requirements described in this section that apply to your configuration.

(hive-security-kerberos-support)=

### Kerberos

In order to use the Hive connector with a Hadoop cluster that uses `kerberos`
authentication, you must configure the connector to work with two services on
the Hadoop cluster:

- The Hive metastore Thrift service
- The Hadoop Distributed File System (HDFS)

Access to these services by the Hive connector is configured in the properties
file that contains the general Hive connector configuration.

Kerberos authentication by ticket cache is not yet supported.

:::{note}
If your `krb5.conf` location is different from `/etc/krb5.conf` you
must set it explicitly using the `java.security.krb5.conf` JVM property
in `jvm.config` file.

Example: `-Djava.security.krb5.conf=/example/path/krb5.conf`.
:::

:::{warning}
Access to the Trino coordinator must be secured e.g., using Kerberos or
password authentication, when using Kerberos authentication to Hadoop services.
Failure to secure access to the Trino coordinator could result in unauthorized
access to sensitive data on the Hadoop cluster. Refer to {doc}`/security` for
further information.

See {doc}`/security/kerberos` for information on setting up Kerberos authentication.
:::

(hive-security-additional-keytab)=

#### Keytab files

Keytab files contain encryption keys that are used to authenticate principals
to the Kerberos {abbr}`KDC (Key Distribution Center)`. These encryption keys
must be stored securely; you must take the same precautions to protect them
that you take to protect ssh private keys.

In particular, access to keytab files must be limited to only the accounts
that must use them to authenticate. In practice, this is the user that
the Trino process runs as. The ownership and permissions on keytab files
must be set to prevent other users from reading or modifying the files.

Keytab files must be distributed to every node running Trino. Under common
deployment situations, the Hive connector configuration is the same on all
nodes. This means that the keytab needs to be in the same location on every
node.

You must ensure that the keytab files have the correct permissions on every
node after distributing them.

(configuring-hadoop-impersonation)=

### Impersonation in Hadoop

In order to use impersonation, the Hadoop cluster must be
configured to allow the user or principal that Trino is running as to
impersonate the users who log in to Trino. Impersonation in Hadoop is
configured in the file {file}`core-site.xml`. A complete description of the
configuration options can be found in the [Hadoop documentation](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/Superusers.html#Configurations).

## Authentication

The default security configuration of the {doc}`/connector/hive` does not use
authentication when connecting to a Hadoop cluster. All queries are executed as
the user who runs the Trino process, regardless of which user submits the
query.

The Hive connector provides additional security options to support Hadoop
clusters that have been configured to use {ref}`Kerberos
<hive-security-kerberos-support>`.

When accessing {abbr}`HDFS (Hadoop Distributed File System)`, Trino can
{ref}`impersonate<hive-security-impersonation>` the end user who is running the
query. This can be used with HDFS permissions and {abbr}`ACLs (Access Control
Lists)` to provide additional security for data.

### Hive metastore Thrift service authentication

In a Kerberized Hadoop cluster, Trino connects to the Hive metastore Thrift
service using {abbr}`SASL (Simple Authentication and Security Layer)` and
authenticates using Kerberos. Kerberos authentication for the metastore is
configured in the connector's properties file using the following optional
properties:

```{eval-rst}
.. list-table:: Hive metastore Thrift service authentication properties
  :widths: 30, 55, 15
  :header-rows: 1

  * - Property value
    - Description
    - Default
  * - ``hive.metastore.authentication.type``
    - Hive metastore authentication type. One of ``NONE`` or ``KERBEROS``. When
      using the default value of ``NONE``, Kerberos authentication is disabled,
      and no other properties must be configured.

      When set to ``KERBEROS`` the Hive connector connects to the Hive metastore
      Thrift service using SASL and authenticate using Kerberos.
    - ``NONE``
  * - ``hive.metastore.thrift.impersonation.enabled``
    - Enable Hive metastore end user impersonation. See
      :ref:`hive-security-metastore-impersonation` for more information.
    - ``false``
  * - ``hive.metastore.service.principal``
    - The Kerberos principal of the Hive metastore service. The coordinator
      uses this to authenticate the Hive metastore.

      The ``_HOST`` placeholder can be used in this property value. When
      connecting to the Hive metastore, the Hive connector substitutes in the
      hostname of the **metastore** server it is connecting to. This is useful
      if the metastore runs on multiple hosts.

      Example: ``hive/hive-server-host@EXAMPLE.COM`` or
      ``hive/_HOST@EXAMPLE.COM``.
    -
  * - ``hive.metastore.client.principal``
    - The Kerberos principal that Trino uses when connecting to the Hive
      metastore service.

      Example: ``trino/trino-server-node@EXAMPLE.COM`` or
      ``trino/_HOST@EXAMPLE.COM``.

      The ``_HOST`` placeholder can be used in this property value. When
      connecting to the Hive metastore, the Hive connector substitutes in the
      hostname of the **worker** node Trino is running on. This is useful if
      each worker node has its own Kerberos principal.

      Unless :ref:`hive-security-metastore-impersonation` is enabled,
      the principal specified by ``hive.metastore.client.principal`` must have
      sufficient privileges to remove files and directories within the
      ``hive/warehouse`` directory.

      **Warning:** If the principal does have sufficient permissions, only the
      metadata is removed, and the data continues to consume disk space. This
      occurs because the Hive metastore is responsible for deleting the
      internal table data. When the metastore is configured to use Kerberos
      authentication, all of the HDFS operations performed by the metastore are
      impersonated. Errors deleting data are silently ignored.
    -
  * - ``hive.metastore.client.keytab``
    - The path to the keytab file that contains a key for the principal
      specified by ``hive.metastore.client.principal``. This file must be
      readable by the operating system user running Trino.
    -
```

#### Configuration examples

The following sections describe the configuration properties and values needed
for the various authentication configurations needed to use the Hive metastore
Thrift service with the Hive connector.

##### Default `NONE` authentication without impersonation

```text
hive.metastore.authentication.type=NONE
```

The default authentication type for the Hive metastore is `NONE`. When the
authentication type is `NONE`, Trino connects to an unsecured Hive
metastore. Kerberos is not used.

(hive-security-metastore-impersonation)=

##### `KERBEROS` authentication with impersonation

```text
hive.metastore.authentication.type=KERBEROS
hive.metastore.thrift.impersonation.enabled=true
hive.metastore.service.principal=hive/hive-metastore-host.example.com@EXAMPLE.COM
hive.metastore.client.principal=trino@EXAMPLE.COM
hive.metastore.client.keytab=/etc/trino/hive.keytab
```

When the authentication type for the Hive metastore Thrift service is
`KERBEROS`, Trino connects as the Kerberos principal specified by the
property `hive.metastore.client.principal`. Trino authenticates this
principal using the keytab specified by the `hive.metastore.client.keytab`
property, and verifies that the identity of the metastore matches
`hive.metastore.service.principal`.

When using `KERBEROS` Metastore authentication with impersonation, the
principal specified by the `hive.metastore.client.principal` property must be
allowed to impersonate the current Trino user, as discussed in the section
{ref}`configuring-hadoop-impersonation`.

Keytab files must be distributed to every node in the cluster that runs Trino.

{ref}`Additional Information About Keytab Files.<hive-security-additional-keytab>`

### HDFS authentication

In a Kerberized Hadoop cluster, Trino authenticates to HDFS using Kerberos.
Kerberos authentication for HDFS is configured in the connector's properties
file using the following optional properties:

```{eval-rst}
.. list-table:: HDFS authentication properties
  :widths: 30, 55, 15
  :header-rows: 1

  * - Property value
    - Description
    - Default
  * - ``hive.hdfs.authentication.type``
    - HDFS authentication type; one of ``NONE`` or ``KERBEROS``. When using the
      default value of ``NONE``, Kerberos authentication is disabled, and no
      other properties must be configured.

      When set to ``KERBEROS``, the Hive connector authenticates to HDFS using
      Kerberos.
    - ``NONE``
  * - ``hive.hdfs.impersonation.enabled``
    - Enable HDFS end-user impersonation. Impersonating the end user can provide
      additional security when accessing HDFS if HDFS permissions or ACLs are
      used.

      HDFS Permissions and ACLs are explained in the `HDFS Permissions Guide
      <https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-hdfs/HdfsPermissionsGuide.html>`_.
    - ``false``
  * - ``hive.hdfs.trino.principal``
    - The Kerberos principal Trino uses when connecting to HDFS.

      Example: ``trino-hdfs-superuser/trino-server-node@EXAMPLE.COM`` or
      ``trino-hdfs-superuser/_HOST@EXAMPLE.COM``.

      The ``_HOST`` placeholder can be used in this property value. When
      connecting to HDFS, the Hive connector substitutes in the hostname of the
      **worker** node Trino is running on. This is useful if each worker node
      has its own Kerberos principal.
    -
  * - ``hive.hdfs.trino.keytab``
    - The path to the keytab file that contains a key for the principal
      specified by ``hive.hdfs.trino.principal``. This file must be readable by
      the operating system user running Trino.
    -
  * - ``hive.hdfs.wire-encryption.enabled``
    - Enable HDFS wire encryption. In a Kerberized Hadoop cluster that uses HDFS
      wire encryption, this must be set to ``true`` to enable Trino to access
      HDFS. Note that using wire encryption may impact query execution
      performance.
    -
```

#### Configuration examples

The following sections describe the configuration properties and values needed
for the various authentication configurations with HDFS and the Hive connector.

(hive-security-simple)=

##### Default `NONE` authentication without impersonation

```text
hive.hdfs.authentication.type=NONE
```

The default authentication type for HDFS is `NONE`. When the authentication
type is `NONE`, Trino connects to HDFS using Hadoop's simple authentication
mechanism. Kerberos is not used.

(hive-security-simple-impersonation)=

##### `NONE` authentication with impersonation

```text
hive.hdfs.authentication.type=NONE
hive.hdfs.impersonation.enabled=true
```

When using `NONE` authentication with impersonation, Trino impersonates
the user who is running the query when accessing HDFS. The user Trino is
running as must be allowed to impersonate this user, as discussed in the
section {ref}`configuring-hadoop-impersonation`. Kerberos is not used.

(hive-security-kerberos)=

##### `KERBEROS` authentication without impersonation

```text
hive.hdfs.authentication.type=KERBEROS
hive.hdfs.trino.principal=hdfs@EXAMPLE.COM
hive.hdfs.trino.keytab=/etc/trino/hdfs.keytab
```

When the authentication type is `KERBEROS`, Trino accesses HDFS as the
principal specified by the `hive.hdfs.trino.principal` property. Trino
authenticates this principal using the keytab specified by the
`hive.hdfs.trino.keytab` keytab.

Keytab files must be distributed to every node in the cluster that runs Trino.

{ref}`Additional Information About Keytab Files.<hive-security-additional-keytab>`

(hive-security-kerberos-impersonation)=

##### `KERBEROS` authentication with impersonation

```text
hive.hdfs.authentication.type=KERBEROS
hive.hdfs.impersonation.enabled=true
hive.hdfs.trino.principal=trino@EXAMPLE.COM
hive.hdfs.trino.keytab=/etc/trino/hdfs.keytab
```

When using `KERBEROS` authentication with impersonation, Trino impersonates
the user who is running the query when accessing HDFS. The principal
specified by the `hive.hdfs.trino.principal` property must be allowed to
impersonate the current Trino user, as discussed in the section
{ref}`configuring-hadoop-impersonation`. Trino authenticates
`hive.hdfs.trino.principal` using the keytab specified by
`hive.hdfs.trino.keytab`.

Keytab files must be distributed to every node in the cluster that runs Trino.

{ref}`Additional Information About Keytab Files.<hive-security-additional-keytab>`

## Authorization

You can enable authorization checks for the {doc}`hive` by setting
the `hive.security` property in the Hive catalog properties file. This
property must be one of the following values:

```{eval-rst}
.. list-table:: ``hive.security`` property values
  :widths: 30, 60
  :header-rows: 1

  * - Property value
    - Description
  * - ``legacy`` (default value)
    - Few authorization checks are enforced, thus allowing most operations. The
      config properties ``hive.allow-drop-table``, ``hive.allow-rename-table``,
      ``hive.allow-add-column``, ``hive.allow-drop-column`` and
      ``hive.allow-rename-column`` are used.
  * - ``read-only``
    - Operations that read data or metadata, such as ``SELECT``, are permitted,
      but none of the operations that write data or metadata, such as
      ``CREATE``, ``INSERT`` or ``DELETE``, are allowed.
  * - ``file``
    - Authorization checks are enforced using a catalog-level access control
      configuration file whose path is specified in the ``security.config-file``
      catalog configuration property. See
      :ref:`catalog-file-based-access-control` for details.
  * - ``sql-standard``
    - Users are permitted to perform the operations as long as they have the
      required privileges as per the SQL standard. In this mode, Trino enforces
      the authorization checks for queries based on the privileges defined in
      Hive metastore. To alter these privileges, use the :doc:`/sql/grant` and
      :doc:`/sql/revoke` commands.

      See the :ref:`hive-sql-standard-based-authorization` section for details.
  * - ``allow-all``
    - No authorization checks are enforced.
```

(hive-sql-standard-based-authorization)=

### SQL standard based authorization

When `sql-standard` security is enabled, Trino enforces the same SQL
standard-based authorization as Hive does.

Since Trino's `ROLE` syntax support matches the SQL standard, and
Hive does not exactly follow the SQL standard, there are the following
limitations and differences:

- `CREATE ROLE role WITH ADMIN` is not supported.
- The `admin` role must be enabled to execute `CREATE ROLE`, `DROP ROLE` or `CREATE SCHEMA`.
- `GRANT role TO user GRANTED BY someone` is not supported.
- `REVOKE role FROM user GRANTED BY someone` is not supported.
- By default, all a user's roles, except `admin`, are enabled in a new user session.
- One particular role can be selected by executing `SET ROLE role`.
- `SET ROLE ALL` enables all of a user's roles except `admin`.
- The `admin` role must be enabled explicitly by executing `SET ROLE admin`.
- `GRANT privilege ON SCHEMA schema` is not supported. Schema ownership can be changed with `ALTER SCHEMA schema SET AUTHORIZATION user`
