# Development

### Test

Run tests with coverage:

```sh
lein cloverage -n 'phrag.*'
```

Tests run on in-memory SQLite DB by default. If environment variables for PostgreSQL DB are provided, GraphQL tests run on both SQlite and PostgreSQL DBs.

Example:

```sh
DB_NAME=my_db DB_HOST=localhost DB_USER=postgres DB_PASS=my_pass lein cloverage -n 'phrag.*'
```
