# Phrag

Instantly-operational yet customizable GraphQL handler for RDBMS

#### Features:

* `Query`, `create`, `update`, and `delete` operations created per resources from DB schema data, using [Lacinia](https://github.com/walmartlabs/lacinia).

* `One-to-one`, `one-to-many` and `many-to-many` relationships supported as nested resource structures.

* Data loader (query batching) to avoid N+1 problem for nested queries, leveraging [superlifter](https://github.com/seancorfield/honeysql) and [Urania](https://github.com/seancorfield/honeysql)

* [Signals](#signals) to inject custom logics before & after DB accesses per resource operations.

* Out-of-the-box resource [filtering](#resource-filtering), [sorting](#resource-sorting) and [pagination](#resource-pagination) for query operations.

* Options to selectively override schema data or entirely base on provided data through [config](#phrag-config).

* Automatic router wiring for [reitit](https://github.com/metosin/reitit) and [bidi](https://github.com/juxt/bidi).

* GraphQL IDE (like GraphiQL) connectable.

#### Notes:

* Supported databases are SQLite and PostgreSQL.

* This project is currently in POC/brush-up stage for a real project usage, so it's not been published to Clojars yet.

### Requirements

* Each entitiy table should have an `id` column for row identities. It's typically a primary key of types such as auto-increment integer or UUID.
* Pivot (bridge) tables for `many-to-many` relationships should have a composite primary key of pivoting foreign keys. `PRIMARY KEY (article_id, tag_id)` for example.
* By default, relationships are identified with foreign key constraints on databases. There is an option to detect relations from table/column names, however it comes with a limitation since matching names such as `user_id` for `users` table are required.

### Usage

##### reitit route

```clojure
;; Read schema data from DB (as data for Integrant)
{:phrag.core/reitit-graphql-route {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}}

;; Provide schema data (direct function call)
(def routes (phrag.core/make-reitit-graphql-route {:tables [{:name "..."}]}))
```

##### bidi route

```clojure
;; Read schema data from DB (as data for Integrant)
{:phrag.core/bidi-graphql-route {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}}

;; Provide schema data (direct function call)
(def routes (phrag.core/make-bidi-graphql-route {:tables [{:name "..."}]}))
```

### Phrag Config

Though there are multiple options for customization, the only config parameter required for Phrag is a database connection.

#### Config Parameters

| Key                  | description                                                                                                 | Required | Default Value |
|----------------------|-------------------------------------------------------------------------------------------------------------|----------|---------------|
| `:db`                | Database connection object.                                                                                 | Yes      |               |
| `:tables`            | List of custom table definitions. Plz check [Schema Data](#schema-data) for details.                        | No       |               |
| `:signals`           | Map of singal functions per resources. Plz check [Signals](#signals) for details.                           | No       |               |
| `:signal-ctx`        | Additional context to be passed into signal functions. Plz check [Signals](#signals) for details.           | No       |               |
| `:scan-schema`       | `true` if DB schema scan is desired for resources in GraphQL.                                               | No       | `true`        |
| `:no-fk-on-db`       | `true` if there's no foreign key is set on DB and relationship detection from names is desired.             | No       | `false`       |
| `:table-name-plural` | `true` if tables uses plural naming like `users` instead of `user`. Required when `:no-fk-on-db` is `true`. | No       | `true`        |

#### Schema Data

By default, Phrag retrieves DB schema data from a DB connection and it is sufficient to construct GraphQL. Yet it is also possible to provide custom schema data, which can be useful to exclude certain columns and/or relationships for specific tables. Custom schema data can be specified as a list of tables under `:tables` key in the config map.

> Notes:
> * When `:scan-schema` is `false`, Phrag will construct GraphQL from the provided table data only.
> * When `:scan-schema` is `true`, provided table data will override scanned table data per table properties: `:name`, `:table-type`, `:columns`, `:fks` and `:pks`.

```edn
{:tables [
   {:name "users"
    :table-type :root
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

| Key           | Description                                                                                      |
|---------------|--------------------------------------------------------------------------------------------------|
| `:name`       | Table name.                                                                                      |
| `:table-type` | Table type. `:root` or `:pivot` are supported.                                                   |
| `:columns`    | List of columns. A column can contain `:name`, `:type`, `:notnull` and `:dflt_value` parameters. |
| `:fks`        | List of foreign keys. A foreign key can contain `:table`, `:from` and `:to` parameters.          |
| `:pks`        | List of primary keys. A primary key can contain `:name` and `:type` parameters.                  |

### Signals

Phrag can signal configurable functions per resource queries/mutations at pre/post-DB operation time. This is where things like access controls or custom business logics can be configured.

> Notes:
> * Resource operations types include `query`, `create`, `update` and `delete`.
> * Signal receiver functions are called with different parameters per types:
>     * A pre-query function will have a list of `where` clauses in [HoneySQL](https://github.com/seancorfield/honeysql) format as its first argument, and its returned value will be passed to a subsequent DB operation.
>     * A pre-mutation function will have request parameters as its first argument, and its returned value will be passed to a subsequent DB operation.
>     * A post-query/mutation function will have a resolved result as its first argument when called, and its returned value will be passed to a result response.
>     * All receiver functions will have a context map as its second argument. It'd contain a signal context specified in a Phrag config together with a DB connection and an incoming HTTP request. 

Here's some examples:

```clojure
;; Restrict access to request user
(defn- end-user-access [sql-args ctx]
  (let [user (user-info (:request ctx))]
    (if (admin-user? user))
      sql-args
      (update sql-args :where conj [:= :id (:user-id user)])))

;; Removes :internal-id for non-admin users
(defn- hide-internal-id [result ctx]
  (let [user (user-info (:request ctx))]
    (if (admin-user? user))
      result
      (update result :internal-id "")))

;; Updates owner data with a user ID from authenticated info in a request
(defn- update-owner [sql-args ctx]
  (let [user (user-info (:request ctx))]
    (if (end-user? user)
      (update sql-args :created_by (:user-id user))
      (sql-args))))
        
(def example-config {:signals {:users {:query {:pre end-user-access :post hide-internal-id}
                                       :create {:pre update-owner}
                                       :update {:pre update-owner}}}})
```

### Resource Filtering

Format of `where: {column-a: {operator: value} column-b: {operator: value}}` is used in arguments for filtering. `AND` / `OR` group can be created as clause lists in `and` / `or` parameter under `where`.

> * Supported operators are `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in` and `like`.
> * Multiple filters are applied with `AND` operator.

##### Example:

`{users (where: {name: {like: "%ken%"} and: [{id: {gt: 100}}, {id: {lte: 200}}]})}` (`users` where `name` is `like` `ken` `AND` `id` is greater than `100` `AND` less than or equal to `200`)

### Resource Sorting

Format of `sort: {[column]: [asc or desc]}` is used in query arguments for sorting.

##### Example:

`sort: {id: asc}` (sort by `id` column in ascending order)

### Resource Pagination

Formats of `limit: [count]` and `offset: [count]` are used in query arguments for pagination. 

>* `limit` and `offset` can be used independently.
>* Using `offset` can return different results when new entries are created while items are sorted by newest first. So using `limit` with `id` filter or `created_at` filter is often considered more consistent.

##### Example

`(where: {id: {gt: 20}} limit: 25)` (25 items after/greater than `id`:`20`).

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
lein test
```

## Legal

Copyright © 2021 Yohei Kusakabe
