# Funcionalidades SurrealDB × AxonBase

Tabela comparativa entre o SurrealDB (referência) e o AxonBase (implementação em Java 21+). Marcação de estado: ✅ presente, 🟡 parcial, ⭕ ausente.

## 1. Modelo de dados

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| Documento (objetos aninhados) | ✅ | ✅ | AxonValue object/array |
| Tabelas / schemaless | ✅ | ✅ | SCHEMAFULL e SCHEMALESS |
| Relacional (constraints, schema) | ✅ | ✅ | `DEFINE FIELD TYPE/ASSERT/DEFAULT` com coerção e validação em runtime; `REFERENCES` com validação de chave estrangeira |
| Grafo (vértices e arestas) | ✅ | ✅ | `RELATE` persiste arestas no KV; travessia `->`/`<-`/`<->` |
| Record links (não JOINs) | ✅ | ✅ | RecordId `tabela:chave`; `FETCH` resolve record links |
| Time-series | ✅ | ⭕ | |
| Geográfico (GeoJSON) | ✅ | ✅ | `geometry::point`, `geometry::line`, `geometry::polygon`, `geo::distance`, `geo::area`, `geo::contains`, índice GEO com GeoHash |
| Key-value | ✅ | ✅ | API pública `kv_get`/`kv_set`/`kv_del`/`kv_scan` via RPC e funções `kv::*` na AxonQL |
| Vetorial | ✅ | ✅ | HNSW multicamada com M/EFC/EFS, distâncias (`vector::distance::*`), similaridade, ORDER BY + LIMIT k |

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
| JOIN básico (`a JOIN b ON a.k = b.k`) | ✅ | 🟡 | Bucle anidado, saída achatada com prefixos `a.campo`/`b.campo`; sen índice de hash, um JOIN por consulta, WHERE pré-JOIN e achatamento raso |

## 3. Transações e armazenamento

| Capacidade | SurrealDB | AxonBase | Notas |
|---|---|---|---|
| ACID multi-linha | ✅ | ✅ | snapshot isolation otimista |
| WAL + snapshot + fsync | ✅ | ✅ | RocksDbBackend (LSM-tree, ZSTD, bloom filters, **padrão**) e WalBackend (legacy) |
| Crash recovery | ✅ | ✅ | RocksDB WAL recovery + WalBackend replay |
| Savepoints | ✅ | ✅ | `SAVEPOINT`/`RELEASE`/`ROLLBACK TO` via AxonQL |
| Retry de conflito otimista | ✅ | ✅ | `Datastore.withRetry`, `VersionConflictException` |
| Storage distribuído | ✅ (TiKV/Raft) | 🟡 | Raft TCP embutido, quorum, failover, membership; sem TiKV |
| Índice UNIQUE | ✅ | ✅ | |
| Índice COUNT | ✅ | 🟡 | definido no catálogo |
| Índice full-text | ✅ | ✅ | analyzer, índice invertido, BM25 real, `@@`, `search::score()`, `search::highlight()` |
| Índice vetorial (HNSW/DiskANN) | ✅ | ✅ | HNSW multicamada com M/EFC/EFS configuráveis, candidatos e ordenação vetorial |
| Busca híbrida (full-text + vetorial) | ✅ | ✅ | Combina postings BM25 e candidatos HNSW num mesmo SELECT |
| **Índice colunar** | ⭕ | **✅** | `DEFINE INDEX ... COLUMNS ... COLUMNAR`, codificação sortable, agregacão direta count/sum/avg/min/max sem carregar documentos |

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
| SDK Node.js / Rust / Go / .NET / Python / PHP | ✅ | 🟡 | SDK Node.js/TypeScript presente (`axonbase-sdk-nodejs/`); Rust, Go, .NET e Python ausentes |
| CLI (start, REPL, import/export) | ✅ | 🟡 | `start` via `run.sh`; sem REPL |
| Docker | ✅ | 🟡 | Dockerfile e Docker Compose de cluster (3 nós) |
| Export/import de dados | ✅ | ✅ | `/export` e `/import`, dump AxonQL |
| Scripting JS / WASM | ✅ | ⭕ | |
| ML (surrealml) | ✅ | ⭕ | |

