# Phrag

**GraphQL from a RDBMS Connection**

An approach of GraphQL on RDBMS for instant and flexible data access while maintaining customization possibilities.

![main](https://github.com/ykskb/phrag/actions/workflows/test.yml/badge.svg) [![Clojars Project](https://img.shields.io/clojars/v/com.github.ykskb/phrag.svg)](https://clojars.org/com.github.ykskb/phrag)

### Overview

- **Instantly Operational**: Phrag creates a GraphQL simply from a RDBMS connection, using schema data such as tables, columns, and primary / foreign keys. All needed is a [database](#requirements).

- **CRUD / SQL Features**: tables and/or views become queryable as root objects containing nested objects of [relationships](docs/mechanism.md#relationships). [Mutations](docs/mechanism.md#mutations) (`create`, `update` and `delete`) are also created per tables. Additionally, [aggregation](docs/sql_feature.md#aggregation), [filter](docs/sql_feature.md#filtering), [sorting](docs/sql_feature.md#sorting) and [pagination](docs/sql_feature.md#pagination) are supported for query operations.

- **Customization**: Phrag comes with an [interceptor capability](#interceptor-signals) to customize behaviors of GraphQL. Custom functions can be configured before & after database accesses per tables and operation types, which can make GraphQL more practical with access controls, event firing and more.

- **Performance in Mind**: Phrag's query resolver implements a batched SQL query per nest level to avoid N+1 problem. [Load tests](docs/performance.md) have also been performed to verify it scales linear with resources without obvious bottlenecks.

### Usage

Phrag's GraphQL can be invoked as a function, [reitit](https://github.com/metosin/reitit) route or [Bidi](https://github.com/juxt/bidi) route. Database (`:db`) is the only required parameter in `config`, but there are many more configurable options. Please refer to [configuration doc](docs/config.md) for details.

Function:

```clojure
(let [config {:db (hikari/make-datasource my-spec)}
      schema (phrag/schema config)]
  (phrag/exec config schema query vars req))
```

Reitit route in an Integrant config map:

```clojure
{:phrag.route/reitit {:db (ig/ref :my/datasource)}}
```

#### Stand-alone Version

There is also a stand-alone version of Phrag which is instantly runnable. It's suitable if a Phrag's GraphQL is desired without any custom logic or if one wants to play around with it.

Phrag's docker image is ready to connect to your database with a single command as below. Here's its [repository](https://github.com/ykskb/phrag-standalone) for more options.

```sh
docker run -it -p 3000:3000 \
-e JDBC_URL=jdbc:sqlite:path/to/db.sqlite \
ykskb/phrag-standalone:latest
```

### Requirements

All needed is an RDBMS. Here's a quick view of database constructs which are important for Phrag. Detailed mechanism is explained [here](docs/mechanism.md).

- **Primary keys**: Phrag uses primary keys as identifiers of GraphQL mutations. Composite primary key is supported.

- **Foreign keys**: Phrag translates foreign keys to nested properties in GraphQL objects.

- **Indices on foreign key columns**: Phrag queries a database by origin and destination columns of foreign keys for nested objects. It should be noted that creating a foreign key does not index those columns.

> **Notes**
>
> - Supported databases are SQLite and PostgreSQL.
>
> - If PostgreSQL is used, Phrag queries tables such as `key_column_usage` and `constraint_column_usage` to retrieve PK / FK info, therefore the database user provided to Phrag needs to be identical to the one that created those keys.
>
> - Not all database column types are mapped to Phrag's GraphQL fields yet. Any help would be appreciated through issues and PRs.

### Documentation

- [Mechanism](docs/mechanism.md)

- [Configuration](docs/config.md)

- [Interceptors](docs/interceptor.md)

- [SQL Features](docs/sql_feature.md)

- [Performance](docs/performance.md)

- [Development](docs/development.md)

Example projects:

- [SNS](https://github.com/ykskb/situated-sns-backend): a situated project to verify Phrag's concept and practicality. It has authentication, access control and custom logics through Phrag's interceptors.

Copyright © 2021 Yohei Kusakabe
