# Plano de implementação do AxonBase

Este documento define o plano de implementação do AxonBase, uma base de dados multimodelo escrita em Java 21+, inspirada na arquitetura do SurrealDB. Ele cobre o núcleo (motor, armazenamento, linguagem), o servidor (HTTP e WebSocket) e os conectores SDK (Software Development Kit). Estabelece o escopo do primeiro produto útil e a ordem das fases para chegar a uma entrega conectável.

## 1. Decisões de arquitetura

### 1.1 Protocolo próprio

O AxonBase fala um protocolo de conexão próprio, não o do SurrealDB. Isso mantém a implementação limpa (sem restrições de licença BSL (Business Source License)), permite SDKs próprios sob licença Apache ou MIT, e deixa liberdade de evolução. Não há compatibilidade nem com o SurrealDB nem com os SDKs dele.

### 1.2 Separação em módulos

Espelho a separação de crates do SurrealDB (core, server, sdk) sobre o equivalente em Java com Maven:

| Módulo | Pacote raiz | Papel |
|---|---|---|
| `axonbase-common` | `com.axonbase.common` | Utilidades, erros, controle de tempo, observabilidade mínima |
| `axonbase-value` | `com.axonbase.value` | Modelo de valores (AxonValue), codecs JSON (JavaScript Object Notation), ordem total |
| `axonbase-parser` | `com.axonbase.parser` | Gramática da AxonQL (recursive descent), AST (abstract syntax tree) |
| `axonbase-core` | `com.axonbase.core` | Motor: armazenamento, catálogo, transações, executor, índices, funções |
| `axonbase-server` | `com.axonbase.server` | Servidor HTTP + WebSocket (WS), CLI, JWT (JSON Web Token), autenticação |
| `axonbase-sdk-java` | `com.axonbase.sdk` | SDK Java de cliente, conector de referência do protocolo |

Os conectores de Node.js, Rust, Go e .NET serão repositórios ou módulos separados, na fase posterior à estabilização do protocolo em Java.

### 1.3 Armazenamento pluggable (interface KvBackend)

Como o SurrealDB constrói sobre a interface `Transactable`, defino `KvBackend` com as operações mínimas: `get`, `set`, `put` (só insere), `del`, `cas` (compare-and-set), `scan` por intervalo, `commit`, `cancel`, `rollback` e savepoints. Para a primeira entrega, duas implementações:

- `Memory` (memória, pura, sem dependência): snapshot isolation por copy-on-write do mapa transacional.
- `FileBackend` (persistente em arquivo, snapshot JSON sin WAL): opción para o MVP; implementada xa na fase 2.
- **`RocksDbBackend`** (binding JNI (Java Native Interface)): **implementado e padrão desde a Etapa F9**. Transações otimistas, snapshot isolation, LSM-tree com compactação leveled e compressão ZSTD, bloom filters, performance sustentada de **243K inserts/s** em batch de 50K. Suporta centenas de milhões de registros com heap mínimo.
- `WalBackend` (WAL + snapshot) disponível como alternativa legacy.

A interface é pluggable; qualquer implementación de `KvBackend` pode ser usada sem alterar o motor.

---

## 2. Linguagem de consulta: AxonQL

Linguagem tipo SQL (Structured Query Language) com capacidades de documento e grafo, própria do AxonBase. O processador é o ponto mais extenso do projeto, então na primeira entrega cubro um subconjunto bem executado, não a superfície completa.

### 2.1 Subconjunto da primeira entrega

`USE`, `LET`/`SET`, `CREATE`, `INSERT` (into), `UPDATE`, `UPSERT`, `MERGE`, `PATCH`, `DELETE`, `SELECT` (com WHERE, ORDER, LIMIT, START, FETCH, fields, `ONLY`), `RELATE`, `DEFINE TABLE` (SCHEMAFULL e SCHEMALESS), `DEFINE FIELD`, `DEFINE INDEX` (UNIQUE), `DEFINE EVENT` (básico), `INFO`, `RETURN`, `IF`/`ELSE`, `ERROR`, `KILL`.

Adiado: grafos por travessia (`->`, `<-`), live queries, changefeeds, índices vetorial e full-text, geo, função de scripting, controle de acesso por linha (`PERMISSIONS`), transações multi-hop explícitas, `PLAN` e `REFERENCE`.

### 2.2 Tipos de valor (modelo AxonValue)