## 6. Comparação Atual

| Área | SurrealDB | AxonBase | Estado e diferença principal |
|---|---:|---:|---|
| Modelo de documento | Maduro | Presente | Objetos, arrays, schemas, defaults e asserts. |
| Grafo | Maduro | Presente | Arestas persistidas e travessias; sem otimizador de grafo avançado. |
| Time-series | Presente | **Ausente** | Ainda não há bucket temporal, retenção ou downsampling. |
| GeoJSON | Maduro | Presente | Funções geo, `geometry::line/polygon` e índice GEO com GeoHash hierárquico. |
| Vetores | HNSW/DiskANN maduros | Presente | HNSW multicamada com M/EFC/EFS; sem DiskANN. |
| Full-text | BM25, analyzers maduros | Presente | Índice invertido, analyzer, BM25 real, score, highlight. |
| Busca híbrida | Presente | Presente | Combina ranking BM25 e candidatos HNSW. |
| Transações | Snapshot isolation maduro | Presente | MVCC, conflito por versão, savepoints, retry automático. |
| Cluster | Produção distribuída | Presente | Raft TCP, quorum, failover, catch-up, membership dinâmica, snapshot/backup/restore, compactação/fsync, shard map por hash consistente, joint consensus simplificado, Docker Compose. |
| Realtime | Maduro | Presente | Live queries, eventos pós-quorum. |
| Segurança | RBAC maduro | Parcial | JWT, escopos, permissões, TLS, rate limit, CORS; sem API keys, refresh token, auditoria. |
| APIs | WS, HTTP, REST, GraphQL, GQL, MCP | Parcial | WS, HTTP, REST, GraphQL, MCP presentes; GQL ausente. |
| SDKs | Múltiplas linguagens | Parcial | Java, Node.js, Python, Go, Rust, .NET — criados e testados (Java: 6, Node: 10, Python: 7, Rust: 13). |
| CLI | `surreal` completo | Parcial | `axon sql`, `repl`, `export`, `import`, administração de cluster. |
| Operação | Cloud madura | Presente | Health, ready, status, métricas, logging, shutdown, pool Jetty, TLS, Docker, Compose. |
| JDBC driver | Nativo | Presente | `jdbc:axonbase:ws://...` — DataGrip, DBeaver, testado com 12 operações. |

Nesta tabela, HNSW (Hierarchical Navigable Small World) é o índice aproximado de vizinhos usado em busca vetorial. BM25 (Best Matching 25) é um algoritmo de relevância para busca textual. JWT (JSON Web Token) identifica sessões autenticadas. mTLS é a versão mútua do TLS (Transport Layer Security), onde cliente e servidor trocam certificados para autenticação bidirecional. Grafos Raft são grupos de nós que usam o protocolo de consenso Raft para replicar dados de forma consistente. MCP (Model Context Protocol) é o protocolo padronizado do Anthropic para ferramentas de agentes de IA. GQL é a norma ISO para linguagem de consulta de propriedades (ISO/IEC 39075).

## Resumo

O AxonBase cobre o núcleo do que o SurrealDB oferece e se aproxima em várias áreas avançadas, faltando principalmente time-series, auditoria de segurança e scripting embutido. A principal vantagem do AxonBase neste estágio é a **abertura**: todo o código está disponível, o protocolo é independente de linguagem com handshake versionado, o cluster roda em Java puro sem dependências externas de infraestrutura.

**Diferenciais recentes:**
- **RocksDbBackend como padrão** — LSM-tree maduro (Meta), compressão ZSTD, bloom filters, 243K inserts/s sustentados, substituindo o WalBackend caseiro
- **Índice colunar** — `DEFINE INDEX ... COLUMNS ... COLUMNAR` para agregacão direta (count/sum/avg/min/max) sem carregar documentos, codificação sortable de valores no KV
