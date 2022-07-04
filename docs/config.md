# Configuration

Though there are multiple options for customization, the only config parameter required for Phrag is a database connection.

### Parameters

| Key                | description                                                                                                                                                                                                                     | Required | Default Value |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------- |
| `:db`              | Database connection (`{:connection object}`) or data source object (`{:datasource object}`). [Hikari-CP datasource](https://github.com/tomekw/hikari-cp) is much more performant than a JDBC connection especially under loads. | Yes      |               |
| `:tables`          | List of custom table definitions. Plz check [Schema Data](#schema-data) for details.                                                                                                                                            | No       |               |
| `:signals`         | Map of singal functions per table, operation and timing. Plz check [Interceptor Signals](interceptor.md) for details.                                                                                                           | No       |               |
| `:signal-ctx`      | Additional context to be passed into signal functions. Plz check [Interceptor Signals](interceptor.md) for details.                                                                                                             | No       |               |
| `:default-limit`   | Default number for SQL `LIMIT` value to be applied when there's no `:limit` argument is specified in a query.                                                                                                                   | No       | `nil`         |
| `:max-nest-level`  | Maximum nest level allowed. This is to avoid infinite nesting. Errors will be returned when nests in requests exceed the value.                                                                                                 | No       | `nil`         |
| `:use-aggregation` | `true` if aggregation is desired on root entity queries and has-many relationships.                                                                                                                                             | No       | `true`        |
| `:scan-tables`     | `true` if DB schema scan is desired for tables in GraphQL.                                                                                                                                                                      | No       | `true`        |
| `:scan-views`      | `true` if DB schema scan is desired for views in GraphQL.                                                                                                                                                                       | No       | `true`        |
| `:graphql-path`    | Path for Phrag's GraphQL when a route is desired through `phrag.route` functions.                                                                                                                                               | No       | `/graphql`    |

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

#### Table Data Details:

| Key        | Description                                                                                      |
| ---------- | ------------------------------------------------------------------------------------------------ |
| `:name`    | Table name.                                                                                      |
| `:columns` | List of columns. A column can contain `:name`, `:type`, `:notnull` and `:dflt_value` parameters. |
| `:fks`     | List of foreign keys. A foreign key can contain `:table`, `:from` and `:to` parameters.          |
| `:pks`     | List of primary keys. A primary key can contain `:name` and `:type` parameters.                  |

> Notes:
>
> - When `:scan-schema` is `false`, Phrag will construct GraphQL from the provided table data only.
> - When `:scan-schema` is `true`, provided table data will override scanned table data per table property: `:name`, `:columns`, `:fks` and `:pks`.
