# duct-db-rest

REST APIs generated from DB (before server starts)

This repository is proof-of-concept project to generate REST APIs from DB, levaraging the data-driven & super-modular [Integrant](https://github.com/weavejester/integrant) in [Duct](https://github.com/duct-framework/duct).

It reads table schema from DB, creates routes & handlers based on a set of rules and registers them before starting the server (thus no runtime overhead.)

### Supported Types

* Root-level

* `1-to-1` / `1-to-N` relation

* `N-to-N` relation

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