`none`, `null`, `bool`, `number` (int, float, decimal), `string`, `duration`, `datetime`, `uuid`, `array`, `set`, `object`, `bytes`, `table`, `record` (RecordId `nome:id`), `geometry`, `regex`, `range` e `file`. Cada tipo define `equals` e `compareTo` para uma ordem total (necessária para intervalos e sort). Serializado para JSON no protocolo (número para number, decimal para string, datetime para RFC3339, uuid com hífen, record para `nome:chave`, geometry para GeoJSON).

### 2.3 Lexing e parsing

- Lexer feito à mão (definição de tokens e cerca de 300 keywords insensíveis a maiúsculas, separadores `{}[]();,` `->` `<-` `<->`, `:=`, literais: string `"`, identificadores `` ` `` e `⟨⟩`, record-strings, datetime `d"…"`, duration `1y 2w 3d`).
- Parser recursive descent com 2 a 3 tokens de lookahead e backtracking mínimo, em estilo Pratt para precedência (igual à tabela de binding powers do SurrealDB). Produz nós com span para erros com origem no código-fonte.
- AST canónico: os nós son records Java imutables e tipados (decisión pragmática sobre a arena de `int` ids do SurrealDB, para simplificar o executor da fase 2); os strings de identidade interna quedan internados onde sexa necesario.

---

## 3. Catálogo e esquemas

Igual ao SurrealDB, o catálogo são dados no KV (key-value), não um arquivo separado. Na primeira entrega:

- `NamespaceDefinition`, `DatabaseDefinition`, `TableDefinition` (SCHEMAFULL e SCHEMALESS, drop), `FieldDefinition` (type, assert, readonly), `IndexDefinition` (UNIQUE e COUNT), `EventDefinition`, `UserDefinition`, `ParamDefinition` e `SequenceDefinition`.
- Chaves com prefixo: `!ns`, `!ns*!db`, `!ns*!db*!table`, `!ns*!db*!table!fd{name}`, `!ns*!db*!table!ix{name}` e `!ns*!db*!table!ev{name}`. Chaves de record: `*{ns}*{db}*{tbl}*{id}`.

Uma tabela é um intervalo de chaves. A identidade de cada record é a chave de armazenamento; no decode, o campo `"id"` entra no objeto.

---

## 4. Motor de execução e transações

### 4.1 Pipeline de consulta

1. `Datastore.execute(sql, session, vars)` converte a AxonQL em AST.
2. `Executor` visita o AST e produz um stream de linhas (`Iterator`).
3. Cada linha vira um `Document` copy-on-write com o par `initial`/`current`. Toda a fase posterior fica portada por `is_modified()`.

### 4.2 Ordem do Document (CREATE, UPSERT, UPDATE, DELETE)

`check permissions` → `compute_input_data` (SET, MERGE, PATCH, CONTENT) → `process_record_data` → `create_record_id` → `check_field_type` → `process_table_fields` (TYPE, READONLY, ASSERT) → `store_record_data` (KV) → `store_index_data` (índice único UPDATE vs INSERT) → `process_record_references` (edges de `RELATE`) → `process_events` → `output` (RETURN).

### 4.3 Transação

- `TransactionType::Read/Write`, `LockType::Optimistic` por padrão.
- Cada backend dá snapshot isolation. Os writes ficam em buffer, o `commit` faz flush incremental; o `cancel` faz rollback; há savepoints.
- Retorno otimista: dois writes concorrentes na mesma chave geram `TransactionConflict` (código de erro dedicado `wsc-32009`).

### 4.4 Funções

- Na primeira entrega: subconjunto de funções de datetime (`time::now()`), string, math e record/id fixes.
- `DEFINE FUNCTION` fica adiado para a fase 2 (motor de scripting).

---

## 5. Protocolo de conexão (AxonRPC)

Formato canônico: JSON-RPC (JSON Remote Procedure Call) sobre WebSocket, mais JSON-RPC sobre HTTP (`/rpc`), mais endpoints REST (REpresentational State Transfer) `/sql` e `/table` para conveniência. Só JSON na primeira entrega; CBOR e FlatBuffers ficam para fases posteriores.

### 5.1 Envelope de request (JSON)

```
{ "id": <marcador>, "method": "<método>", "params": [...], "session": "<uuid>", "txn": "<uuid>", "version": 1 }
```

Resposta:
```
{ "id": <mesmo>, "session": "<uuid>", "result": <value> | "error": {"code": ..., "message": ..., "kind": ..., "details": ...} }
```

Os códigos de erro seguem a convenção JSON-RPC: `-32700` parse, `-32600` invalid request, `-32601` method not found, `-32602` método não permitido, `-32603` invalid params, `-32000` internal, `-32002` invalid auth, `-32003` query error e `-32009` txn conflict.

### 5.2 Métodos RPC da primeira entrega

`ping`, `use`, `set`/`let`, `unset`, `signin`, `signup` (opcional), `authenticate`, `invalidate`, `query`, `select`, `create`, `insert`, `update`, `upsert`, `merge`, `patch`, `delete`, `relate`, `version`, `begin`, `commit`, `cancel`, `run` (funções) e `live` (fase 2).

No lado HTTP: `live`, `begin`, `commit` e `cancel` indisponíveis (requests sem estado; sessões efêmeras reproduzíveis).

### 5.3 Autenticação

- `signin` envia credenciais `{ user, pass }` root, `{ ns, user, pass }` namespace, `{ ns, db, user, pass }` database ou `{ ns, db, ac, ... }` access.
- Devolve um JWT com assinatura HS256, com as claims `id`, `ns`, `db`, `exp`, `nbf`, `iat` e `jti`.
- O REST usa `Authorization: Basic` ou `Bearer` e os headers `ns`, `db`, `auth-ns` e `auth-db`.

---

## 6. Servidor

- CLI Java (`axonbase start [...]`): flags `--bind`/`-b`, `--username`/`--user`, `--password`/`--pass`, `--log`, `--path` (memory ou path do RocksDB) e endpoints `/version`, `/health`, `/ready`, `/status`, `/rpc`, `/query`, `/table/*`, `/signin`, `/signup`, `/import` e `/export`.
- HTTP e WebSocket com Jetty (uma dependência): HTTP/1.1 e upgrades WS. `Content-Type: application/json`, e o WS faz upgrade com `Sec-WebSocket-Protocol: json` (padrão).
- Observabilidade mínima: logs (texto e JSON), métricas básicas e `X-Request-Id`.

---

## 7. SDKs de conectores

Ordem de construção, validando sempre o protocolo contra o servidor real:

1. **Java (`axonbase-sdk-java`)**, referência: `Axon.connect("ws://…")`, `signin`, `use`, `query`, `create`/`select`/`update`/`delete`, `begin`/`commit`/`cancel` e métodos preparados (fases 1-2).
2. **Node.js (`axonbase-js`)** após o protocolo estável em Java.
3. **Rust, Go e C#** em fases sucessivas, reutilizando uma especificação de protocolo (OpenAPI + JSON-RPC) como documento de referência.

---

## 8. Fases e entregas

### Fase 0: Fundação (semanas 1-2)
- Repo Maven multi-módulo `axonbase-common` e `axonbase-value`, `.gitignore`, `README`.
- `AxonValue` + codec JSON + ordem total + teste unitário.
- Testes JUnit configurados e CI.

### Fase 1: Parser AxonQL (semanas 4-6)
- Lexer, parser recursive descent, AST (subconjunto da entrega), render canónico e testes por arquivos `.axonql`.
- Validar precedência, associatividade, literais e record ids.

### Fase 2: Núcleo do motor (semanas 4-6) ✅
- Interface `KvBackend` + `MemoryBackend` (snapshot CoW) + `FileBackend` (persistencia simple por snapshot JSON); WalBackend e RocksDbBackend implementados posteriormente.
- `Datastore` (namespaces/DBs/catálogo), `Executor` (interpreta AST), `Document` CoW (initial/current), catálogo, `DEFINE`s e índices UNIQUE e COUNT.
- Executar subconjunto AxonQL (CREATE/INSERT/UPDATE/DELETE/SELECT/RELATE/DEFINE/INFO/IF), persistencia e tests unit/integración. ✅

### Fase 3: Servidor + wire (semanas 3-4) ✅
- Jetty HTTP/1.1, JSON-RPC (`/rpc`), `/sql` e `/table` REST, auth com JWT HS256 e CLI `start` (endpoints de health e version, `/signin`). ✅

### Fase 4: SDK Java + WebSocket + reforço (semanas 3-4) ✅
- Servidor: transporte WebSocket do JSON-RPC no endpoint `/rpc/ws` (reutilizando o `RpcDispatcher`), ademais do HTTP. ✅
- `axonbase-sdk-java` sobre WS JSON-RPC (cliente WebSocket de Jetty): `connect`, `use`, `query`, `version`, `create`, `select`, `update`, `delete`, con request/response síncrono e erros tipados. Testes end-to-end contra o servidor real. ✅
- Ergonomia e seguridade: erros tipados (`AxonSdkException`), reconexión fica para evolução; nunca logar credenciais.

### Fase 5: Estabilização + docs (semanas 2-3) ✅
- Ampliar cobertura de testes: round-trip de INSERT, RELATE, DEFINE FIELD/INDEX/EVENT, IF/ELSE, CAST, RETURN; execução com INSERT, START/LIMIT, ONLY, RETURN NONE. ✅
- Documentação: `USAGE.md` (guia de uso CLI/HTTP/SDK) e `CONNECTOR.md` (especificação do wire protocol para os futuros conectores Node.js/Rust/Go/C#). ✅
- Definition of Done do primeiro produto útil validado: servidor (`run.sh start`) + CRUD via HTTP e SDK por WebSocket, JWT em `signin`. ✅

**Entrega primeiro produto útil verde (Definition of Done):**
- `start --path memory` + `curl /sql` executa `SELECT/CREATE/DEFINE`.
- `axonbase-sdk-java` executa create, read, update, delete contra o servidor, com signin JWT.
- Conjunto de testes de linguagem e SDK verde; `mvn test` sem warnings.

---

## 9. Riscos e mitigações

- **RocksDB JNI**: fallback total para `Memory` na primeira entrega; RocksDB, opcional, isolado pela interface pluggable. **Implementado na Etapa F9 e tornado padrão.**
- **Índice colunar**: implementado como novo tipo de índice (CREATE COLUMNAR INDEX), com codificação sortable de valores, varredura por prefixo para agregacão direta de count/sum/avg/min/max sem carregar documentos.
- **Escopo do AxonQL**: reduzido a um subconjunto sólido; a superfície completa será desbravada em fases com corpus de testes.
- **Compatibilidade entre SDKs**: o protocolo JSON-RPC é estável e simples; documento-o em `CONNECTOR.md` para que Node, Rust, Go e C# o implementem.
- **Complexidade de transações**: a primeira entrega usa snapshot otimista; os conflitos mapeiam com o código definido `wsc-32009`.

---

## 10. Próximos passos

1. Aprovação deste plano.
2. Criar a estrutura Maven e `axonbase-common`/`axonbase-value` (fase 0).
3. Implementar por fases; ao final de cada uma, revisão e commit.

As fases 0 a 5 estão concluídas no primeiro ciclo. Próximas evoluções naturais, fora do primeiro produto útil:

- Conectores de Node.js, Rust, Go e C#, seguindo `CONNECTOR.md`.
- Live queries e changefeeds (transporte de streaming no WebSocket).
- Índices vetorial e full-text, geo e grafos por travessia (`->`, `<-`).
- Autenticação com `signin` por RPC (usuário root/namespace/DB) e controle de acesso por linha.

---

## 11. Etapa A: endurecer o núcleo (segundo ciclo) ✅

Vista de robustez real sobre o primeiro produto útil, aliñada co que SurrealDB ten basal.

---

## 18. F1 a F8 — resumo de funcionalidades concluídas

Tudo o que era roadmap original (Etapas B a E) está implementado. A documentação em `CONNECTOR.md` e `FEATURES.md` reflete o estado real.

| Frente | Funcionalidades |
|---|---|
| **F1 — Protocolo** | `PROTOCOL_VERSION=1`, handshake `hello` no WebSocket, 27 métodos RPC, vars tipados (`$datetime`, `$duration`, `$decimal`, `$bytes`, `$record`, `$uuid`, `$table`) |
| **F2 — Transações** | `SAVEPOINT`/`RELEASE`/`ROLLBACK TO`, phantom isolation com `readRanges`, retry otimista (`Datastore.withRetry`), `VersionConflictException` |
| **F3 — EXPLAIN** | `EXPLAIN`/`EXPLAIN ANALYZE`, planner com estratégias `UNIQUE_LOOKUP`, `FULLTEXT`, `VECTOR`, `GEO`, `HYBRID`, `FULL_SCAN` |
| **F4 — Busca** | BM25 real, busca híbrida (full-text + vetorial), HNSW multicamada com M/EFC/EFS, GeoHash hierárquico, geometrias não pontuais (`geometry::line`, `geometry::polygon`) |
| **F5a — KV público** | `kv_get`/`kv_set`/`kv_del`/`kv_scan` como RPC, funções `kv::*` na AxonQL |
| **F5b — Schema** | `DEFINE FIELD ... REFERENCES` com validação de chave estrangeira, `JOIN` básico com bucle anidado |
| **F6 — Cluster** | Raft TCP, quorum, eleição, failover, catch-up, membership dinâmica (`addPeer`/`removePeer` via joint consensus), snapshot/backup/restore, compactação e fsync do log Raft, shard map por hash consistente |
| **F7 — Servidor** | Métricas Prometheus reais, timeouts configuráveis (`AXON_QUERY_TIMEOUT`, `AXON_TXN_TIMEOUT`, `AXON_SHUTDOWN_TIMEOUT`), rate limiting, CORS, TLS/mTLS, logging estruturado (JSON), shutdown gracioso, pool Jetty configurável |
| **F8 — SDK Node.js** | `axonbase-sdk-nodejs/` com cliente WebSocket, types, live queries, vars tipados, handshake hello |
| **F9 — RocksDB** | `RocksDbBackend` implementando `VersionedKvBackend`, LSM-tree com compactação leveled + ZSTD, bloom filters, `WriteBatch` atómico para OCC, 222K rec/s bulk insert, 174 MB para 1M registros, **padrão na inicialização** |
| **F10 — Índice Colunar** | `CREATE INDEX ... COLUMNS ... COLUMNAR`, codificação sortable de valores (`CI|{ns}|{db}|{table}|{index}|{col}|{type}{data}{rowKey}`), varredura por prefixo para count/sum/avg/min/max, detecção automática de igualdade e agregação no planner |
| **Cluster** | Docker Compose de três nós (`docker-compose.cluster.yml`) validado |
| **F0 — Correções** | Codec unicode corrigido, decimal serializado como string, bytes como base64, erros RPC tipados (`AxonSdkException`), segurança no export (sem hashes de senha), MVCC (`VersionedKvBackend`), concorrência Raft (`synchronized`/versões), mapas seguros (`ConcurrentHashMap`) |

Testes totais: **265 métodos `@Test`** em 49 classes. `mvn -o test` verde.

### 11.1 Storage durable (A1)
- **`Transaction`**: vista `KvBackend` read-through que bufferiza writes; snapshot consistente dentro da transação; `commit`/`cancel`. Integrada na sesão e no executor.
- **`WalBackend`**: WAL (Write-Ahead Logging) append-only con opcodes PUT/DEL, fsync en `flush`, snapshot compacta ao superar 1 MiB e crash recovery (replay + truncate). Substitúe ao `FileBackend` de snapshot JSON como opción durable.

### 11.2 Transações no wire (A3)
- `BEGIN`/`COMMIT`/`CANCEL` con lóxica real no `RpcDispatcher` (reutilizando `Datastore.beginSession/commitSession/cancelSession`), antes eram no-ops no switch.
- Test end-to-end por WebSocket: outra conexión non ve cambios non commiteados até `COMMIT`.

### 11.3 Auth real (A2)
- `UserStore` (hash SHA-256 con salt) e `AuthService` (signin + verify JWT).
- Métodos RPC `signin` (devolve JWT) e `authenticate` (valida e vincula a sessão).
- No WebSocket com `requireAuth`, uma query sem autenticação devolve o erro `-32003 usuário não autenticado`; credenciais inválidas `-32002`.
- SDK `Axon.signin(user,pass)` e `Axon.authenticate(token)`.

### 11.4 PERMISSIONS FOR (A4)
- Parse e render de `PERMISSIONS FOR select/create/update/delete WHERE <expr>` en `DEFINE TABLE`.
- Motor: filtro por fila en `SELECT` e cheques en `CREATE`/`UPDATE`/`DELETE`.
- Test: `DEFINE TABLE doc ... PERMISSIONS FOR select WHERE published = true` filtra correctamente.

### 11.5 Config e observabilidade (A5)
- `Main` lê env `AXON_*` (port/path/user/pass/secret/bind) como defaults.
- Endpoint `/metrics` estilo Prometheus (requests, ws_open, uptime).
- `Dockerfile` multi-stage (build → JRE) con exec via classpath.

Suite actual: **63 tests verdes** (`mvn test`). Validação real: `/health`, `signin` con `-32002` para credenciaes mal, JWT ok, `/metrics`.

---

## 12. Comparação SurrealDB × AxonBase e próximo roadmap

| Capacidade | SurrealDB | AxonBase hoje |
|---|---|---|
| Modelo de dados | documento, grafo, relacional, time-series, geo, KV | documento/tabela, grafo, KV público (`kv_get`/`kv_set`/`kv_del`/`kv_scan` + funções `kv::*`), GeoJSON (`geometry::point/line/polygon`) |
| Query language | SurrealQL completo (grafo `->`, subquery, agregação, ~300 funções) | AxonQL compacto: subqueries, `SELECT VALUE`, aliases, travessia `->`/`<-`, agregações, >130 funções, `EXPLAIN`/`EXPLAIN ANALYZE`, `SAVEPOINT`/`RELEASE`/`ROLLBACK TO`, `JOIN` básico, `DEFINE FIELD ... REFERENCES` |
| Transações | ACID snapshot isolation, optimistic, retry, savepoints | MVCC local com VersionedKvBackend, read set, phantom isolation (`readRanges`), savepoints, retry otimista (`Datastore.withRetry`), conflito por versão (`VersionConflictException`), commit condicional atômico |
| Storage | memory, RocksDB, SurrealKV (MVCC), TiKV distribuído | MemoryBackend + RocksDbBackend (LSM-tree, ZSTD, bloom filters, **padrão**) + WalBackend (WAL+snapshot+fsync+crash recovery) + VersionedKvBackend (MVCC) |
| Índices | unique, count, full-text, vetorial (HNSW/DiskANN) | unique, count, full-text com analyzer e BM25 real, vetorial HNSW multicamada com M/EFC/EFS configuráveis, geo com GeoHash hierárquico, **colunar (sortable encoding, agregacão direta)** |
| Realtime | live queries, changefeeds, subscriptions | `LIVE SELECT`, `DIFF`, `KILL`, eventos de tabela (`DEFINE EVENT` dispara nas mutações), notificações WebSocket (`notification` frame), retenção em transação |
| Auth | root/ns/db/access, JWT, `PERMISSIONS` por linha | usuários e access methods ROOT/NAMESPACE/DATABASE, JWT com escopo, `PERMISSIONS FOR`, rate limiting (AXON_RATE_LIMIT), TLS/mTLS, CORS |
| Wire | WS JSON-RPC + HTTP + REST + GraphQL + GQL + MCP | WS + HTTP JSON-RPC + REST `/sql` `/table`, handshake `hello` com PROTOCOL_VERSION=1, 27 métodos RPC, vars tipados (`$datetime`, `$duration`, `$decimal`, `$bytes`, `$record`, `$uuid`, `$table`) |
| SDKs | Rust, JS/Node/Deno, Python, Go, .NET, PHP, Java | Java (conector de referência) + Node.js/TypeScript (`axonbase-sdk-nodejs/`) |
| Extras | export/import, Docker, WASM, scripting JS, ML | export/import (`/export` e `/import`), `/metrics` Prometheus, Dockerfile, `axonbase.conf` + env `AXON_*`, Docker Compose de cluster 3 nós |
| Cluster | Produção distribuída (TiKV/Raft) | Raft TCP embutido, quorum, eleição, failover, catch-up, membership dinâmica (`/admin/cluster/join|leave`), snapshot/backup/restore (`/admin/cluster/snapshot|backup|restore`), compactação e fsync do log Raft, shard map por hash consistente, joint consensus simplificado |

O roadmap original (Etapas B a F) está integralmente implementado; ver seção 18 para o resumo de cada frente.

---

## 13. Etapa B: maturidade da AxonQL e do motor ✅

### 13.1 Grafo real (B1)
- `RELATE` persiste arestas no KV com chave direcional dupla (`E|ns|db|out|fromTbl|fromKey|kind|toTbl|toKey` e a inversa `in`).
- Travessia `->`/`<-`/`<->` em idioms (inclusive como primeiro termo do SELECT), filtrando por tipo.
- Teste: `RELATE user:1->wrote->article:1` e `SELECT ->wrote->article FROM user:1` devolvem o vértice de destino.

### 13.2 Funções e agregações (B2)
- Agregações `count`/`sum`/`avg`/`min`/`max` avaliadas no motor (em field ou com `GROUP BY`).
- `GROUP BY` parseado e renderizado; agrupamento por valor das expressões.
- Novas keywords `count`, `sum`, `avg`, `min`, `max`, `group` no lexer; keywords de função tratadas como `Call`.
- Testes: `SELECT count()` e `SELECT sum(valor) FROM venda GROUP BY cidade` (SP=17, RJ=5).

### 13.3 Schema enforçado (B3)
- `DEFINE FIELD TYPE/ASSERT/DEFAULT`: coerção de tipo (int/float/number/string/bool/datetime/array/object), validação de `ASSERT` (com `$value`) e aplicação de `DEFAULT` ao criar.
- Aplicado em `CREATE` e reflexo no `UPDATE`.
- Testes: rejeição de tipo inválido, falha de assert (`CONTAINS "@"`), default aplicado.

### 13.4 Export/import e UPSERT (B4)
- `Datastore.exportDatabase/importDatabase`: dump AxonQL com DEFINEs (incluindo ASSERT/DEFAULT) e `CREATE <tabela>:<chave> CONTENT <json>` por registro, restaurando o id original via chave física.
- Endpoints `/export` (GET) e `/import` (POST) no servidor.
- `UPSERT` insere quando nenhum registro é atualizado.
- Testes: round-trip export/import em datastore novo, UPSERT.

Suite atual da Etapa B: 73 testes verdes.

## 14. Etapa C: tempo real (live queries e eventos) ✅

### 14.1 Sintaxe LIVE SELECT (C1)
- `Statement.Live(select, diff)` no AST, `parseLive()` no parser e render com round-trip exato.
- Keywords novas no lexer: `live` e `diff`.
- `LIVE SELECT * FROM person`, `LIVE SELECT * FROM person WHERE age > 18` e `LIVE SELECT * FROM person DIFF`.

### 14.2 Barramento de notificações (C2)
- `LiveBus` no motor: regista subscriptions por namespace, banco de dados e tabela, guardando o SELECT original e a sessão dona.
- `Executor.runLive` devolve o identificador da live query (UUID em texto); `KILL "<id>"` cancela e devolve booleano.
- `CREATE`, `UPDATE` e `DELETE` publicam a mudança: a ação vai como `CREATE`, `UPDATE` ou `DELETE`.
- O `WHERE` da live query filtra as notificações e a projeção de campos é respeitada.
- Modo `DIFF`: o resultado é uma lista de operações no estilo JSON Patch (`add`, `replace`, `remove`).
- Com transação aberta, as notificações ficam retidas na sessão e só saem no `COMMIT`; o `CANCEL` descarta-as.

### 14.3 Eventos de tabela (C3)
- `DEFINE EVENT ... WHEN ... THEN ...` passa a guardar a condição e as sentenças no catálogo, e dispara de facto nas mutações.
- Variáveis expostas ao evento: `$event`, `$before`, `$after` e `$value`, restauradas depois da execução.

### 14.4 Tempo real no wire (C4)
- Métodos RPC novos: `live` (parâmetros `[tabela, diff]`) e `kill` (parâmetro `[id]`).
- O WebSocket empurra as mudanças em frames `{"notification": {"id", "action", "result"}}`, distintos das respostas de RPC.
- Ao fechar a conexão, as live queries da sessão são canceladas e a transação pendente é abortada.
- SDK Java: `live(tabela, handler)`, `live(tabela, diff, handler)` e `kill(id)`, com buffer para notificações que chegam antes do registro do handler.

### 14.5 Correções expostas pelos testes de tempo real
- `CREATE pessoa:chave` passa a respeitar a chave indicada no alvo (antes gerava sempre um UUID e o registro ficava inalcançável por id).
- Chaves de aresta passam a usar a forma canónica (`n1`, `sana`), igual à do armazenamento dos registros, o que faz a travessia de grafo funcionar de verdade.
- Travessia com base sintética (`->wrote->article` como primeiro termo do SELECT) resolve o registro corrente, e o nome do campo projetado passa a ser a própria travessia.

Suite atual: **86 testes verdes** (`mvn test`, BUILD SUCCESS).

## 15. Consolidação antes da Etapa D ✅

### 15.1 Subqueries e composição de SELECT
- Subquery em expressão, filtro e `FROM`: `(SELECT ...)`, `SELECT VALUE`, aliases com `AS` e origem derivada.
- Subquery correlacionada recebe o documento externo em `$parent`.
- Operadores de coleção: `IN`, `NOT IN`, `CONTAINS`, `NOT CONTAINS` e variantes já presentes no AST.

### 15.2 Biblioteca de funções
- Mais de 130 funções built-in, distribuídas entre `string`, `math`, `array`, `object`, `type`, `record`, `time`, `rand`, `crypto`, `encoding` e `value`.
- Funções avaliadas no contexto de uma linha do SELECT, por exemplo `string::uppercase(name)`.
- `INFO FOR ROOT` lista as funções disponíveis para conectores e ferramentas.

### 15.3 Metadados com INFO
- `INFO FOR ROOT` expõe namespaces, bancos, funções, usuários e access methods sem hashes de senha.
- `INFO FOR NAMESPACE`, `INFO FOR DATABASE` e `INFO FOR TABLE` descrevem catálogo, schema, campos, índices e eventos.

### 15.4 Identidade e escopos
- `DEFINE USER <nome> ON ROOT|NAMESPACE|DATABASE PASSWORD <senha> ROLES ...` cria identidade com hash salgado no catálogo do datastore.
- `DEFINE ACCESS <nome> ON ROOT|NAMESPACE|DATABASE` registra um access method nomeado.
- `signin` emite JWT (JSON Web Token) com escopo efetivo; `authenticate` e `use` rejeitam namespace ou banco fora desse escopo.

### 15.5 Configuração por arquivo
- `axonbase.conf` usa linhas `chave = valor`; `axonbase.conf.example` documenta as opções.
- Ordem de precedência: valores padrão, arquivo, ambiente `AXON_*`, flags da CLI (Command-Line Interface).
- Configura `path`, `bind`, `port`, `require_auth`, `secret`, `user` e `pass`.

Suite atual: **99 testes verdes** (`mvn -o test`, BUILD SUCCESS).

## 16. Etapa D: busca avançada (em progresso)

### 16.1 Busca full-text (D1) ✅
- `DEFINE ANALYZER` suporta `LOWERCASE`, `STOPWORDS` e stemming leve.
- `DEFINE INDEX ... SEARCH ANALYZER ...` cria e atualiza um índice invertido persistido no backend.
- `campo @@ "termos"` intersecta os termos pelo índice; `search::score()` e `search::highlight(campo)` funcionam na projeção.

### 16.2 Geometrias e vetores (D2/D3) ✅
- `geometry::point`, `geometry::line` e `geometry::polygon` produzem GeoJSON (Geographic JSON).
- `geo::distance`, `geo::area` e `geo::contains`; `DEFINE INDEX ... GEO` mantém uma grade espacial e pré-filtra consultas de raio.
- Tipos de schema `geometry` e `vector`; distância e similaridade por `vector::*`.
- `DEFINE INDEX ... HNSW DIMENSION n DIST ...` mantém uma camada navegável de vizinhos no KV (Key-Value) e obtém candidatos para `ORDER BY vector::distance::* ... LIMIT k`.

Suite da Etapa D: **106 testes verdes** (`mvn -o test`, BUILD SUCCESS).

## 17. Etapa E: distribuição ✅

### 17.1 MVCC local e WAL transacional ✅
- `VersionedKvBackend` introduz versões por chave e commit condicional atômico.
- `Transaction` mantém read set, cache de leitura repetível e valida o snapshot no commit.
- `MemoryBackend` sincroniza operações, copia bytes defensivamente e aplica batches após validar todas as precondições.
- `WalBackend` grava batches em um frame recuperável; o replay aplica o batch inteiro ou o descarta quando estiver truncado.

### 17.2 Raft, cluster e sharding ✅
- Grupo Raft por database com eleição, quórum, catch-up e snapshots.
- Roteamento ao líder via `ClusterStatusProvider` com erro `NOT_LEADER`.
- Membership dinâmica: `addPeer`/`removePeer` via joint consensus simplificado.
- Shard map por hash consistente de `(namespace, database)`.
- Compactação e fsync do log Raft (`FileRaftLog`).
- Docker Compose de três nós validado.
- Endpoints administrativos `/admin/cluster/snapshot|backup|restore|join|leave`.

Suite atual: **265 testes** (`@Test` anotados) — `mvn -o test` BUILD SUCCESS.
