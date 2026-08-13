# arc-core-sql

`arc-core-sql` is the shared platform-neutral MySQL boundary for ARC,
ProxyARC, and standalone RusCrafting plugins. It deliberately contains no
Paper or Velocity imports.

## Runtime ownership

Create `SqlRuntime` only when the owning plugin's `mysql.enabled` setting is
true. The runtime owns its Hikari pool and bounded JDBC executor and must be
closed during plugin shutdown.

```kotlin
val mysql = SqlModuleConfig.load(dataPath)
val runtime = if (mysql.enabled) {
    SqlRuntime.create(mysql.connection(), "my-plugin")
} else {
    null
}
```

`SqlConnectionConfig.toString()` redacts passwords, and credentials are never
placed in the JDBC URL. Callers must not log raw configuration maps or database
exceptions that can include connection properties.

## Migrations

Each plugin owns its schema and supplies ordered `SqlMigration` values to a
namespace-specific `MySqlMigrator`. The migrator serializes competing server
nodes with `GET_LOCK`, records source checksums, and refuses a modified applied
migration.

MySQL implicitly commits most DDL. Every migration statement must therefore be
idempotent (`IF NOT EXISTS`, guarded data backfill, or an equivalent design) so
a partial server or network failure can be retried safely. Never edit an
already-applied migration; append a new version.

## Threading

JDBC is blocking. Use `SqlExecutor.read`, `write`, or `transaction`; use
`submit` for SQL-adjacent work such as a migrator that owns its own connection.
Never call repository JDBC directly from a Paper, Velocity, or event-loop
thread. Render the result back on the platform scheduler only after the future
completes.
