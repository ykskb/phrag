# Phrag

**RDBMS Schema to GraphQL with Interceptors**

Phrag creates a GraphQL handler from RDBMS schema. All needed is a connection to a DB with [proper constraints](#design).

Phrag has an [interceptor signal](#interceptor-signals) feature to inject custom logics per GraphQL operation. It can make GraphQL more practical with things like access control and event firing per queries/mutations.

![main](https://github.com/ykskb/phrag/actions/workflows/test.yml/badge.svg)

#### Features:

- CRUD operations (`query` and `create`/`update`/`delete` mutations) created per resource with [Lacinia](https://github.com/walmartlabs/lacinia).

- `One-to-one`, `one-to-many`, `many-to-many` and `circular many-to-many` relationships supported as nested query objects as per its [design](#query-relationships).

- Data loader (query batching) to avoid N+1 problem for nested queries, leveraging [superlifter](https://github.com/seancorfield/honeysql) and [Urania](https://github.com/funcool/urania)

- [Aggregation queries](#aggregation) for root entity and has-many relationships.

- Resource [filtering](#resource-filtering), [sorting](#resource-sorting) and [pagination](#resource-pagination) as arguments in query operations.

- [Interceptor Signals](#interceptor-signals) to inject custom logics before/after DB accesses per resource operations.

- Options to use schema retrieved from a database, selectively override it or entirely base on provided data through [config](#config).

- Automatic router wiring for [reitit](https://github.com/metosin/reitit) and [bidi](https://github.com/juxt/bidi).

- GraphQL IDE (like GraphiQL) connectable.

#### Notes:

- Supported databases are SQLite and PostgreSQL.

- This project is currently in POC/brush-up stage for a real project usage, so it's not been published to Clojars yet.

### Usage

Create ring app with reitit route using Integrant

```clojure
{:phrag.core/reitit-graphql-route {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}}
```

### Design

Phrag focuses on constraints to let database schema represent application data structure.

##### Query Relationships

Phrag transforms a foreign key (FK) constraint into nested query objects of GraphQL as the diagram below.

<img src="./docs/images/fk-transform.png" />

This is a fundamental concept for Phrag to support multiple types of relationships.

##### Mutations

Primary key (PK) constraints are identifier constructs of mutations. `create` operations return PK column fields and `update`/`delete` operations require them as identifier data.

<!---
> Notes:
> * There is an option to detect relations from table/column names, however it comes with a limitation since matching names such as `user_id` for `users` table are required.
-->

##### Tables

Though schema data can be overriden, all the tables are transformed into queries at root level (of course with all the relationships). This allows API consumers to query data in any structure they wish without restricting them to query only in certain way.

### Config

Though there are multiple options for customization, the only config parameter required for Phrag is a database connection.

#### Config Parameters

| Key                  | description                                                                                                               | Required | Default Value |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------- | ------------- |
| `:db`                | Database connection object.                                                                                               | Yes      |               |
| `:tables`            | List of custom table definitions. Plz check [Schema Data](#schema-data) for details.                                      | No       |               |
| `:signals`           | Map of singal functions per resources. Plz check [Interceptor Signals](#interceptor-signals) for details.                 | No       |               |
| `:signal-ctx`        | Additional context to be passed into signal functions. Plz check [Interceptor Signals](#interceptor-signals) for details. | No       |               |
| `:use-aggregation`   | `true` if aggregation is desired on root entity queries and has-many relationships.                                       | No       | `true`        |
| `:scan-schema`       | `true` if DB schema scan is desired for resources in GraphQL.                                                             | No       | `true`        |
| `:no-fk-on-db`       | `true` if there's no foreign key is set on DB and relationship detection is desired from column/table names.              | No       | `false`       |
| `:table-name-plural` | `true` if tables uses plural naming like `users` instead of `user`. Required when `:no-fk-on-db` is `true`.               | No       | `true`        |

#### Schema Data

By default, Phrag retrieves DB schema data from a DB connection and it is sufficient to construct GraphQL. Yet it is also possible to provide custom schema data, which can be useful to exclude certain tables, columns and/or relationships from specific tables. Custom schema data can be specified as a list of tables under `:tables` key in the config map.

```edn
{:tables [
   {:name "users"
    :columns [{:name "id"
       	       :type "int"
               :notnull 0
               :dflt_value nil}
              {:name "image_id"
               :type "int"
               :notnull 1
               :dflt_value 1}
	           ;; ... more columns
	           ]
    :fks [{:table "images" :from "image_id" :to "id"}]
    :pks [{:name "id" :type "int"}]}
    ;; ... more tables
    ]}
```

##### Table Data Details:

| Key        | Description                                                                                      |
| ---------- | ------------------------------------------------------------------------------------------------ |
| `:name`    | Table name.                                                                                      |
| `:columns` | List of columns. A column can contain `:name`, `:type`, `:notnull` and `:dflt_value` parameters. |
| `:fks`     | List of foreign keys. A foreign key can contain `:table`, `:from` and `:to` parameters.          |
| `:pks`     | List of primary keys. A primary key can contain `:name` and `:type` parameters.                  |

> Notes:
>
> - When `:scan-schema` is `false`, Phrag will construct GraphQL from the provided table data only.
> - When `:scan-schema` is `true`, provided table data will override scanned table data per table properties: `:name`, `:table-type`, `:columns`, `:fks` and `:pks`.

### Interceptor Signals

Phrag can signal configured functions per resource queries/mutations at pre/post-operation time. This is where things like access controls or custom business logics can be configured. Signal functions are called with different parameters as below:

##### Pre-operation Signal Function

| Type   | Signal function receives (as first parameter):                    | Returned value will be:                |
| ------ | ----------------------------------------------------------------- | -------------------------------------- |
| query  | SQL parameter map: `{:select #{} :where [] :offset 0 :limit 100}` | Passed to subsequent query operation.  |
| create | Submitted mutation parameters                                     | Passed to subsequent create operation. |
| update | Submitted mutation parameters                                     | Passed to subsequent update operation. |
| delete | Submitted mutation parameters                                     | Passed to subsequent delete operation. |

> Notes:
>
> - `query` signal functions will be called in nested queries (relations) as well.
> - `:where` parameter for `query` operation is in [HoneySQL](https://github.com/seancorfield/honeysql) format.

##### Post-operation Signal Function

| Type   | Signal function receives (as a first parameter):   | Returned value will be:  |
| ------ | -------------------------------------------------- | ------------------------ |
| query  | Result value(s) returned from query operation.     | Passed to response body. |
| create | Primary key object of created item: e.g. `{:id 3}` | Passed to response body. |
| update | Result object: `{:result true}`                    | Passed to response body. |
| delete | Result object: `{:result true}`                    | Passed to response body. |

##### All Signal Functions

All receiver functions will have a context map as its second argument. It'd contain a signal context specified in a Phrag config (`:signal-ctx`) together with a DB connection (`:db`) and an incoming HTTP request (`:req`).

##### Examples

```clojure
(defn- end-user-access
  "Users can query only his/her own user info"
  [sql-args ctx]
  (let [user (user-info (:req ctx))]
    (if (admin-user? user))
      sql-args
      (update sql-args :where conj [:= :user_id (:id user)])))

(defn- hide-internal-id
  "Removes internal-id for non-admin users"
  [result ctx]
  (let [user (user-info (:req ctx))]
    (if (admin-user? user))
      result
      (update result :internal-id nil)))

(defn- update-owner
  "Updates created_by with accessing user's id"
  [args ctx]
  (let [user (user-info (:req ctx))]
    (if (end-user? user)
      (assoc args :created_by (:id user))
      args)))

;; Multiple signal function can be specified as a vector.

(def example-config
  {:signals {:all [check-user-auth check-user-role]
             :users {:query {:pre end-user-access
                             :post hide-internal-id}
                     :create {:pre update-owner}
                     :update {:pre update-owner}}}})
```

> Notes:
>
> - `:all` can be used at each level of signal map to run signal functions across all tables, all operations for a table, or both timing for a specific operation.

### Resource Filtering

Format of `where: {column-a: {operator: value} column-b: {operator: value}}` is used in arguments for filtering. `AND` / `OR` group can be created as clause lists in `and` / `or` parameter under `where`. Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

> - Supported operators are `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in` and `like`.
> - Multiple filters are applied with `AND` operator.

##### Example:

`{users (where: {name: {like: "%ken%"} or: [{age: {eq: 20}}, {age: {eq: 21}}]})}` (`users` where `name` is `like` `ken` `AND` `age` is `20` `OR` `21`)

### Resource Sorting

Format of `sort: {[column]: [asc or desc]}` is used in query arguments for sorting. Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

##### Example:

`sort: {id: asc}` (sort by `id` column in ascending order)

### Resource Pagination

Formats of `limit: [count]` and `offset: [count]` are used in query arguments for pagination. Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

> - `limit` and `offset` can be used independently.
> - Using `offset` can return different results when new entries are created while items are sorted by newest first. So using `limit` with `id` filter or `created_at` filter is often considered more consistent.

##### Example

`(where: {id: {gt: 20}} limit: 25)` (25 items after/greater than `id`:`20`).

### Aggregation

`avg`, `count`, `max`, `min` and `sum` are supported and it can also be [filtered](#resource-filtering). Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

##### Example

`cart_items_aggregate (where: {cart_id: {eq: 1}}) {count max {price} min {price} avg {price} sum {price}}` (queries `count` of `cart_items` together with `max`, `min` `sum` and `avg` of `price` where `cart_id` is `1`).

### Environment

To begin developing, start with a REPL.

```sh
lein repl
```

Then load the development environment with reitit example.

```clojure
user=> (dev-reitit)
:loaded
```

Run `go` to prep and initiate the system.

```clojure
dev=> (go)
:initiated
```

By default this creates a web server at <http://localhost:3000>.

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```

### Testing

Testing is fastest through the REPL, as you avoid environment startup
time.

```clojure
dev=> (test)
...
```

But you can also run tests through Leiningen.

```sh
lein eftest
```

## Legal

Copyright © 2021 Yohei Kusakabe
