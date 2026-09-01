"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Migrator = void 0;
const node_crypto_1 = require("node:crypto");
const node_fs_1 = require("node:fs");
const node_path_1 = require("node:path");
const DEFAULT_TABLE = "_migration";
const CREATE_TABLE_SQL = `
DEFINE TABLE _migration SCHEMAFULL;
DEFINE FIELD version ON TABLE _migration TYPE string;
DEFINE FIELD name ON TABLE _migration TYPE string;
DEFINE FIELD checksum ON TABLE _migration TYPE string;
DEFINE FIELD applied_at ON TABLE _migration TYPE datetime DEFAULT time::now();
`;
class Migrator {
    axon;
    table;
    beforeHooks = [];
    afterHooks = [];
    constructor(axon, table) {
        this.axon = axon;
        this.table = table ?? DEFAULT_TABLE;
    }
    onBefore(hook) {
        this.beforeHooks.push(hook);
    }
    onAfter(hook) {
        this.afterHooks.push(hook);
    }
    async ensureTable() {
        await this.axon.query(CREATE_TABLE_SQL);
    }
    async applied() {
        const result = await this.axon.query(`SELECT version, name, checksum, applied_at FROM ${this.table} ORDER BY version ASC`);
        return result ?? [];
    }
    load(path) {
        const resolved = (0, node_path_1.resolve)(path);
        const stat = (0, node_fs_1.statSync)(resolved);
        if (stat.isFile()) {
            if (!resolved.endsWith(".axql")) {
                throw new Error(`not an .axql file: ${resolved}`);
            }
            return [parseFile(resolved)];
        }
        const entries = (0, node_fs_1.readdirSync)(resolved)
            .filter((f) => f.endsWith(".axql"))
            .sort();
        return entries.map((f) => parseFile((0, node_path_1.join)(resolved, f)));
    }
    async status(path) {
        const files = this.load(path);
        const applied = await this.applied();
        const appliedVersions = new Set(applied.map((r) => r.version));
        const pending = files.filter((f) => !appliedVersions.has(f.version));
        return { pending, applied };
    }
    async up(path) {
        const { pending } = await this.status(path);
        const completed = [];
        for (const migration of pending) {
            for (const hook of this.beforeHooks) {
                await hook(migration);
            }
            const sql = (0, node_fs_1.readFileSync)(migration.path, "utf-8");
            await this.axon.query(sql);
            const dateStr = new Date().toISOString();
            const result = await this.axon.query(`UPSERT ${this.table}:v_${migration.version} CONTENT { version: $v, name: $n, checksum: $c, applied_at: time::now() }`, {
                v: migration.version,
                n: migration.name,
                c: migration.checksum,
            });
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
exports.Migrator = Migrator;
function parseFile(filePath) {
    const basename = filePath.split("/").pop() ?? filePath;
    const name = basename.endsWith(".axql") ? basename.slice(0, -5) : basename;
    const version = name.includes("_") ? name.substring(0, name.indexOf("_")) : name;
    const content = (0, node_fs_1.readFileSync)(filePath);
    const checksum = (0, node_crypto_1.createHash)("sha256").update(content).digest("hex");
    return { version, name, path: filePath, checksum };
}
