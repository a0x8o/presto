# Trino concepts

## Overview

To understand Trino, you must first understand the terms and concepts
used throughout the Trino documentation.

While it is easy to understand statements and queries, as an end-user
you should have familiarity with concepts such as stages and splits to
take full advantage of Trino to execute efficient queries. As a
Trino administrator or a Trino contributor you should understand how
Trino's concepts of stages map to tasks and how tasks contain a set
of drivers which process data.

This section provides a solid definition for the core concepts
referenced throughout Trino, and these sections are sorted from most
general to most specific.

:::{note}
The book [Trino: The Definitive Guide](https://trino.io/trino-the-definitive-guide.html) and the research
paper [Presto: SQL on Everything](https://trino.io/paper.html) can
provide further information about Trino and the concepts in use.
:::

(trino-concept-architecture)=

## Architecture

Trino is a distributed query engine that processes data in parallel across
multiple servers. There are two types of Trino servers,
{ref}`coordinators <trino-concept-coordinator>` and
{ref}`workers <trino-concept-worker>`. The following sections describe these
servers and other components of Trino's architecture.

(trino-concept-cluster)=

### Cluster

A Trino cluster consists of a {ref}`coordinator <trino-concept-coordinator>` and
many {ref}`workers <trino-concept-worker>`. Users connect to the coordinator
with their {ref}`SQL <glossSQL>` query tool. The coordinator collaborates with the
workers. The coordinator and the workers access the connected
{ref}`data sources <trino-concept-data-sources>`. This access is configured in
{ref}`catalogs <trino-concept-catalog>`.

Processing each query is a stateful operation. The workload is orchestrated by
the coordinator and spread parallel across all workers in the cluster. Each node
runs Trino in one JVM instance, and processing is parallelized further using
threads.

(trino-concept-coordinator)=

### Coordinator

The Trino coordinator is the server that is responsible for parsing
statements, planning queries, and managing Trino worker nodes.  It is
the "brain" of a Trino installation and is also the node to which a
client connects to submit statements for execution. Every Trino
installation must have a Trino coordinator alongside one or more
Trino workers. For development or testing purposes, a single
instance of Trino can be configured to perform both roles.

The coordinator keeps track of the activity on each worker and
coordinates the execution of a query. The coordinator creates
a logical model of a query involving a series of stages, which is then
translated into a series of connected tasks running on a cluster of
Trino workers.

Coordinators communicate with workers and clients using a REST API.

(trino-concept-worker)=

### Worker

A Trino worker is a server in a Trino installation, which is responsible
for executing tasks and processing data. Worker nodes fetch data from
connectors and exchange intermediate data with each other. The coordinator
is responsible for fetching results from the workers and returning the
final results to the client.

When a Trino worker process starts up, it advertises itself to the discovery
server in the coordinator, which makes it available to the Trino coordinator
for task execution.

Workers communicate with other workers and Trino coordinators
using a REST API.

(trino-concept-data-sources)=

## Data sources

Throughout this documentation, you'll read terms such as connector,
catalog, schema, and table. These fundamental concepts cover Trino's
model of a particular data source and are described in the following
section.

### Connector

A connector adapts Trino to a data source such as Hive or a
relational database. You can think of a connector the same way you
think of a driver for a database. It is an implementation of Trino's
{doc}`SPI </develop/spi-overview>`, which allows Trino to interact
with a resource using a standard API.

Trino contains several built-in connectors: a connector for
{doc}`JMX </connector/jmx>`, a {doc}`System </connector/system>`
connector which provides access to built-in system tables,
a {doc}`Hive </connector/hive>` connector, and a
{doc}`TPCH </connector/tpch>` connector designed to serve TPC-H benchmark
data. Many third-party developers have contributed connectors so that
Trino can access data in a variety of data sources.

Every catalog is associated with a specific connector. If you examine
a catalog configuration file, you see that each contains a
mandatory property `connector.name`, which is used by the catalog
manager to create a connector for a given catalog. It is possible
to have more than one catalog use the same connector to access two
different instances of a similar database. For example, if you have
two Hive clusters, you can configure two catalogs in a single Trino
cluster that both use the Hive connector, allowing you to query data
from both Hive clusters, even within the same SQL query.

(trino-concept-catalog)=

### Catalog

A Trino catalog contains schemas and references a data source via a
connector.  For example, you can configure a JMX catalog to provide
access to JMX information via the JMX connector. When you run SQL
statements in Trino, you are running them against one or more catalogs.
Other examples of catalogs include the Hive catalog to connect to a
Hive data source.

When addressing a table in Trino, the fully-qualified table name is
always rooted in a catalog. For example, a fully-qualified table name
of `hive.test_data.test` refers to the `test` table in the
`test_data` schema in the `hive` catalog.

Catalogs are defined in properties files stored in the Trino
configuration directory.

### Schema

Schemas are a way to organize tables. Together, a catalog and schema
define a set of tables that can be queried. When accessing Hive or a
relational database such as MySQL with Trino, a schema translates to
the same concept in the target database. Other types of connectors may
choose to organize tables into schemas in a way that makes sense for
the underlying data source.

### Table

A table is a set of unordered rows, which are organized into named columns
with types. This is the same as in any relational database. The mapping
from source data to tables is defined by the connector.

## Query execution model

Trino executes SQL statements and turns these statements into queries,
that are executed across a distributed cluster of coordinator and workers.

### Statement

Trino executes ANSI-compatible SQL statements.  When the Trino
documentation refers to a statement, it is referring to statements as
defined in the ANSI SQL standard, which consists of clauses,
expressions, and predicates.

Some readers might be curious why this section lists separate concepts
for statements and queries. This is necessary because, in Trino,
statements simply refer to the textual representation of a statement written
in SQL. When a statement is executed, Trino creates a query along
with a query plan that is then distributed across a series of Trino
workers.

### Query

When Trino parses a statement, it converts it into a query and creates
a distributed query plan, which is then realized as a series of
interconnected stages running on Trino workers. When you retrieve
information about a query in Trino, you receive a snapshot of every
component that is involved in producing a result set in response to a
statement.

The difference between a statement and a query is simple. A statement
can be thought of as the SQL text that is passed to Trino, while a query
refers to the configuration and components instantiated to execute
that statement. A query encompasses stages, tasks, splits, connectors,
and other components and data sources working in concert to produce a
result.

(trino-concept-stage)=

### Stage

When Trino executes a query, it does so by breaking up the execution
into a hierarchy of stages. For example, if Trino needs to aggregate
data from one billion rows stored in Hive, it does so by creating a
root stage to aggregate the output of several other stages, all of
which are designed to implement different sections of a distributed
query plan.

The hierarchy of stages that comprises a query resembles a tree.
Every query has a root stage, which is responsible for aggregating
the output from other stages. Stages are what the coordinator uses to
model a distributed query plan, but stages themselves don't run on
Trino workers.

(trino-concept-task)=

### Task

As mentioned in the previous section, stages model a particular
section of a distributed query plan, but stages themselves don't
execute on Trino workers. To understand how a stage is executed,
you need to understand that a stage is implemented as a series of
tasks distributed over a network of Trino workers.

Tasks are the "work horse" in the Trino architecture as a distributed
query plan is deconstructed into a series of stages, which are then
translated to tasks, which then act upon or process splits. A Trino
task has inputs and outputs, and just as a stage can be executed in
parallel by a series of tasks, a task is executing in parallel with a
series of drivers.

(trino-concept-splits)=

### Split

Tasks operate on splits, which are sections of a larger data
set. Stages at the lowest level of a distributed query plan retrieve
data via splits from connectors, and intermediate stages at a higher
level of a distributed query plan retrieve data from other stages.

When Trino is scheduling a query, the coordinator queries a
connector for a list of all splits that are available for a table.
The coordinator keeps track of which machines are running which tasks,
and what splits are being processed by which tasks.

### Driver

Tasks contain one or more parallel drivers. Drivers act upon data and
combine operators to produce output that is then aggregated by a task
and then delivered to another task in another stage. A driver is a
sequence of operator instances, or you can think of a driver as a
physical set of operators in memory. It is the lowest level of
parallelism in the Trino architecture. A driver has one input and
one output.

### Operator

An operator consumes, transforms and produces data. For example, a table
scan fetches data from a connector and produces data that can be consumed
by other operators, and a filter operator consumes data and produces a
subset by applying a predicate over the input data.

### Exchange

Exchanges transfer data between Trino nodes for different stages of
a query. Tasks produce data into an output buffer and consume data
from other tasks using an exchange client.
