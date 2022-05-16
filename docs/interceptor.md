# Interceptor Signals

Phrag can signal configured functions per resource queries/mutations at pre/post-operation time. This is where things like access controls or custom business logics can be configured. Signal functions are called with different parameters as below:

### Pre-operation Interceptor Function

| Type   | Signal function receives (as first parameter):                             | Returned value will be:                |
| ------ | -------------------------------------------------------------------------- | -------------------------------------- |
| query  | SQL parameter map: `{:select #{} :where [] :sort [] :offset 0 :limit 100}` | Passed to subsequent query operation.  |
| create | Submitted mutation parameters                                              | Passed to subsequent create operation. |
| update | Submitted mutation parameters                                              | Passed to subsequent update operation. |
| delete | Submitted mutation parameters                                              | Passed to subsequent delete operation. |

> Notes:
>
> - `query` signal functions for matching table will be called in nested queries (relations) as well.
> - `:where` and `:limit` parameter for `query` operation are in [HoneySQL](https://github.com/seancorfield/honeysql) format.

#### Post-operation Interceptor Function

| Type   | Signal function receives (as a first parameter):   | Returned value will be:  |
| ------ | -------------------------------------------------- | ------------------------ |
| query  | Result value(s) returned from query operation.     | Passed to response body. |
| create | Primary key object of created item: e.g. `{:id 3}` | Passed to response body. |
| update | Result object: `{:result true}`                    | Passed to response body. |
| delete | Result object: `{:result true}`                    | Passed to response body. |

#### All Interceptor Functions

All receiver functions will have a context map as its second argument. It'd contain a signal context specified in a Phrag config (`:signal-ctx`) together with a DB connection (`:db`) and an incoming HTTP request (`:req`).

### Examples

```clojure
(defn- end-user-access
  "Users can query only his/her own user info"
  [sql-args ctx]
  (let [user (user-info (:req ctx))]
    (if (admin-user? user))
      sql-args
      (update sql-args :where conj [:= :user_id (:id user)])))

(defn- hide-internal-id
  "Removes internal-id for non-admin users"
  [result ctx]
  (let [user (user-info (:req ctx))]
    (if (admin-user? user))
      result
      (update result :internal-id nil)))

(defn- update-owner
  "Updates created_by with accessing user's id"
  [args ctx]
  (let [user (user-info (:req ctx))]
    (if (end-user? user)
      (assoc args :created_by (:id user))
      args)))

;; Multiple signal function can be specified as a vector.

(def example-config
  {:signals {:all [check-user-auth check-user-role]
             :users {:query {:pre end-user-access
                             :post hide-internal-id}
                     :create {:pre update-owner}
                     :update {:pre update-owner}}}})
```

> Notes:
>
> - `:all` can be used at each level of signal map to run signal functions across all tables, all operations for a table, or both timing for a specific operation.
