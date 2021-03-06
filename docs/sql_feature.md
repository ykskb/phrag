# SQL Features

## Aggregation

`avg`, `count`, `max`, `min` and `sum` are supported and it can also be [filtered](#filtering).

##### Example:

_Select `count` of `cart_items` together with `max`, `min` `sum` and `avg` of `price` where `cart_id` is `1`._

```
cart_items_aggregate (where: {cart_id: {eq: 1}}) {count max {price} min {price} avg {price} sum {price}}
```

## Filtering

Parameters should be placed under query arguments as below:

```
where: {column-a: {operator: value} column-b: {operator: value}}
```

`AND` / `OR` group can be created as clause lists in `and` / `or` parameter under `where`. It is possible to nest them to create sub group of conditions such as `((a=1 AND b=2) OR (a=3 AND b=4))`.

> - Supported operators are `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in` and `like`.
> - Multiple filters are applied with `AND` operator.

##### Example:

_`Users` where `name` is `like` `ken` `AND` `age` is `20` `OR` `21`._

```
{users (where: {name: {like: "%ken%"} or: [{age: {eq: 20}}, {age: {eq: 21}}]})}
```

## Sorting

Parameters should be placed under query arguments as below:

```
sort: {[column]: [asc or desc]}
```

##### Example:

_Sort by `id` column in ascending order._

```
sort: {id: asc}
```

## Pagination

Parameters should be placed under query arguments as below:

```
limit: [count]
offset: [count]
```

> - `limit` and `offset` can be used independently.
> - Using `offset` can return different results when new entries are created while items are sorted by newest first. So using `limit` with `id` filter or `created_at` filter is often considered more consistent.

##### Example:

_25 items after/greater than `id`:`20`_

```
(where: {id: {gt: 20}} limit: 25)
```
