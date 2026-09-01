import * as assert from "node:assert";
import { test } from "node:test";
import { SagaTransaction } from "../src/index.js";
import type { Axon } from "../src/client.js";

test("SagaTransaction manages the saga lifecycle and session correlation", async () => {
  const queries: string[] = [];
  const variables: Array<[string, unknown]> = [];
  const client = {
    async query(sql: string): Promise<unknown> {
      queries.push(sql);
      if (sql.startsWith("BEGIN")) return { status: "RUNNING" };
      if (sql.startsWith("COMMIT")) return { status: "COMMITTED" };
      if (sql.startsWith("SHOW")) return { status: "COMMITTED", steps: [] };
      return { updated: 1 };
    },
    async letVar(name: string, value: unknown): Promise<void> {
      variables.push([name, value]);
    },
  } as unknown as Axon;
  const saga = new SagaTransaction(client, "order's", "corr\\id'");

  await assert.rejects(saga.step("UPDATE orders:o1 SET total = 200"), /Saga not begun/);
  await saga.begin();
  assert.equal(saga.isBegun, true);
  assert.deepEqual(await saga.step("UPDATE orders:o1 SET total = 200"), { updated: 1 });
  assert.deepEqual(await saga.describe(), { status: "COMMITTED", steps: [] });
  await saga.commit();
  assert.equal(saga.isFinished, true);
  await assert.rejects(saga.commit(), /Saga already finished/);

  assert.deepEqual(variables, [["saga_corr", "corr\\id'"]]);
  assert.deepEqual(queries, [
    "BEGIN SAGA order''s WITH CORRELATION 'corr\\\\id'''",
    "UPDATE orders:o1 SET total = 200",
    "SHOW SAGA TRANSACTION order''s 'corr\\\\id'''",
    "COMMIT SAGA order''s WITH CORRELATION 'corr\\\\id'''",
  ]);
});

test("SagaTransaction rollback is idempotent", async () => {
  const queries: string[] = [];
  const client = {
    async query(sql: string): Promise<unknown> {
      queries.push(sql);
      return { status: sql.startsWith("BEGIN") ? "RUNNING" : "CANCELLED" };
    },
    async letVar(): Promise<void> {},
  } as unknown as Axon;
  const saga = new SagaTransaction(client, "pedido", "corr-123");

  await saga.rollback();
  await saga.begin();
  await saga.rollback();
  await saga.rollback();

  assert.equal(saga.isFinished, true);
  assert.deepEqual(queries, [
    "BEGIN SAGA pedido WITH CORRELATION 'corr-123'",
    "CANCEL SAGA pedido WITH CORRELATION 'corr-123'",
  ]);
});
