# Phrag

**GraphQL from a RDBMS Connection**

![main](https://github.com/ykskb/phrag/actions/workflows/test.yml/badge.svg)

### Overview

**Instantly Operational**
Phrag creates a GraphQL from a RDBMS connection, using schema data such as tables, columns, and primary / foreign keys.

**CRUD / SQL Features**
All tables become queryable as root objects containing nested objects of [relationships](docs/mechanism.md#relationships). [Mutations](docs/mechanism.md#mutations) (`create`, `update` and `delete`) are also created per tables. Additionally, [aggregation](docs/sql_feature.md#aggregation), [filter](docs/sql_feature.md#filtering), [sorting](docs/sql_feature.md#sorting) and [pagination](docs/sql_feature.md#pagination) are supported for query operations.

**Performance in Mind**
Phrag's query resolver implements a batched SQL query per nest level to avoid N+1 problem. [Load tests](docs/performance.md) have also been performed to verify it scales linear with resources without obvious bottlenecks.

**Customization**
Phrag comes with an [interceptor capability](#interceptor-signals) to customize behaviors of GraphQL. Custom functions can be configured before & after database accesses per tables and operation types, which can make GraphQL more practical with access control, event firing and more.

Documentation:

- [Database Requirements / Mechanism](docs/mechanism.md)

- [Configuration](docs/config.md)

- [Interceptors](docs/interceptor.md)

- [SQL Features](docs/sql_feature.md)

- [Performance](docs/performance.md)

- [Development](docs/development.md)

Example projects:

- [SNS](https://github.com/ykskb/situated-sns-backend): a situated project for Phrag to verify its concept and practicality. It has authentication, access control and custom logics through Phrag's interceptors.

### Usage

Phrag's GraphQL can be invoked as a function, [reitit](https://github.com/metosin/reitit) route or [Bidi](https://github.com/juxt/bidi) route. Database (`:db`) is the only required parameter in `config`, but there are many more configurable options. Please refer to [config doc](docs/config.md) for details.

Function:

```clojure
(let [schema (phrag/schema config)]
  (phrag/exec config schema query vars req))
```

Reitit route in an Integrant config map:

```clojure
{:phrag.route/reitit {:db (ig/ref :sql/datasource)} }
```

Copyright © 2021 Yohei Kusakabe
