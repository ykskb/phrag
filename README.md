# Phrag

DB Schema Data to GraphQL

Phrag creates an instantly-operational GraphQL route from DB schema data.

#### Features:

* Creates a [ring](https://github.com/ring-clojure/ring) route powered with GraphQL query and mutation capabilities as per schema data.

* Supports nested resource structures for `one-to-one`, `one-to-many` and `many-to-many` relationships on top of `root` entities.

* Data loader (query batching) wired up to avoid N+1 problem for nested queries.

* [Signal dispatcher](#signals) to add custom logics before & after DB accesses per resource operations.

* [Filtering](#resource-filtering), [sorting](#resource-sorting) and [pagination](#resource-pagination) come out of the box.

* DB schema data can be retrieved from a running DB or specified with a config map selectively.

* Automatic router wiring supported for [reitit](https://github.com/metosin/reitit) and [bidi](https://github.com/juxt/bidi).

* GraphQL IDE (like GraphiQL) connectable.

#### Notes:

* This project is currently in POC/brush-up stage in a real project usage. It hasn't been published to Clojars yet.

### Schema Data to Nested Resource Structures

### Usage

##### reitit

```clojure
;; Read schema data from DB (as data for Integrant)
{:phrag.core/reitit-graphql-route {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}}

;; Provide schema data (direct function call)
(def routes (phrag.core/make-reitit-graphql-route {:tables [{:name "..."}]}))
```

##### bidi

```clojure
;; Read schema data from DB (as data for Integrant)
{:phrag.core/bidi-graphql-route {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}}

;; Provide schema data (direct function call)
(def routes (phrag.core/make-bidi-graphql-route {:tables [{:name "..."}]}))
```

##### Notes:

When reading schema data from DB connection, Phrag leverages naming patterns of tables/columns to identify relationships:

> Table names can be specified in the [config map](#phrag-config) for other naming patterns. (In this case, Phrag will not attempt retrieving schema data from DB.)

1. `Root` or `N-to-N` relationship?

	A table name without `_` would be classified as `Root`, and a table name pattern of `resourcea_resourceb` (like `members_groups`) is assumed for `N-to-N` tables. 

2. `1-to-1`/`1-to-N` relationship?

	If a table is not `N-to-N` and contains a column ending with `_id`, `1-to-1`/`1-to-N` relationship is identified per column.

### Phrag Config

Though configurable parameters vary by router types, Phrag doesn't require many config values in general. Some key concepts & list of parameters are as below:

#### Config Parameters

| Key                     | Description                                                                                                         | Default Value      |
|-------------------------|---------------------------------------------------------------------------------------------------------------------|--------------------|
| `:db`                   | Database connection object.                                                                                         |                    |
| `:table-name-plural`    | `true` if tables uses plural naming like `users` instead of `user`.                                                 | `true`             |
| `:resource-path-plural` | `true` if plural is desired for URL paths like `/users` instead of `/user`.                                         | `true`             |
| `:tables`               | DB schema including list of table definitions. Plz check [Schema Data](#schema-data) for details.                   | Created from `:db` |
| `:signals`              | Map of singal functions per resources. Plz check [Signal Functions](#signal-functions) for details.                 |                    |
| `:signal-ctx`           | Additional context to be passed into signal functions. Plz check [Signal Functions](#signal-functions) for details. |                    |

#### Schema Data

Schema data is used to specify custom table schema to construct GraphQL without querying a DB. It is specified with a list of tables under `:tables` key in the config map.

```edn
{:tables [
   {:name "users"
    :columns [{:name "id"
       	       :type "text"
               :notnull 0
               :dflt_value nil}
              {:name "image_id"
               :type "int"
               :notnull 1
               :dflt_value 1}
	           ;; ... more columns
	           ]
    :relation-types [:root :one-n]
    :belongs-to ["image"]
    ;; ... more tables
    ]}
```

##### Table Data Details:

| Key              	     | Description                                                                                                       |
|------------------------|-------------------------------------------------------------------------------------------------------------------|
| `:name`              	 | Table name.                                                                                                       |
| `:columns`             | List of columns. A column can contain `:name`, `:type`, `:notnull` and `:dflt_value` parameters.                  |
| `:relation-types`      | List of table relation types. `:root`, `:one-n` and `:n-n` are supported.                                         |
| `:belongs-to`          | List of columns related to `id` of other tables. (`:table-name-plural` will format them accordingly.)             |

### Signals

Phrag has a signal dispatcher with receiver functions which are configurable per resource actions at pre/post-DB operation time. This is where things like access controls or custom business logics can be injected.

> Notes:
> * Resource operations types include `query`, `create`, `update` and `delete`.
> * A pre-operation function will have SQL arguments as its first argument when called, and its returned value will be passed to subsequent DB operation.
> * A pre-operation function for `query` will have `where` clause lists in HoneySQL format while ones for `mutations` will receive request parameters.
> * A post-operation function will have a resolved result as its first argument when called, and its returned value will be passed to a result response.
> * Both receiver functions will have a context map as its second argument. It'd contain a signal context specified in Phrag config as well as a DB connection and an HTTP request. 

Here's an example:

```clojure
;; Restrict access to request user
(defn- end-user-access [sql-args ctx]
  (let [user (user-info (:request ctx))]
    (if (if (admin-user? user))
        sql-args
        (update sql-args :where conj [:= :id (:user-id user)]))))

;; Removes :internal-id for non-admin users
(defn- hide-internal-id [result ctx]
  (let [user (user-info (:request ctx))]
    (if (if (admin-user? user))
        result
        (update result :internal-id ""))))

;; Updates owner data with a user ID from authenticated info in a request
(defn update-owner [sql-args ctx]
  (let [user (user-info (:request ctx))]
    (if (end-user? user)
        (update sql-args :created_by (:user-id user))
        (sql-args))))
        
{:users {:query {:pre end-user-access :post hide-internal-id}
         :create {:pre update-owner}
         :update {:pre update-owner}}}
```

### Resource Filtering

Format of `where: {column-a: {operator: value} column-b: {operator: value}}` is used in arguments for filtering. `AND` / `OR` group can be created as clause lists in `and` / `or` parameter under `where`.

##### Example:

`{users (where: {name: {like: "%ken%"} and: [{id: {gt: 100}}, {id: {lte: 200}}]})}` (`users` where `name` is `like` `ken` `AND` `id` is greater than `100` `AND` less than or equal to `200`)

> * Supported operators are `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in` and `like`.
> * Multiple filters are applied with `AND` operator.

### Resource Sorting

Format of `sort: {[column]: [asc or desc]}` is used in query arguments for sorting.

##### Example:

`sort: {id: asc}` (sort by `id` column in ascending order)

### Resource Pagination

Formats of `limit: [count]` and `offset: [count]` are used in query arguments for pagination. 

##### Example

`(filter: {id: {operator: gt value: 20}} limit: 25)` (20 items after/greater than `id`:`20`).

>* `limit` and `offset` can be used independently.
>* Using `offset` can return different results when new entries are created while items are sorted by newest first. So using `limit` with `id` filter or `created_at` filter is often considered more consistent.

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
