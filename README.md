# Sapid

REST APIs from DB Schema

Sapid configures REST API endpoints from DB schema at app init time, leveraging [Integrant](https://github.com/weavejester/integrant). No run-time overhead :)

* Auto-registers routes & handlers with [Ataraxy](https://github.com/weavejester/ataraxy) in [Duct](https://github.com/duct-framework/duct) from a single line in config. (Currently working on [bidi](https://github.com/juxt/bidi) and [reitit](https://github.com/metosin/reitit).)

* DB schema can be retrieved from a running DB or specified with a config map.

* Query filters, sorting and pagination come out of the box.

Notes:

* This project is currently at work-in-progress state.

* Sapid comes with generalizations which are the trade-offs for instantly working endpoints.

### Schema to REST Endpoints

![Image of Schema to APIs](./docs/images/db-rest-apis-highlighted.png)

We can see three types of relationships in the example above: `Root`, `N-to-N` and `1-to-N`. Sapid configures REST endpoints for each type as below:

* `Root`

| HTTP methods                               | Routes           |
|--------------------------------------------|------------------|
| `GET`, `POST`                              | `/resource`      |
| `GET`, `DELETE`, `PUT` and `PATCH`         | `/resource/{id}` |

* `N-to-1` / `1-to-N`

| HTTP methods                       | Routes                                                   |
|------------------------------------|----------------------------------------------------------|
| `GET` and `POST`                   | `/parent-resource/{parent-id}/child-resource`            |
| `GET`, `DELETE`, `PUT` and `PATCH` | `/parent-resource/{parent-id}/child-resource/{child-id}` |

* `N-to-N`

| HTTP methods | Routes                                              |
|--------------|-----------------------------------------------------|
| `GET`        | `/resource-a/{id-of-a}/resource-b/`                 |
| `GET`        | `/resource-b/{id-of-b}/resource-a/`                 |
| `POST`       | `/resource-a/{id-of-a}/resource-b/{if-of-b}/add`    |
| `POST`       | `/resource-b/{id-of-b}/resource-a/{if-of-a}/add`    |
| `POST`       | `/resource-a/{id-of-a}/resource-b/{if-of-b}/delete` |
| `POST`       | `/resource-b/{id-of-b}/resource-a/{if-of-a}/delete` |

### Usage

##### Schema from DB

Auto-configuration from a running DB follows logics as below:

1. `Root` or `N-to-N` relationship?

	A table name without `_` would be classified as `Root`, and a table name pattern of `resourcea_resourceb` such as `members_groups` is assumed for `N-to-N` tables. 

2. `1-to-N` relationship?

	If a table is not `N-to-N` and contains a column ending with `_id`, `1-to-N` relationship is identified per column.

*If other naming patterns are required, table names can be specified in the [config map](#sapid-config).

###### Examples:

* Ataraxy in Duct

```edn
; at root/module level of duct config
:sapid.core/register {}
```

##### Schema Config Map

When `tables` data is provided in the [config](#sapid-config), Sapid uses it for DB schema instead of retrieving from a datbase.

Please refer to [config section](#sapid-config) for the format of schema data.

###### Examples:

* Ataraxy in Duct

```edn
; at root/module level of duct config
:sapid.core/register {:router "..." :tables: [{:name "..."}]}
```

### Sapid Config

```edn
{:router :ataraxy
 :db-config-key :duct.database/sql
 :db-keys ["db-spec"]
 :table-name-plural true
 :resource-path-plural true
 :tables [
   {:relation-types [:root :one-n]
    :name "users"
    :columns [{:name "id"
       	       :type "text"}
              {:name "image_id"
               :type "int"}]  ; ... more columns
    :belongs-to ["image"]
    :pre-signal #ig/ref :my/pre-signal-fn
    :post-signal #ig/ref :my/post-signal-fn}
    ; ... more tables
   ]}
```

Parameter Details:

| Key                   | Description                                                                | Default Value      |
|-----------------------|----------------------------------------------------------------------------|--------------------|
| :router               | Router type.                                                               | :ataraxy           |
| :db-config-key        | Integrant key for a database.                                              | :duct.database/sql |
| :db-keys              | Keys to get a connection from a database.                                  | ["db-spec"]        |
| :table-name-plural    | True if tables uses plural naming like `users` instead of `user`.          | true               |
| :resource-path-plural | True if plural is desired for URL paths like `/users` instead of `/user`.  | true               |
| :tables               | DB schema including list of table definitions.                             |                    |

Table Parameter Details:

| Key             | Description                                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------------------------------|
| :relation-types | Relation types. `:root`, `:one-n` and `:n-n` are supported.                                                       |
| :name           | Table name.                                                                                                       |
| :columns        | List of columns. A column can contain `:name` and `:type` parameters.                                             |
| :belongs-to     | List of columns related to `id` of other tables. (`:table-name-plural` will format them accordingly.)                |
| :pre-signal     | A function to be triggered at handler before accessing DB. (It will be triggered with request as a parameter.)    |
| :post-signal    | A function to be triggered at handler after accessing DB. (It will be triggered with result data as a parameter.) |

### 

### Setup

When you first clone this repository, run:

```sh
lein duct setup
```

This will create files for local configuration, and prep your system
for the project.

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
