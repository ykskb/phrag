# Phrag

**RDBMS Schema to GraphQL with Interceptors**

![main](https://github.com/ykskb/phrag/actions/workflows/test.yml/badge.svg)

Phrag creates a GraphQL handler from a RDBMS connection with an idea that DB schema with primary/foreign keys can sufficiently represent data models/relationships for GraphQL.

Tables become queryable as root objects containing nested objects of relationships. Mutations (`create`, `update` and `delete`) are also created per tables with primary keys as their identifiers.

In addition, Phrag allows custom functions to be configured before & after DB accesses per resource operations. It can make GraphQL more practical with things like access control and event firing per queries/mutations.

### Features:

- CRUD operations (`query` and `create`/`update`/`delete` mutations) created per resource with [Lacinia](https://github.com/walmartlabs/lacinia).

- `One-to-one`, `one-to-many`, `many-to-many` and `circular many-to-many` relationships supported as nested query objects.

- Data loader (query batching) to avoid N+1 problem for nested queries, leveraging [superlifter](https://github.com/seancorfield/honeysql) and [Urania](https://github.com/funcool/urania)

- [Aggregation queries](#aggregation) for root entity and has-many relationships.

- [Filtering](#filtering), [sorting](#sorting) and [pagination](#pagination) as arguments in query operations.

- [Interceptor signals](#interceptor-signals) to inject custom logics before/after DB accesses per resource operations.

- Automatic route wiring for [reitit](https://github.com/metosin/reitit) and [bidi](https://github.com/juxt/bidi).

- GraphQL IDE (like GraphiQL) connectable.

### Usage

Create a ring app with reitit using Integrant

```clojure
{:phrag.route/reitit {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit)}}
```

### Notes:

- Supported databases are SQLite and PostgreSQL.

- This project is currently in POC/brush-up stage for a real project usage, so it's not been published to Clojars yet.

- Not all database column types are mapped to GraphQL fields yet. Any help such as reports and PRs would be appreciated.

- Phrag transforms a foreign key constraint into nested query objects of GraphQL as the diagram below. This is a fundamental concept for Phrag to support multiple types of relationships:
  <img src="./docs/images/fk-transform.png" width="600px" />

### Config

Though there are multiple options for customization, the only config parameter required for Phrag is a database connection.

##### Parameters

| Key                  | description                                                                                                               | Required | Default Value |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------- | ------------- |
| `:db`                | Database connection object.                                                                                               | Yes      |               |
| `:tables`            | List of custom table definitions. Plz check [Schema Data](#schema-data) for details.                                      | No       |               |
| `:signals`           | Map of singal functions per resources. Plz check [Interceptor Signals](#interceptor-signals) for details.                 | No       |               |
| `:signal-ctx`        | Additional context to be passed into signal functions. Plz check [Interceptor Signals](#interceptor-signals) for details. | No       |               |
| `:default-limit`     | Default number for SQL `LIMIT` value to be applied when there's no `:limit` argument is specified in a query.             | No       | `nil`         |
| `:use-aggregation`   | `true` if aggregation is desired on root entity queries and has-many relationships.                                       | No       | `true`        |
| `:scan-schema`       | `true` if DB schema scan is desired for resources in GraphQL.                                                             | No       | `true`        |
| `:no-fk-on-db`       | `true` if there's no foreign key is set on DB and relationship detection is desired from column/table names.              | No       | `false`       |
| `:table-name-plural` | `true` if tables uses plural naming like `users` instead of `user`. Required when `:no-fk-on-db` is `true`.               | No       | `true`        |

##### Schema Data

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

##### Pre-operation Interceptor Function

| Type   | Signal function receives (as first parameter):                             | Returned value will be:                |
| ------ | -------------------------------------------------------------------------- | -------------------------------------- |
| query  | SQL parameter map: `{:select #{} :where [] :sort [] :offset 0 :limit 100}` | Passed to subsequent query operation.  |
| create | Submitted mutation parameters                                              | Passed to subsequent create operation. |
| update | Submitted mutation parameters                                              | Passed to subsequent update operation. |
| delete | Submitted mutation parameters                                              | Passed to subsequent delete operation. |

> Notes:
>
> - `query` signal functions for matching table will be called in nested queries (relations) as well.
> - `:where` and `:limit` parameter for `query` operation are in [HoneySQL](https://github.com/seancorfield/honeysql) format.

##### Post-operation Interceptor Function

| Type   | Signal function receives (as a first parameter):   | Returned value will be:  |
| ------ | -------------------------------------------------- | ------------------------ |
| query  | Result value(s) returned from query operation.     | Passed to response body. |
| create | Primary key object of created item: e.g. `{:id 3}` | Passed to response body. |
| update | Result object: `{:result true}`                    | Passed to response body. |
| delete | Result object: `{:result true}`                    | Passed to response body. |

##### All Interceptor Functions

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

### Filtering

Format of `where: {column-a: {operator: value} column-b: {operator: value}}` is used in arguments for filtering. `AND` / `OR` group can be created as clause lists in `and` / `or` parameter under `where`. Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

> - Supported operators are `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in` and `like`.
> - Multiple filters are applied with `AND` operator.

##### Example:

`{users (where: {name: {like: "%ken%"} or: [{age: {eq: 20}}, {age: {eq: 21}}]})}` (`users` where `name` is `like` `ken` `AND` `age` is `20` `OR` `21`)

### Sorting

Format of `sort: {[column]: [asc or desc]}` is used in query arguments for sorting. Actual formats can be checked through the introspection on UI such as GraphiQL once you run Phrag.

##### Example:

`sort: {id: asc}` (sort by `id` column in ascending order)

### Pagination

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
