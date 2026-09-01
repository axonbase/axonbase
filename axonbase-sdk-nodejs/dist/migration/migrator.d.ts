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
export declare class Migrator {
    private axon;
    private table;
    private beforeHooks;
    private afterHooks;
    constructor(axon: Axon, table?: string);
    onBefore(hook: MigrationHook): void;
    onAfter(hook: MigrationHook): void;
    ensureTable(): Promise<void>;
    applied(): Promise<MigrationRecord[]>;
    load(path: string): MigrationFile[];
    status(path: string): Promise<{
        pending: MigrationFile[];
        applied: MigrationRecord[];
    }>;
    up(path: string): Promise<MigrationRecord[]>;
}
