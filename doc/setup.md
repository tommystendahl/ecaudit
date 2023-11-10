# Setup ecAudit

In this setup guide you'll find pointers to the installation procedure as well as various ways to configure ecAudit.

When upgrading ecAudit to a new version, always consider special [upgrade instructions](../UPGRADING.md) which may apply.


## Prerequisites

ecAudit requires a Java 8 or Java 11 compatible JVM.


## Installation

ecAudit is integrating with Apache Cassandra using some of its many plug-in points.
The [installation](install.md) guide describes how to deploy the ecAudit plugin and configure Cassandra to use it.


## Configuration

Once installed, ecAudit will start to generate audit records.
The ecAudit plugin itself is configured,
either by settings in the ```audit.yaml``` file
or with Java properties which typically are set in the ```cassandra-env.sh``` file.

Read on below to learn how to tune the logger backend and manage audit whitelists.

In the [audit.yaml reference](audit_yaml_reference.md) there are more details about different options.

A custom setup is described in the [Query Logger Example](example_query_logger.md).


### Logger Backend

Two different logger backends are supported out of the box:
* There is the [SLF4J Logger](slf4j_logger.md) backend which is using the well known logging framework.
  Together with [Logback](https://logback.qos.ch/), this provides a wide range of options when generating audit records.
  This is the best option if you need audit records in clear-text files, or want an easy integration based on one of the many Logback appenders.
  With the SLF4J backend audit record format is configurable using settings in ecAudit.
* Then there is the [Chronicle Logger](chronicle_logger.md) backend which has the best performance characteristics.
  This backend stores audit records in a binary format and is best suited when handling large volumes of records.


### Logger Timing

Logger timing specifies *when* log entries should be written, **pre-logging** (default) and **post-logging** (C* 4.0 style) are available.
You'll find more details in the [audit.yaml reference](audit_yaml_reference.md).


### Audit Whitelists

By default ecAudit will create a record for each and every login attempt and CQL query.
The result can be rather overwhelming,
but with whitelists it is possible to configure what operations that will *not* yield an audit record.

There are a few different ways to setup and manage whitelists:
* With [Role Based Whitelist_Management](role_whitelist_management.md) all whitelists are defined with CQL statements.
  Any update will be automatically distributed and applied to all nodes in the cluster.
  As the name implies,
* The [YAML Whitelist](yaml_whitelist_management.md) option offers a simple way to get started.
  Changes are applied per node and requires a restart.
* The [YAML 'n' ROLE](yaml_and_role_whitelist_management.md) option is a combination of the two previous options.
  With this setting, an operation will be considered to be whitelisted if it is covered by any of the two whitelists.
* Finally, it is also possible to disable whitelists completely in which case all operations will be audit logged.

To disable whitelists all together, add the following option near the end of your ```cassandra-env.sh```

```
JVM_EXTRA_OPTS="$JVM_EXTRA_OPTS -Decaudit.filter_type=NONE"
```


### Wrapped Authenticator Backend

The ecAudit plug-in must be installed as the ```authenticator``` in the ```cassandra.yaml``` in order to capture authentication operations for auditing.
The actual authentication must still be handled by an ```IDecoratedAuthenticator``` implementation.
The ```IDecoratedAuthenticator``` is an extended version of the ```IAuthenticator``` interface provided by Cassandra.
ecAudit provides the ```DecoratedPasswordAuthenticator``` out of the box, which is used by default.
The ```DecoratedPasswordAuthenticator``` is just and extended version of the ```PasswordAuthenticator``` provided by Cassandra.

With the ```wrapped_authenticator``` setting in the ```audit.yaml``` file it is possible to configure another ```IDecoratedAuthenticator``` which may be your own custom implementation.
This is useful if requiring a non-default (i.e. not ```PasswordAuthenticator```) implementation of an authenticator but still want authentication logs.

For backwards compatibility reasons the ```AuditPasswordAuthenticator``` is also provided by ecAudit.
It is an ```IAuthenticator``` which will emit audit records and it is hard wired to use the standard ```PasswordAuthenticator``` for authentication.
It may be installed directly in the  ```cassandra.yaml```.


### Wrapped Authorizer Backend

The ecAudit plug-in must be installed as the ```authorizer``` in the ```cassandra.yaml``` in order to capture some operations.
The actual authorization still has to be handled by another ```IAuthorizer``` implementation.
Cassandra provides the ```AllowAllAuthorizer``` and the ```CassandraAuthorizer``` out of the box,
where ecAudit will delegate authorization operations to the ```CassandraAuthorizer``` by default.
With the ```wrapped_authorizer``` setting in the ```audit.yaml``` file it is possible to configure another ```IAuthorizer``` which may be your own custom implementation.
