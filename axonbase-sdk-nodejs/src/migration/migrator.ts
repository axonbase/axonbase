import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { Axon } from "../client.js";

export interface MigrationRecord {
  version: string;
  name: string;
  checksum: string;
  applied_at: string;
}

export interface MigrationFile {
  version: string;
  name: string;
  path: string;
  checksum: string;
}

export type MigrationHook = (migration: MigrationFile) => void | Promise<void>;

const DEFAULT_TABLE = "_migration";

const CREATE_TABLE_SQL = `
DEFINE TABLE _migration SCHEMAFULL;
DEFINE FIELD version ON TABLE _migration TYPE string;
DEFINE FIELD name ON TABLE _migration TYPE string;
DEFINE FIELD checksum ON TABLE _migration TYPE string;
DEFINE FIELD applied_at ON TABLE _migration TYPE datetime DEFAULT time::now();
`;

export class Migrator {
  private axon: Axon;
  private table: string;
  private beforeHooks: MigrationHook[] = [];
  private afterHooks: MigrationHook[] = [];

  constructor(axon: Axon, table?: string) {
    this.axon = axon;
    this.table = table ?? DEFAULT_TABLE;
  }

  onBefore(hook: MigrationHook): void {
    this.beforeHooks.push(hook);
  }

  onAfter(hook: MigrationHook): void {
    this.afterHooks.push(hook);
  }

  async ensureTable(): Promise<void> {
    await this.axon.query(CREATE_TABLE_SQL);
  }

  async applied(): Promise<MigrationRecord[]> {
    const result = await this.axon.query(
      `SELECT version, name, checksum, applied_at FROM ${this.table} ORDER BY version ASC`,
    );
    return (result as MigrationRecord[]) ?? [];
  }

  load(path: string): MigrationFile[] {
    const resolved = resolve(path);
    const stat = statSync(resolved);

    if (stat.isFile()) {
      if (!resolved.endsWith(".axql")) {
        throw new Error(`not an .axql file: ${resolved}`);
      }
      return [parseFile(resolved)];
    }

    const entries = readdirSync(resolved)
      .filter((f) => f.endsWith(".axql"))
      .sort();

    return entries.map((f) => parseFile(join(resolved, f)));
  }

  async status(path: string): Promise<{ pending: MigrationFile[]; applied: MigrationRecord[] }> {
    const files = this.load(path);
    const applied = await this.applied();
    const appliedVersions = new Set(applied.map((r) => r.version));
    const pending = files.filter((f) => !appliedVersions.has(f.version));
    return { pending, applied };
  }

  async up(path: string): Promise<MigrationRecord[]> {
    const { pending } = await this.status(path);
    const completed: MigrationRecord[] = [];

    for (const migration of pending) {
      for (const hook of this.beforeHooks) {
        await hook(migration);
      }

      const sql = readFileSync(migration.path, "utf-8");
      await this.axon.query(sql);

      const dateStr = new Date().toISOString();
      const result = await this.axon.query(
        `UPSERT ${this.table}:v_${migration.version} CONTENT { version: $v, name: $n, checksum: $c, applied_at: time::now() }`,
        {
          v: migration.version,
          n: migration.name,
          c: migration.checksum,
        },
      );

      for (const hook of this.afterHooks) {
        await hook(migration);
      }

      completed.push({
        version: migration.version,
        name: migration.name,
        checksum: migration.checksum,
        applied_at: dateStr,
      });
    }

    return completed;
  }
}

function parseFile(filePath: string): MigrationFile {
  const basename = filePath.split("/").pop() ?? filePath;
  const name = basename.endsWith(".axql") ? basename.slice(0, -5) : basename;
  const version = name.includes("_") ? name.substring(0, name.indexOf("_")) : name;
  const content = readFileSync(filePath);
  const checksum = createHash("sha256").update(content).digest("hex");
  return { version, name, path: filePath, checksum };
}