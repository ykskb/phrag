# Phrag

**GraphQL from a RDBMS Connection**

Phrag implements [its approach](docs/mechanism.md) to GraphQL on RDBMS for an instant and flexible data accesses with customization options.

![main](https://github.com/ykskb/phrag/actions/workflows/test.yml/badge.svg) [![Clojars Project](https://img.shields.io/clojars/v/com.github.ykskb/phrag.svg)](https://clojars.org/com.github.ykskb/phrag) [![cljdoc badge](https://cljdoc.org/badge/com.github.ykskb/phrag)](https://cljdoc.org/d/com.github.ykskb/phrag)

## Overview

- **Instantly Operational:** Phrag creates a GraphQL simply from a RDBMS connection, using schema data such as tables, columns, and primary / foreign keys. It can be run as a Clojure project or a [stand-alone executable](#stand-alone-version).

- **CRUD / Relationship Features:** tables and/or views become queryable as root objects including nested objects of [relationships](docs/mechanism.md#relationships) with [aggregation](docs/sql_feature.md#aggregation), [filter](docs/sql_feature.md#filtering), [sorting](docs/sql_feature.md#sorting) and [pagination](docs/sql_feature.md#pagination) supported. [Mutations](docs/mechanism.md#mutations) (`create`, `update` and `delete`) are also created per table.

- **Customization:** Phrag comes with an [interceptor capability](#interceptor-signals) to customize behaviors of GraphQL. Custom functions can be configured in Clojure before & after database accesses per table & operation type, which can make GraphQL more practical with access controls, event firing and more.

- **Performance in Mind:** Phrag's query resolver translates a nested object query into a single SQL query, leveraging correlated subqueries and JSON functions. [Load tests](docs/performance.md) have also been performed to verify it scales linear with resources without obvious bottlenecks.

## Requirements

Though there are multiple ways to run, Phrag only requires an RDBMS to create GraphQL. Here's a quick view of database constructs that are important for Phrag. Detailed mechanism is explained [here](docs/mechanism.md).

- **Primary keys:** Phrag uses primary keys as identifiers of GraphQL mutations. Composite primary key is supported.

- **Foreign keys:** Phrag translates foreign keys to nested properties in GraphQL objects.

- **Indices on foreign key columns:** Phrag queries a database by origin and destination columns of foreign keys for nested objects. It should be noted that creating a foreign key does not index those columns.

> **Notes:**
>
> - Supported databases are SQLite and PostgreSQL.
>
> - If PostgreSQL is used, Phrag queries usage tables such as `key_column_usage` and `constraint_column_usage` to retrieve PK / FK info, therefore the database user provided to Phrag needs to be identical to the one that created those keys.
>
> - Not all database column types are mapped to Phrag's GraphQL fields yet. Any help would be appreciated through issues and PRs.

## Usage

Phrag's GraphQL can be created with `phrag.core/schema` function and invoked through `phrag.core/exec` function:

```clojure
(let [config {:db (hikari/make-datasource my-spec)}
      schema (phrag/schema config)]
  (phrag/exec config schema query vars req))
```

There is also a support for creating Phrag's GraphQL as a route for [reitit](https://github.com/metosin/reitit) or [Bidi](https://github.com/juxt/bidi):

```clojure
;; Add a route (path & handler) into a ring router:
(ring/router (phrag.route/reitit {:db my-datasource})

;; Also callable as an Integrant config map key
{:phrag.route/reitit {:db (ig/ref :my/datasource)}}
```

> **Notes:**
>
> Database (`:db`) is the only required parameter in `config`, but there are many more configurable options. Please refer to [configuration doc](docs/config.md) for details.

## Stand-alone Version

There is a stand-alone version of Phrag which is runnable as a Docker container or Java process with a single command. It's suitable if Phrag's GraphQL is desired without any custom logic or if one wants to play around with it. [Here](https://github.com/ykskb/phrag-standalone) is the repository of those artifacts for more options and information.

```sh
# example: Docker container with SQLite
# visit http://localhost:3000/graphiql/index.html after running:
docker run -it -p 3000:3000 \
-v /host/db/dir:/database \
-e JDBC_URL=jdbc:sqlite:/database/db.sqlite \
ykskb/phrag-standalone:latest
```

## Documentation

- [Mechanism](docs/mechanism.md)

- [Configuration](docs/config.md)

- [Interceptors](docs/interceptor.md)

- [SQL Features](docs/sql_feature.md)

- [Performance](docs/performance.md)

- [Development](docs/development.md)

### Example projects:

- [SNS](https://github.com/ykskb/situated-sns-backend): a situated project to verify Phrag's concept and practicality. It has authentication, access control and custom logics through Phrag's interceptors.

Copyright © 2021 Yohei Kusakabe
