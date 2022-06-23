# Mechanism

There are several projects out there for GraphQL automation on RDBMS. Among them, Phrag focuses on keeping itself minimal and not-overly-complicated while providing decent CRUD capabilities.

## Database

Phrag creates its GraphQL engine from an existing RDBMS. It does not deal with DB management such as model definitions or migrations.

## Queries

All or selected tables / views become queryable as root objects including nested objects of relationships in Phrag. This is for flexible data accesses without being constrained to certain query structures defined in GraphQL schema. Data can be accessed at the root level or as a nested object together with parent objects through relationships.

In terms of query format, Phrag does not use a [cursor connection](https://relay.dev/graphql/connections.htm). This is an intentional design decision since Phrag features universal argument formats across root level and nested objects for filtering, aggregation and pagination.

### Relationships

Phrag transforms a foreign key constraint into nested query objects of GraphQL as illustrated in the diagram below. This is a fundamental concept for Phrag to support multiple types of relationships:

<img src="./images/fk-transform.png" width="400px" />

### SQL Queries

N+1 problem is an anti-pattern where a relationship query is executed for every one of retrieved records. Phrag's query resolver translates nested query objects into a single SQL query, leveraging lateral join / correlated subqueries with JSON functions.

## Mutations

`Create`, `update` and `delete` mutations are created for each table. Primary keys work as an identitier of each record for mutations:

1. Phrag registers PK(s) of a table as a GraphQL object.

2. `Create` mutation returns a PK object with generated values as a successful response.

3. `Update` or `delete` mutation requires the PK object as a parameter to identify the record for the operations.

## Security

- **Infinite nests:** nested objects created for both origin and destination columns of foreign keys actually mean possible infinite nests, and it is possibly an attack surface when a user queries millions of nests. Phrag has a [config](config.md) value, `max-nest-level` for this, and an error response will be returned when a query exceed the nest level specified.

- **Default limit:** querying millions of records can be resource-intensive and we don't want it to happen accidentally. [Config](config.md) value of `default-limit` can be used to apply default limit value when there's no `limit` parameter specified in a query.
