# Performance

## Load Test

Load test repository: [phrag-perf](https://github.com/ykskb/phrag-perf)

### Objectives

Load tests were performed to:

- Get some benchmarks for simple resource setups.
  (Stringent JVM/OS/DB tuning is excluded from the scope.)

- Verify performance improves linear with additional resources, which means there's no obvious bottleneck.

- Compare performance of resolver models between bucket queue model and recursion model.

### Measurement

How many users can be served under request duration of `500ms` while each user is constantly sending a request every `2s`?

- Duration: `60s` with 2 stages:
  - `30s` of ramping up to a target number of users.
  - `30s` of staying at a target number of users.
- Metrics used is `http_req_duration` for `p95`.
- HTTP error & response error rate must be less than `1%`.

### Tests

- 3 queries with different nest levels were tested to see performance difference of additional queries and serialization (1 nest = 1 DB query).

- Each test was performed with `limit: 50` and `limit: 100` to see performance difference of overall serialization workload.

- All tables had `100,000+` pre-generated records as performance tests in small number of records often do not represent performance accurately.

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

### Results

Application setup:

- Server: Jetty (Ring)

- Router: reitit

Computation / storage resources:

- Platform: AWS ECS Single Task Container
- Database: AWS RDS PostgreSQL
  2vCPU + 1GB RAM (Free-tier of `db.t3.micro`)

#### 1vCPU + 4GB RAM

Limit: `50`

| Query   | MAX VUs | p(95) | Min | Max   | Reqs  | Reqs/s |
| ------- | ------- | ----- | --- | ----- | ----- | ------ |
| No nest | 1300    | 427ms | 7ms | 845ms | 27510 | 442    |
| 1 nest  | 700     | 461ms | 9ms | 860ms | 14750 | 237    |
| 2 nests | 500     | 326ms | 8ms | 758ms | 10919 | 175    |

Limit: `100`

| Query   | MAX VUs | p(95) | Min  | Max   | Reqs  | Reqs/s |
| ------- | ------- | ----- | ---- | ----- | ----- | ------ |
| No nest | 900     | 395ms | 8ms  | 895ms | 19515 | 313    |
| 1 nest  | 400     | 257ms | 10ms | 703ms | 8872  | 143    |
| 2 nests | 300     | 274ms | 10ms | 599ms | 6602  | 106    |

#### 2vCPU + 8GB RAM

Limit: `50`

| Query   | MAX VUs | p(95) | Min | Max    | Reqs  | Reqs/s |
| ------- | ------- | ----- | --- | ------ | ----- | ------ |
| No nest | 1900    | 353ms | 6ms | 697ms  | 41174 | 662    |
| 1 nest  | 1400    | 365ms | 7ms | 721ms  | 29668 | 477    |
| 2 nests | 1200    | 447ms | 8ms | 1069ms | 25088 | 402    |

Limit: `100`

| Query   | MAX VUs | p(95) | Min  | Max   | Reqs  | Reqs/s |
| ------- | ------- | ----- | ---- | ----- | ----- | ------ |
| No nest | 2000    | 489ms | 7ms  | 846ms | 41693 | 669    |
| 1 nest  | 900     | 291ms | 10ms | 663ms | 19695 | 316    |
| 2 nests | 700     | 294ms | 9ms  | 774ms | 15312 | 246    |

### Observations

- **Resource allocation**: performance seems to have improved roughly linear with the resource allocation overall and improvement was even more significant for nested queries.

- **Nest levels**: considering additional SQL queries and serialization required per nest level, `20%` to `50%` less performance per nest levels seems reasonable. However, with increased resources, performance drop with nested queries seems to be smaller. It was also observed that querying nested objects for `has-many` relationship affects performance more than `has-one` relationship, which indicates serialization and validation of retrieved records as per GraphQL schema is possibly the factor for larger latency and resource consumption.

- **Resolver model**: bucket queue model with [Superlifter](https://github.com/oliyh/superlifter) vs recursion model were compared for more performant resolver implementation. Though results are not included in this page, recursion model was more performant in a case of Phrag which has simple resolution requirements. The overhead of adding queue and resolving them through Promise (CompletableFuture) seemed to have some overhead.
