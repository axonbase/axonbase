"""Programmatic AxonBase migration API."""

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Awaitable, Callable

from ..client import Axon

MigrationHook = Callable[[str, str], Awaitable[None]]


@dataclass(frozen=True)
class Migration:
    version: str
    name: str
    path: Path
    checksum: str
    sql: str


class Migrator:
    """Applies .axql migrations sequentially with state tracking.

    Usage::

        migrator = Migrator(axon)
        await migrator.up("migrations")
    """

    def __init__(self, axon: Axon) -> None:
        self.axon = axon
        self._table = "_migration"
        self._before: MigrationHook | None = None
        self._after: MigrationHook | None = None

    def on_before(self, hook: MigrationHook) -> "Migrator":
        self._before = hook
        return self

    def on_after(self, hook: MigrationHook) -> "Migrator":
        self._after = hook
        return self

    async def ensure_table(self) -> None:
        await self.axon.query(
            "DEFINE TABLE _migration SCHEMAFULL;"
            "DEFINE FIELD version ON TABLE _migration TYPE string;"
            "DEFINE FIELD applied_at ON TABLE _migration TYPE datetime;"
            "DEFINE FIELD checksum ON TABLE _migration TYPE string;"
            "DEFINE FIELD name ON TABLE _migration TYPE string;"
        )

    async def applied(self) -> dict[str, str]:
        rows = await self.axon.query(
            "SELECT version, checksum FROM _migration ORDER BY version ASC;"
        )
        if not isinstance(rows, list):
            return {}
        return {
            str(row["version"]): str(row["checksum"])
            for row in rows if isinstance(row, dict)
            and row.get("version") is not None and row.get("checksum") is not None
        }

    def load(self, path: str | Path) -> list[Migration]:
        directory = Path(path)
        if not directory.is_dir():
            raise FileNotFoundError(f"Migration directory not found: {directory}")
        files = sorted(directory.glob("*.axql"))
        migrations: list[Migration] = []
        for file in files:
            version = file.stem.split("_", 1)[0] if "_" in file.stem else file.stem
            content = file.read_text().strip()
            migrations.append(Migration(
                version=version,
                name=file.name[: -len(".axql")] if file.name.endswith(".axql") else file.name,
                path=file,
                checksum=hashlib.sha256(file.read_bytes()).hexdigest(),
                sql=content,
            ))
        return migrations

    async def up(self, path: str | Path) -> list[Migration]:
        migrations = self.load(path)
        applied = await self.applied()
        for m in migrations:
            if m.version in applied and applied[m.version] != m.checksum:
                raise RuntimeError(
                    f"Migration {m.name} checksum changed "
                    f"(was {applied[m.version][:12]}...)"
                )
        pending = [m for m in migrations if m.version not in applied]
        if not pending:
            return []
        await self.ensure_table()
        for migration in pending:
            if self._before:
                await self._before(migration.version, migration.name)
            await self._apply_one(migration)
            if self._after:
                await self._after(migration.version, migration.name)
        return pending

    async def status(self, path: str | Path) -> list[tuple["Migration", bool]]:
        migrations = self.load(path)
        applied = await self.applied()
        return [(m, m.version in applied) for m in migrations]

    async def _apply_one(self, migration: Migration) -> None:
        if not migration.sql:
            return
        await self.axon.query(migration.sql)
        rid = f"v_{migration.version}"
        name = migration.name.replace('"', '\\"')
        checksum = migration.checksum.replace('"', '\\"')
        await self.axon.query(
            f"UPSERT _migration:{rid} CONTENT {{"
            f"version: \"{migration.version}\", name: \"{name}\", "
            f"checksum: \"{checksum}\", applied_at: time::now()"
            f"}}"
        )