# Sapid

GraphQL / REST APIs from DB Schema Data

Sapid creates instantly-operational GraphQL / REST routes from DB schema data.

#### Features:

* Creates [ring](https://github.com/ring-clojure/ring) routes powered for different routers including [reitit](https://github.com/metosin/reitit), [bidi](https://github.com/juxt/bidi) or [Duct](https://github.com/duct-framework/duct)-[Ataraxy](https://github.com/weavejester/ataraxy).

* Supports nested resource structures for `one-to-one`, `one-to-many` and `many-to-many` relationships on top of `root` entities.

* DB schema data can be retrieved from a running DB or specified with a config map selectively.

* [Filters](#resource-filters), [sorting](#resource-sorting) and [pagination](#resource-pagination) come out of the box for both GraphQL and REST APIs.

* [Swagger UI](https://swagger.io/tools/swagger-ui/) / GraphQL IDE (like GraphiQL) connectable.

#### Notes:

* This project is currently in POC state and hasn't been published to Clojars yet.

### Schema Data to Nested Resource Structures

Here's an example schema showing how Sapid creates endpoints according to four types of relationships: `Root`, `1-to-1`, `1-to-N` and `N-to-N`. (Please refer to [Routes per relationship types](#routes-per-relationship-types) for geneic rules.)

![Image of Schema to APIs](./docs/images/sapid-diagram.png)


### Usage

##### reitit

```clojure
;; Read schema data from DB (as data for Integrant)
{:sapid.core/reitit-routes {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :sapid.core/reitit-routes)}}

;; Provide schema data (direct function call)
(def routes (sapid.core/make-reitit-routes {:tables [{:name "..."}]}))
```

##### bidi

```clojure
;; Read schema data from DB (as data for Integrant)
{:sapid.core/bidi-routes {:db (ig/ref :my-db/connection)}
 ::app {:routes (ig/ref :sapid.core/reitit-routes)}}

;; Provide schema data (direct function call)
(def routes (sapid.core/make-bidi-routes {:tables [{:name "..."}]}))
```


##### Duct Ataraxy

```edn
;; at root/module level of duct config edn
:sapid.core/duct-routes {} 
```

##### Notes:

When reading schema data from DB connection, Sapid leverages naming patterns of tables/columns to identify relationships:

> Table names can be specified in the [config map](#sapid-config) for other naming patterns. (In this case, Sapid will not attempt retrieving schema data from DB.)

1. `Root` or `N-to-N` relationship?

	A table name without `_` would be classified as `Root`, and a table name pattern of `resourcea_resourceb` (like `members_groups`) is assumed for `N-to-N` tables. 

2. `1-to-1`/`1-to-N` relationship?

	If a table is not `N-to-N` and contains a column ending with `_id`, `1-to-1`/`1-to-N` relationship is identified per column.

### Sapid config map

Though configurable parameters vary by router types, Sapid doesn't require many config values in general. Some key concepts & list of parameters are as below:

#### Schema data

Schema data is used to specify custom table schema to construct REST APIs without querying a DB. It is specified with a list of tables under `:tables` key in the config map.

```edn
{:tables [
   {:relation-types [:root :one-n]
    :name "users"
    :columns [{:name "id"
       	       :type "text"}
              {:name "image_id"
               :type "int"}
	       ; ... more columns
	      ]
    :belongs-to ["image"]
    :pre-save-signal #ig/ref :my-project/user-pre-save-fn
    :post-save-signal #ig/ref :my-project/user-post-save-fn}
    ; ... more tables
   ]
   ; ... more parameters
}
```

##### Table details:

| Key              	 | Description                                                                                                       |
|------------------------|-------------------------------------------------------------------------------------------------------------------|
| `:name`              	 | Table name.                                                                                                       |
| `:columns`             | List of columns. A column can contain `:name` and `:type` parameters.                                             |
| `:relation-types`      | List of relation types. `:root`, `:one-n` and `:n-n` are supported.                                               |
| `:belongs-to`          | List of columns related to `id` of other tables. (`:table-name-plural` will format them accordingly.)             |
| `:pre-save-signal`     | A function to be triggered at handler before accessing DB. (It will be triggered with request as a parameter.)    |
| `:post-save-signal`    | A function to be triggered at handler after accessing DB. (It will be triggered with result data as a parameter.) |

#### Config parameter details:

| Key                     | Description                                                                  | Default Value                 |
|-------------------------|------------------------------------------------------------------------------|-------------------------------|
| `:db`                   | Database connection object.                                                  |                               |
| `:table-name-plural`    | `true` if tables uses plural naming like `users` instead of `user`.          | `true`                        |
| `:resource-path-plural` | `true` if plural is desired for URL paths like `/users` instead of `/user`.  | `true`                        |
| `:tables`               | DB schema including list of table definitions.                               | Created from `:db`            |

* Parameters specific to Duct Ataraxy

| Key                     | Description                                                                  | Default Value                 |
|-------------------------|------------------------------------------------------------------------------|-------------------------------|
| `:project-ns`           | Project namespace. It'll be used for route keys.                             | Loaded from `:duct.core`      |
| `:db-config-key`        | Integrant key for a database connection.                                     | `:duct.database/sql`          |
| `:db`                   | Database connection object. If provided Sapid won't init the :db-config-key. | Created from `:db-config-key` |
| `:db-ref`               | Integrant reference to a database connection for REST handler configs.       | Created from `:db-config-key` |
| `:db-keys`              | Keys to get a connection from a database map.                                    | [:spec]                       |

### Resource Filters

##### GraphQL

Format of `filter: {[column]: {operator: [operator], value: [value]}` is used in query arguments for filtering.

###### Example:

`{users (filter: {id: {operator: lt, value: 100} id: {operator: ne, value: 1}})}` (`users` where `id` is less than `100` `AND` `id` is not equal to `1`)

##### REST API

Format of `?column=[operator]:[value]` is used in a query string for filtering.


###### Example:

`?id=lt:100&id=ne:1` (where `id` is less than `100` `AND` `id` is not equal to `1`)

> * Supported operators are `eq`, `ne`, `lt`, `le`/`lte`, `gt`, and `ge`/`gte`.
> * Operators default to `eq` when omitted.
> * Multiple queries are applied with `AND` operator.

### Resource Sorting

##### GraphQL

Format of `sort: {[column]: [asc or desc]}` is used in query arguments for sorting.

###### Example:

`sort: {id: asc}` (sort by `id` column in ascending order)

##### REST API

Format of `?order-by=[column]:[asc or desc]` is used in a query string for sorting.

###### Example:

`?order-by=id:desc` (sort by `id` column in descending order)

> * Direction defaults to `desc` when omitted.

### Resource Pagination

##### GraphQL

Format of `limit: [count]` and `offset: [count]` is used in query arguments for pagination. 

###### Example

`(filter: {id: {operator: gt value: 20}} limit: 25)` (20 items after/greater than `id`:`20`).

##### REST API

Formats of `limit=[count]` and `offset=[count]` are used in a query string for pagination.

###### Example:

`?limit=20&id=gt:20` (20 items after/greater than `id`:`20`.)

>* `limit` and `offset` can be used independently.
>
>* Using `offset` can return different results when new entries are created while items are sorted by newest first. So using `limit` with `id` filter or `created_at` filter is often considered more consistent.



### Routes per relationship types

Generic rules of route creation per relatioship types are as below:

* `Root`

| HTTP methods                               | Routes           |
|--------------------------------------------|------------------|
| `GET`, `POST`                              | `/resource`      |
| `GET`, `DELETE`, `PUT` and `PATCH`         | `/resource/{id}` |

* `1-to-1`/`1-to-N`

| HTTP methods                       | Routes                                                   |
|------------------------------------|----------------------------------------------------------|
| `GET` and `POST`                   | `/parent-resource/{parent-id}/child-resource`            |
| `GET`, `DELETE`, `PUT` and `PATCH` | `/parent-resource/{parent-id}/child-resource/{child-id}` |

* `N-to-N`

| HTTP methods | Routes                                              |
|--------------|-----------------------------------------------------|
| `GET`        | `/resource-a/{id-of-a}/resource-b`                  |
| `GET`        | `/resource-b/{id-of-b}/resource-a`                  |
| `POST`       | `/resource-a/{id-of-a}/resource-b/{id-of-b}/add`    |
| `POST`       | `/resource-b/{id-of-b}/resource-a/{id-of-a}/add`    |
| `POST`       | `/resource-a/{id-of-a}/resource-b/{id-of-b}/delete` |
| `POST`       | `/resource-b/{id-of-b}/resource-a/{id-of-a}/delete` |


### Environment

To begin developing, start with a REPL.

```sh
lein repl
```

Then load the development environment.

```clojure
user=> (dev)
:loaded
```

Run `go` to prep and initiate the system.

```clojure
dev=> (go)
:duct.server.http.jetty/starting-server {:port 3000}
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
