// Package migration provides migration management for AxonBase databases.
//
// It tracks applied migrations in a `_migration` table with version, name,
// checksum (SHA-256), and applied_at fields.
//
// Usage:
//
//	migrator := migration.NewMigrator(axon, "")
//	if err := migrator.EnsureTable(); err != nil { ... }
//	records, err := migrator.Up("./migrations")
package migration