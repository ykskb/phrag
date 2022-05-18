# Development

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

When you make changes to your source files, use `reset` to reload any modified files and reset the server.

```clojure
dev=> (reset)
:reloading (...)
:resumed
```

### Testing

```clojure
dev=> (test)
```

Coverage

```sh
lein cloverage -n 'phrag.*'
```
