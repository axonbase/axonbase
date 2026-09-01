import * as assert from "node:assert";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { createServer } from "node:https";
import { test } from "node:test";
import { WebSocketServer } from "ws";
import { Axon } from "../src/client.js";
import type { CertificateCompletion } from "../src/protocol.js";

test("certificate methods send protocol-compatible requests", async () => {
  const server = new WebSocketServer({ port: 0 });
  await once(server, "listening");
  const { port } = server.address() as { port: number };
  const requests: Array<{ method: string; params: unknown[] }> = [];

  server.on("connection", (socket) => {
    socket.send(JSON.stringify({ hello: { protocol: 1, server: "test", methods: [] } }));
    socket.on("message", (data) => {
      const request = JSON.parse(data.toString()) as { id: number; method: string; params: unknown[] };
      requests.push(request);
      const result = request.method === "certificate.begin"
        ? { id: "challenge-id", challenge: "nonce", expires_at: "2026-01-01T00:00:00Z" }
        : "temporary-credential";
      socket.send(JSON.stringify({ id: request.id, result }));
    });
  });

  const axon = await Axon.connect(`ws://127.0.0.1:${port}`, { reconnect: false });
  const completion: CertificateCompletion = {
    id: "challenge-id",
    challenge: "nonce",
    store: "icp-brasil",
    user: "12345678901",
    chain: ["base64-der-certificate"],
    signature: "base64-signature",
  };

  try {
    assert.deepEqual(await axon.certificateBegin("icp-brasil"), {
      id: "challenge-id",
      challenge: "nonce",
      expires_at: "2026-01-01T00:00:00Z",
    });
    assert.equal(await axon.certificateComplete(completion), "temporary-credential");
    assert.deepEqual(requests.map(({ method, params }) => ({ method, params })), [
      { method: "certificate.begin", params: [{ store: "icp-brasil" }] },
      { method: "certificate.complete", params: [completion] },
    ]);
  } finally {
    axon.close();
    server.close();
    await once(server, "close");
  }
});

test("TLS client options support mTLS connections", async () => {
  const ca = await readFile(new URL("../../certs/ca/dev-ca.crt", import.meta.url));
  const httpsServer = createServer({
    cert: await readFile(new URL("../../certs/server/server.crt", import.meta.url)),
    key: await readFile(new URL("../../certs/server/server.key", import.meta.url)),
    ca,
    requestCert: true,
    rejectUnauthorized: true,
  });
  const server = new WebSocketServer({ server: httpsServer });
  httpsServer.listen(0, "127.0.0.1");
  await once(httpsServer, "listening");
  const { port } = httpsServer.address() as { port: number };
  let clientAuthorized = false;

  server.on("connection", (socket, request) => {
    clientAuthorized = request.socket.authorized;
    socket.send(JSON.stringify({ hello: { protocol: 1, server: "test", methods: [] } }));
  });

  const axon = await Axon.connect(`wss://localhost:${port}`, {
    reconnect: false,
    tls: {
      ca,
      cert: await readFile(new URL("../../certs/client-demo/client-demo.crt", import.meta.url)),
      key: await readFile(new URL("../../certs/client-demo/client-demo.key", import.meta.url)),
    },
  });

  try {
    assert.equal(clientAuthorized, true);
  } finally {
    axon.close();
    server.close();
    httpsServer.close();
    await once(httpsServer, "close");
  }
});
