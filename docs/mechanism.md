## Database Requirements for Phrag

Here's a quick view of database constructs which are important for Phrag. Mechanism is explained [below](#mechanism-of-phrag) for details.

- **Foreign keys**: Phrag translates foreign keys to nested properties in GraphQL objects.

- **Primary keys**: Phrag uses primary keys as identifiers of GraphQL mutations. Composite primary key is supported.

- **Indices on foreign key columns**: Phrag queries a database by both origin and destination columns of foreign keys for nested objects. (Please note simply creating a foreign key DOES NOT index those columns.)

> #### Notes
>
> - Supported databases are SQLite and PostgreSQL.
>
> - In a use case with PostgreSQL, Phrag queries tables such as `key_column_usage` and `constraint_column_usage` to retrieve full FK / PK info. Therefore the database user provided to Phrag needs to be identical to the one that created those keys.
>
> - Not all database column types are mapped to Phrag's GraphQL fields yet. Any help would be appreciated through issues and PRs.

## Mechanism of Phrag

### Queries

All tables become queryable as root objects of GraphQL in Phrag. This is for flexible data access without being constrained to certain query structures defined in GraphQL schema. Data can be accessed at root level or as a nested object together with parent objects through relationships.

### Relationships

Phrag transforms a foreign key constraint into nested query objects of GraphQL as illustrated in the diagram below. This is a fundamental concept for Phrag to support multiple types of relationships:

<img src="./images/fk-transform.png" width="400px" />

### SQL Queries

N+1 problem is an anti-pattern where a relationship query is executed for every one of retrieved records. Phrag's query resolver implements a batched SQL query per nest level to avoid N+1 problem.

## Mutations

`Create`, `update` and `delete` mutations are created for each table. Primary keys work as an identitier of each record for mutations:

1. Phrag registers PK as a GraphQL object.

2. `Create` mutation returns a PK object with generated values as a successful response.

3. `Update` or `delete` mutation requires the PK object as a parameter to identify the record for the operations.
