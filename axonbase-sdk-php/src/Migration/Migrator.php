<?php

declare(strict_types=1);

namespace AxonBase\Migration;

use AxonBase\Axon;
use AxonBase\Error;

final class Migrator
{
    private const DEFAULT_TABLE = '_migration';

    /** @var list<callable(Migration): void> */
    private array $beforeHooks = [];

    /** @var list<callable(Migration): void> */
    private array $afterHooks = [];

    public function __construct(private readonly Axon $axon, private readonly string $table = self::DEFAULT_TABLE) {}

    /** @param callable(Migration): void $hook */
    public function onBefore(callable $hook): self
    {
        $this->beforeHooks[] = $hook;
        return $this;
    }

    /** @param callable(Migration): void $hook */
    public function onAfter(callable $hook): self
    {
        $this->afterHooks[] = $hook;
        return $this;
    }

    public function ensureTable(): void
    {
        $this->axon->query("DEFINE TABLE {$this->table} SCHEMAFULL;"
            . "DEFINE FIELD version ON TABLE {$this->table} TYPE string;"
            . "DEFINE FIELD name ON TABLE {$this->table} TYPE string;"
            . "DEFINE FIELD checksum ON TABLE {$this->table} TYPE string;"
            . "DEFINE FIELD applied_at ON TABLE {$this->table} TYPE datetime DEFAULT time::now();");
    }

    /** @return array<string, string> */
    public function applied(): array
    {
        try {
            $rows = $this->axon->query("SELECT version, checksum FROM {$this->table} ORDER BY version ASC;");
        } catch (Error) {
            return [];
        }

        if (!is_array($rows)) {
            return [];
        }

        $applied = [];
        foreach ($rows as $row) {
            if (is_array($row) && isset($row['version'], $row['checksum'])) {
                $applied[(string) $row['version']] = (string) $row['checksum'];
            }
        }
        return $applied;
    }

    /** @return list<Migration> */
    public function load(string $path): array
    {
        if (is_file($path)) {
            if (!str_ends_with($path, '.axql')) {
                throw new \InvalidArgumentException("not an .axql file: {$path}");
            }
            return [$this->parseFile($path)];
        }
        if (!is_dir($path)) {
            throw new \InvalidArgumentException("migration directory not found: {$path}");
        }

        $paths = glob(rtrim($path, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . '*.axql') ?: [];
        sort($paths, SORT_STRING);
        return array_map($this->parseFile(...), $paths);
    }

    /** @return list<array{migration: Migration, applied: bool}> */
    public function status(string $path): array
    {
        $applied = $this->applied();
        return array_map(
            static fn (Migration $migration): array => ['migration' => $migration, 'applied' => isset($applied[$migration->version])],
            $this->load($path),
        );
    }

    /** @return list<Migration> */
    public function up(string $path): array
    {
        $applied = $this->applied();
        $pending = [];
        foreach ($this->load($path) as $migration) {
            if (isset($applied[$migration->version])) {
                if (!hash_equals($applied[$migration->version], $migration->checksum)) {
                    throw new \RuntimeException("migration checksum changed: {$migration->name}");
                }
                continue;
            }
            $pending[] = $migration;
        }

        if ($pending === []) {
            return [];
        }

        $this->ensureTable();
        foreach ($pending as $migration) {
            foreach ($this->beforeHooks as $hook) {
                $hook($migration);
            }
            if (trim($migration->sql) !== '') {
                $this->axon->query($migration->sql);
            }
            $this->axon->query(
                "UPSERT {$this->table}:v_{$migration->version} CONTENT { version: \$v, name: \$n, checksum: \$c, applied_at: time::now() }",
                ['v' => $migration->version, 'n' => $migration->name, 'c' => $migration->checksum],
            );
            foreach ($this->afterHooks as $hook) {
                $hook($migration);
            }
        }
        return $pending;
    }

    private function parseFile(string $path): Migration
    {
        $name = pathinfo($path, PATHINFO_FILENAME);
        $version = explode('_', $name, 2)[0];
        $sql = file_get_contents($path);
        if ($sql === false) {
            throw new \RuntimeException("failed to read migration: {$path}");
        }
        $checksum = hash_file('sha256', $path);
        if ($checksum === false) {
            throw new \RuntimeException("failed to hash migration: {$path}");
        }
        return new Migration($version, $name, $path, $checksum, $sql);
    }
}
