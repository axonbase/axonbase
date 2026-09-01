# AxonBase SDK Node.js/TypeScript

Cliente WebSocket JSON-RPC do AxonBase para Node.js/TypeScript.

## Instalação

```bash
npm install @axonbase/sdk
```

## Uso básico

```ts
import { Axon } from "@axonbase/sdk";

const axon = await Axon.connect("ws://127.0.0.1:8000/rpc/ws");
await axon.use("test", "dev");

// Consulta AxonQL
const rows = await axon.query("SELECT * FROM person WHERE age >= 18");

// CRUD via RPC
await axon.create("person", { name: "Ana", age: 30 });
await axon.update("person", { age: 31 });
await axon.delete("person");

// Live query
const id = await axon.live("person", (liveId, action, result) => {
  console.log(action, result);
});
await axon.kill(id);

// Fechar conexão
axon.close();
```

## Funcionalidades

- Handshake hello com protocolo versionado
- Todos os métodos RPC: `ping`, `use`, `query`, `signin`, `signup`, `authenticate`, `invalidate`, `version`
- CRUD: `select`, `create`, `insert`, `update`, `upsert`, `delete`, `relate`
- Variáveis de sessão: `let`, `unset`
- Transações: `begin`, `commit`, `cancel`
- Key-Value: `kvGet`, `kvSet`, `kvDel`, `kvScan`
- Live queries: `live`, `kill`
- Reconexão automática e tratamento de `NOT_LEADER`
- `vars` tipados (datetime, duration, decimal, bytes, uuid, record, table)

## Configuração

```ts
const axon = new Axon("ws://127.0.0.1:8000/rpc/ws", {
  timeout: 30_000,          // timeout RPC (ms)
  reconnect: true,          // reconectar automaticamente
  reconnectInterval: 1_000, // intervalo entre tentativas
  maxReconnectAttempts: 10, // máximo de tentativas
});
```

## Testes

```bash
# Requer servidor AxonBase rodando
AXON_URL=ws://127.0.0.1:8000/rpc/ws npm test
```