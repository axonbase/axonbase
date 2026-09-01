import * as assert from "node:assert";
import { test } from "node:test";
import { SagaParticipantTransaction } from "../src/index.js";
import type { Axon } from "../src/client.js";

test("SagaParticipantTransaction commits only the local transaction", async () => {
  const calls: string[] = [];
  const client = {
    async begin(): Promise<void> { calls.push("begin"); },
    async letVar(name: string, value: unknown): Promise<void> { calls.push(`let:${name}=${value}`); },
    async query(sql: string): Promise<unknown> { calls.push(`query:${sql}`); return { updated: 1 }; },
    async commit(): Promise<void> { calls.push("commit"); },
    async cancel(): Promise<void> { calls.push("cancel"); },
  } as unknown as Axon;
  const participant = new SagaParticipantTransaction(client, "corr-123");

  await assert.rejects(participant.step("UPDATE orders:o1 SET total = 200"), /Transaction not begun/);
  await participant.begin();
  await participant.step("UPDATE orders:o1 SET total = 200");
  await participant.commit();

  assert.deepEqual(calls, [
    "begin",
    "let:saga_corr=corr-123",
    "query:UPDATE orders:o1 SET total = 200",
    "commit",
  ]);
  assert.equal(calls.some((call) => /SAGA/.test(call)), false);
});

test("SagaParticipantTransaction rolls back only the local transaction", async () => {
  const calls: string[] = [];
  const client = {
    async begin(): Promise<void> { calls.push("begin"); },
    async letVar(): Promise<void> { calls.push("let"); },
    async cancel(): Promise<void> { calls.push("cancel"); },
  } as unknown as Axon;
  const participant = new SagaParticipantTransaction(client, "corr-123");

  await participant.begin();
  await participant.rollback();
  await participant.rollback();

  assert.deepEqual(calls, ["begin", "let", "cancel"]);
});
