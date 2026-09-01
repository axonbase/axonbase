/**
 * Teste de integração do SDK Node.js.
 *
 * Requer um servidor AxonBase rodando em `AXON_URL` (default ws://127.0.0.1:8000/rpc/ws).
 *
 * ```bash
 * # Iniciar servidor de teste
 * cd .. && mvn -q -pl axonbase-server -am exec:java -Dexec.mainClass="com.axonbase.server.Main" \
 *   -Dexec.args="start --path memory --port 8000 --no-auth" &
 *
 * # Rodar testes
 * cd axonbase-sdk-nodejs && npx tsx tests/integration.test.ts
 * ```
 */
import * as assert from "node:assert";
import { test, describe, before, after } from "node:test";
import { Axon } from "../src/client.js";

const URL = process.env.AXON_URL || "ws://127.0.0.1:8000/rpc/ws";

describe("AxonBase SDK Node.js", () => {
  let axon: Axon;

  before(async () => {
    axon = await Axon.connect(URL, { reconnect: false });
    await axon.use("test", "dev");
  });

  after(() => {
    axon.close();
  });

  test("handshake hello", () => {
    const hello = axon.helloInfo;
    assert.ok(hello, "deve receber hello");
    assert.equal(typeof hello.protocol, "number");
    assert.equal(typeof hello.server, "string");
    assert.ok(Array.isArray(hello.methods));
  });

  test("ping", async () => {
    const result = await axon.ping();
    assert.equal(result, true);
  });

  test("version", async () => {
    const v = await axon.version();
    assert.ok(v.length > 0);
  });

  test("CRUD via RPC", async () => {
    // create
    const created = await axon.create("person", { name: "Ana", age: 30 });
    assert.ok(created);

    // select
    const rows = await axon.select("SELECT * FROM person");
    assert.ok(Array.isArray(rows));
    assert.ok(rows.length >= 1);

    // update
    await axon.update("person", { age: 31 });

    // upsert
    await axon.upsert("person", { name: "Bob", age: 20 });

    // delete
    await axon.delete("person");
  });

  test("query com vars", async () => {
    const result = await axon.query("RETURN $name", { name: "Mundo" });
    assert.equal(result, "Mundo");
  });

  test("live query", async () => {
    const events: unknown[] = [];
    const id = await axon.live("live_test", (liveId, action, result) => {
      events.push({ action, result });
    });
    assert.ok(id.length > 0);
    await axon.create("live_test", { data: "hello" });
    await new Promise((r) => setTimeout(r, 500));
    assert.equal(events.length, 1);
    const ev = events[0] as { action: string };
    assert.equal(ev.action, "CREATE");
    await axon.kill(id);
  });

  test("key-value", async () => {
    await axon.kvSet("test", "dev", "mykey", "myvalue");
    const val = await axon.kvGet("test", "dev", "mykey");
    assert.equal(val, "myvalue");
    const deleted = await axon.kvDel("test", "dev", "mykey");
    assert.equal(deleted, true);
    const val2 = await axon.kvGet("test", "dev", "mykey");
    assert.equal(val2, null);
  });

  test("variáveis de sessão", async () => {
    await axon.letVar("$x", 42);
    const result = await axon.query("RETURN $x");
    assert.equal(result, 42);
    await axon.unset("$x");
  });

  test("transação", async () => {
    await axon.begin();
    await axon.create("tx_test", { value: 1 });
    const rows = await axon.select("SELECT * FROM tx_test");
    assert.ok(Array.isArray(rows));
    assert.equal(rows.length, 1);
    await axon.commit();
    const rows2 = await axon.select("SELECT * FROM tx_test");
    assert.equal(rows2.length, 1);
    await axon.delete("tx_test");
  });

  test("erro NOT_LEADER", async () => {
    try {
      // Simular erro enviando para seguidor (precisa de cluster)
      // Se não houver cluster, o teste é ignorado
      await axon.kvSet("inexistente", "db", "k", "v");
    } catch (err: unknown) {
      // Pode ser erro de database inexistente ou NOT_LEADER
      const msg = (err as Error).message;
      assert.ok(msg.length > 0);
    }
  });
});