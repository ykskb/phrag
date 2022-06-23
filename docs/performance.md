# Performance

## Load Test

Load test repository: [phrag-perf](https://github.com/ykskb/phrag-perf)

> Note:
> This documentation page is mainly about Phrag's GraphQL `query` since there are several varying factors for measuring its performnace. On the other hand, Phrag's `mutations` are atomic operations and simpler to be estimated, so its result is put as a [reference data](#reference-data) below.

### Objectives

Load tests were performed to:

- Get some benchmarks for simple resource setups.
  (Stringent VM/GC/OS/DB tuning is not in the scope.)

- Verify there's no obvious bottleneck and performance improves more or less linear with additional resources.

- Compare performance of different resolver models: subquery model vs query-per-nest model vs bucket queue model.

### Measurement

While each user is constantly sending a request every `2s`, how many users can Phrag serve within `500ms` ?

- Duration: `60s` with 2 stages:
  - `30s` of ramping up to a target number of users.
  - `30s` of staying at a target number of users.
- Metrics used is `http_req_duration` for `p95`.
- HTTP error & response error rate must be less than `1%`.

### Tests

- 3 GraphQL queries with different nest levels were tested to see performance difference of additional data retrieval.

- Each test was performed with `limit: 50` and `limit: 100` to see performance difference of serialization workload.

- All tables had roughly `100,000` records created through GraphQL mutation calls.

- Parameters of `$offset` and `$id_gt` were randomized between `0` to `100,000` for each request.

Query without nest: simple object listing

```graphql
query queryVenues($limit: Int!, $offset: Int!, $id_gt: Int!) {
  venues(limit: $limit, offset: $offset, where: { id: { gt: $id_gt } }) {
    id
    name
    postal_code
  }
}
```

Query with 1 nest: `has-many`

```graphql
query queryVenueMeetups($limit: Int!, $offset: Int!, $id_gt: Int!) {
  venues(limit: $limit, offset: $offset, where: { id: { gt: $id_gt } }) {
    id
    name
    postal_code
    meetups(limit: $limit, sort: { id: desc }) {
      id
      title
    }
  }
}
```

Query with 2 nests: `has-many` and `has-one` (often referred as `many-to-many`)

```graphql
query queryMeetupsWithMembers($limit: Int!, $offset: Int!, $id_gt: Int!) {
  meetups(limit: $limit, offset: $offset, where: { id: { gt: $id_gt } }) {
    id
    title
    meetups_members(limit: $limit) {
      member {
        id
        email
      }
    }
  }
}
```

### Setup

#### Application

- Server: Jetty (Ring)

- Router: reitit

#### Computation / storage resources

- Platform: AWS ECS Single Task Container
  - `1vCPU + 4GB RAM`
  - `2vCPU + 8GB RAM`
- Database: AWS RDS PostgreSQL
  - `2vCPU + 1GB RAM` (Free-tier of `db.t3.micro`)

\*Both resources were set up in a single availability zone.

#### Request Client

Home computer setup with Mac Studio was used to send requests from the same region as application servers.

### Results

#### `1vCPU + 4GB RAM`

Limit: `50`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------- | ------ | ----- | --- | ----- | ----- |
| No nest | 1300    | 442    | 427ms | 7ms | 845ms | 27510 |
| 1 nest  | 800     | 273    | 406ms | 7ms | 889ms | 17003 |
| 2 nests | 700     | 240    | 427ms | 9ms | 991ms | 15068 |

Limit: `100`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------- | ------ | ----- | --- | ----- | ----- |
| No nest | 900     | 316    | 308ms | 7ms | 781ms | 19643 |
| 1 nest  | 400     | 144    | 204ms | 8ms | 474ms | 8918  |
| 2 nests | 400     | 143    | 219ms | 9ms | 685ms | 8908  |

#### `2vCPU + 8GB RAM`

Limit: `50`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------- | ------ | ----- | --- | ----- | ----- |
| No nest | 2500    | 824    | 454ms | 6ms | 843ms | 51352 |
| 1 nest  | 1400    | 490    | 355ms | 7ms | 837ms | 30427 |
| 2 nests | 1300    | 451    | 354ms | 9ms | 926ms | 28053 |

Limit: `100`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------- | ------ | ----- | --- | ----- | ----- |
| No nest | 1800    | 600    | 444ms | 6ms | 943ms | 37364 |
| 1 nest  | 1000    | 331    | 499ms | 8ms | 893ms | 20610 |
| 2 nests | 700     | 240    | 383ms | 9ms | 874ms | 14919 |

### Observations

#### Resource allocation

Performance seems to have improved roughly linear with the additional resource allocation overall. Improvement seems slightly more significant for nested queries.

#### Nest levels

Considering additional subqueries and serialization required, `30%` to `40%` less performance per a nest level seems sensible. It was also observed that querying nested objects for `has-many` relationship affected performance more than `has-one` relationship, which possibly indicates serialization and validation of retrieved records is the factor for more latency.

#### Resolver Models

As explained in [mechanism](./mechanism.md), Phrag translates nested GraphQL queries into a single SQL, leveraging correlated subqueries and JSON functions. This model was compared against other possible models as below:

- **Bucket Queue Model**: bucket queue model with [Superlifter](https://github.com/oliyh/superlifter) in this [branch](https://github.com/ykskb/phrag/tree/superlifter-version) was tested for comparison. The idea is to use buckets for firing batched SQL queries per a nest level. Though results are not included in this page, a model of resolving nested data by directly going through a query graph was more performant. Adding queues and resolving them through Promise (CompletableFuture) seemed to have some overhead.

- **SQL-per-nest Model**: a model of issueing a DB query per a nest level in this [branch](https://github.com/ykskb/phrag/tree/sql-per-nest-version) was actually the original idea for Phrag's resolver. Though this model fires DB queries more frequently, it was observed nearly as performant as the subquery model and it had less load on DB's CPU by `20%` to `30%`. However, subquery model was chosen over this model since the performance of SQL-per-nest model can be affected by DB connection overhead, depending on environment setups. Also, subquery model still performed slightly better even though it had higher load on DB's CPU. It's a trade-off of performing subqueries & JSON serialization on DB side instead of application side. Performance measured for SQL-per-nest model can be found in [reference data](#sql-per-nest-model) below.

### Reference Data

#### Mutations

`Create` mutation was measured as below:

##### `1vCPU + 4GB RAM`

| MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------ | ----- | --- | ----- | ----- |
| 1500    | 926    | 394ms | 7ms | 667ms | 56689 |

#### SQL-per-nest Model

##### `1vCPU + 4GB RAM`

Limit: `50`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max   | Reqs  |
| ------- | ------- | ------ | ----- | --- | ----- | ----- |
| No nest | 1300    | 442    | 427ms | 7ms | 845ms | 27510 |
| 1 nest  | 700     | 237    | 461ms | 9ms | 860ms | 14750 |
| 2 nests | 500     | 175    | 326ms | 8ms | 758ms | 10919 |

Limit: `100`

| Query   | MAX VUs | Reqs/s | p(95) | Min  | Max   | Reqs  |
| ------- | ------- | ------ | ----- | ---- | ----- | ----- |
| No nest | 900     | 313    | 395ms | 8ms  | 895ms | 19515 |
| 1 nest  | 400     | 143    | 257ms | 10ms | 703ms | 8872  |
| 2 nests | 300     | 106    | 274ms | 10ms | 599ms | 6602  |

##### `2vCPU + 8GB RAM`

Limit: `50`

| Query   | MAX VUs | Reqs/s | p(95) | Min | Max    | Reqs  |
| ------- | ------- | ------ | ----- | --- | ------ | ----- |
| No nest | 1900    | 662    | 353ms | 6ms | 697ms  | 41174 |
| 1 nest  | 1400    | 477    | 365ms | 7ms | 721ms  | 29668 |
| 2 nests | 1200    | 402    | 447ms | 8ms | 1069ms | 25088 |

Limit: `100`

| Query   | MAX VUs | Reqs/s | p(95) | Min  | Max   | Reqs  |
| ------- | ------- | ------ | ----- | ---- | ----- | ----- |
| No nest | 2000    | 669    | 489ms | 7ms  | 846ms | 41693 |
| 1 nest  | 900     | 316    | 291   | 10ms | 663ms | 19695 |
| 2 nests | 700     | 246    | 294ms | 9ms  | 774ms | 14919 |
