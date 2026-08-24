# Funcionalidades SurrealDB × AxonBase

Tabela comparativa entre o SurrealDB (referência) e o AxonBase (implementação em Java 21+). Marcação de estado: ✅ presente, 🟡 parcial, ⭕ ausente.

## 1. Modelo de dados

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| Documento (objetos aninhados) | ✅ | ✅ | AxonValue object/array |
| Tabelas / schemaless | ✅ | ✅ | SCHEMAFULL e SCHEMALESS |
| Relacional (constraints, schema) | ✅ | ✅ | `DEFINE FIELD TYPE/ASSERT/DEFAULT` com coerção e validação em runtime |
| Grafo (vértices e arestas) | ✅ | ✅ | `RELATE` persiste arestas no KV; travessia `->`/`<-`/`<->` |
| Record links (não JOINs) | ✅ | ✅ | RecordId `tabela:chave`; `FETCH` resolve record links |
| Time-series | ✅ | ⭕ | |
| Geográfico (GeoJSON) | ✅ | ⭕ | |
| Key-value | ✅ | 🟡 | Interface `KvBackend` presente; sem API KV pública |
| Vetorial | ✅ | ⭕ | |

## 2. Linguagem de consulta (SurrealQL × AxonQL)

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| CREATE / INSERT / UPDATE / DELETE | ✅ | ✅ | Com SET, CONTENT, MERGE, PATCH, REPLACE |
| UPSERT | ✅ | ✅ | |
| SELECT com WHERE / ORDER / LIMIT / START / FETCH / ONLY | ✅ | ✅ | START/LIMIT em qualquer ordem |
| RELATE | ✅ | ✅ | Persiste arestas e permite travessia |
| DEFINE TABLE / FIELD / INDEX / EVENT | ✅ | ✅ | PERMISSIONS FOR, TYPE/ASSERT/DEFAULT, EVENT dispara nas mutações |
| INFO | ✅ | ✅ | ROOT, NAMESPACE, DATABASE e TABLE com catálogo estruturado |
| IF / ELSE / END, RETURN, ERROR | ✅ | ✅ | |
| Transações BEGIN / COMMIT / CANCEL | ✅ | ✅ | Por sessão, com WAL + snapshot |
| Agregações + GROUP BY | ✅ | ✅ | count/sum/avg/min/max com GROUP BY |
| Subqueries | ✅ | ✅ | expressão, filtro, origem derivada e correlação com `$parent` |
| funções built-in | ~300 | 🟡 (~130) | string, math, array, object, type, record, time, crypto e mais |
| LIVE SELECT / changefeeds | ✅ | ✅ | `LIVE SELECT ... [WHERE] [DIFF]` e `KILL` |
| Grafos por travessia `->`/`<-`/`<->` | ✅ | ✅ | Persistência de arestas + travessia |

## 3. Transações e armazenamento

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| ACID multi-linha | ✅ | ✅ | snapshot isolation otimista |
| WAL + snapshot + fsync | ✅ | ✅ | WalBackend |
| Crash recovery | ✅ | ✅ | replay do WAL + truncate |
| Savepoints | ✅ | ⭕ | |
| Retry de conflito otimista | ✅ | ⭕ | código `-32009` definido |
| Storage distribuído | ✅ (TiKV/Raft) | ⭕ | Etapa E |
| Índice UNIQUE | ✅ | ✅ | |
| Índice COUNT | ✅ | 🟡 | definido no catálogo |
| Índice full-text | ✅ | ✅ | analyzer, índice invertido, `@@`, score e highlight |
| Índice vetorial (HNSW/DiskANN) | ✅ | ✅ | HNSW em camada única, candidatos e ordenação vetorial |

## 4. Protocolo e servidor

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| JSON-RPC sobre WebSocket | ✅ | ✅ | `/rpc/ws` |
| JSON-RPC sobre HTTP | ✅ | ✅ | `/rpc` |
| REST `/sql` e `/table` | ✅ | ✅ | `/sql`, `/table/*` |
| Auth: JWT signin/authenticate | ✅ | ✅ | usuários e access methods com escopo ROOT/NAMESPACE/DATABASE |
| PERMISSIONS por linha | ✅ | ✅ | PERMISSIONS FOR |
| Live notifications (DIFF/PATCH) | ✅ | ✅ | RPC `live`/`kill` e frames `notification` |
| Métricas (/metrics) | ✅ | ✅ | estilo Prometheus |
| Health/version/ready | ✅ | ✅ | /health, /version |
| GraphQL / GQL / MCP | ✅ | ⭕ | Fora do escopo atual |
| Config por arquivo/env | ✅ | ✅ | `axonbase.conf`, env `AXON_*` e flags da CLI |

## 5. Ecossistema

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| SDK Java | ✅ | ✅ | `axonbase-sdk-java` |
| SDK Node.js / Rust / Go / .NET / Python / PHP | ✅ | ⭕ | `CONNECTOR.md` pronto |
| CLI (start, REPL, import/export) | ✅ | 🟡 | `start` via `run.sh`; sem REPL |
| Docker | ✅ | 🟡 | Dockerfile presente |
| Export/import de dados | ✅ | ✅ | `/export` e `/import`, dump AxonQL |
| Scripting JS / WASM | ✅ | ⭕ | |
| ML (surrealml) | ✅ | ⭕ | |

## Resumo

O AxonBase cobre o núcleo do que o SurrealDB oferece: modelo de documento, AxonQL com grafo, subqueries, agregações e uma biblioteca ampla de funções, transações com WAL, wire JSON-RPC (WebSocket e HTTP), autenticação JWT com escopos, permissões por linha e tempo real com live queries e eventos de tabela. As maiores lacunas estão em funcionalidades avançadas: busca vetorial e full-text (Etapa D), distribuição (Etapa E) e o ecossistema de SDKs além de Java (Etapa F).
