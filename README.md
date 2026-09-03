# AxonBase

Multi-model database written in Java 21+, combining document, graph, relational, key-value, full-text, vector, and geospatial models in a single engine with a unified query language (AxonQL), HTTP and WebSocket server, and SDKs in 8 languages.

## Features

- **Multi-model engine**: documents, graph edges, relational tables, key-value, time series, geospatial (GeoJSON), and vectors in one ACID-compliant engine
- **AxonQL**: SQL-like query language with graph traversal (`->`/`<-`), subqueries, aggregations, 130+ built-in functions, and EXPLAIN/EXPLAIN ANALYZE
- **Native sagas**: `BEGIN SAGA`, `COMMIT SAGA`, `CANCEL SAGA` with correlation IDs, automatic compensation, remote rollback via Database Link, and ledger tracking
- **AI Audit**: LLM-generated security rules classify every query as `SAFE`, `WARNING`, or `DANGER` before execution — no production data reaches the AI provider
- **Data Rules**: row-level security with predicate ANDing and automatic field masking, enforced across all queries without application changes
- **Search engines**: BM25 full-text with configurable analyzers, HNSW vector search (cosine/Euclidean/Manhattan), geo spatial with R-Tree, graph traversal, and columnar indexes for direct aggregation (count/sum/avg/min/max)
- **Multi-model indexes**: unique, count, full-text (SEARCH ANALYZER), HNSW vector, GEO spatial, and COLUMNAR analytical
- **MVCC transactions**: snapshot isolation, savepoints, optimistic retry, phantom protection via read ranges, and `VersionConflictException`
- **Raft consensus**: TCP Raft with leader election, quorum, dynamic membership (join/leave via joint consensus), catch-up, snapshots, and consistent hash sharding
- **Authentication**: JWT (HS256) with root/namespace/database scope, certificate-based (ICP-Brasil compatible), mTLS, and Data Rules from JWT claims
- **Protocols**: JSON-RPC over WebSocket and HTTP, REST (`/sql`, `/table`), GraphQL, MCP (Model Context Protocol), and JDBC
- **Observability**: Prometheus metrics (`/metrics`), health/ready/status endpoints, structured JSON logging, S3 backup with AES-256-GCM encryption
- **Configuration**: file-based (`axonbase.conf`), environment variables (`AXON_*`), and CLI flags with precedence chain

## SDKs

All 8 SDKs expose the same public API with feature parity, tested independently:

| Language | Registry | Install |
|---|---|---|
| Java | Maven Central | `mvn dependency:get -Dartifact=com.axondatabase:axonbase-sdk-java:0.2.1` |
| Node.js / TypeScript | npm | `npm install @axonbase/sdk@0.1.1` |
| Python | PyPI | `python -m pip install axonbase-sdk==0.1.1` |
| Go | Go module proxy | `go get github.com/axonbase/axonbase/axonbase-sdk-go@v0.1.0` |
| Rust | crates.io | `cargo add axonbase-sdk@0.1.1` |
| .NET | NuGet | `dotnet add package AxonBase.Sdk --version 0.1.0` |
| PHP | Packagist | `composer require axonbase/sdk:^0.1` |
| Ruby / Rails | RubyGems | `gem install axonbase-sdk -v 0.1.0` |

Every SDK implements: `connect`, `use`, `signin`, `authenticate`, `query`, `select`, `create`, `insert`, `update`, `upsert`, `delete`, `relate`, `begin`/`commit`/`cancel`, `kv_get`/`kv_set`/`kv_del`/`kv_scan`, `live`/`kill`, `certificateBegin`/`certificateComplete`, mTLS, typed RPC errors, Migrator (.axql), and `SagaTransaction`/`SagaParticipantTransaction`.

## What makes AxonBase different

- **Only database with native sagas** — distributed transactions with correlation, compensation, and remote rollback, no Kafka or outbox pattern needed
- **Only database with AI Audit** — LLM-generated rules govern every query, no separate proxy or middleware required
- **Only multi-model database with columnar indexes** — direct aggregation without loading documents, traditionally a separate ClickHouse workload
- **MCP with read-only defaults** — agents discover tables safely via `axon_select` and `axon_info`; arbitrary query (`axon_query`) is not exposed
- **8 SDKs with verified parity** — every SDK passes the same feature matrix across all 8 languages

## Modules

| Module | Package | Role |
|---|---|---|
| `axonbase-common` | `com.axonbase.common` | Utilities, errors, time control, minimal observability |
| `axonbase-value` | `com.axonbase.value` | Value model, JSON codec, total order |
| `axonbase-parser` | `com.axonbase.parser` | AxonQL grammar (recursive descent) and AST |
| `axonbase-core` | `com.axonbase.core` | Engine: storage, catalog, transactions, executor, indexes, functions |
| `axonbase-server` | `com.axonbase.server` | HTTP + WebSocket server, CLI, JWT, authentication |
| `axonbase-sdk-java` | `com.axonbase.sdk` | Java SDK client |
| `axonbase-sdk-php` | `AxonBase` | PHP client |
| `axonbase-sdk-ruby` | `AxonBase` | Ruby client |
| `axonbase-jdbc` | `com.axonbase.jdbc` | JDBC driver over WebSocket |
| `axonbase-spring-data` | `com.axonbase.springdata` | Spring Data integration |
| `axonbase-cli` | `com.axonbase.cli` | Interactive REPL and scripted commands |

## Build and test

```bash
# Full build
mvn install -DskipTests

# Run all Java tests
mvn test

# Run SDK-specific tests
cd axonbase-sdk-nodejs && npm test
cd axonbase-sdk-rust && cargo test
cd axonbase-sdk-go && go test ./...
cd axonbase-sdk-ruby && ruby -Ilib test/client_test.rb
cd axonbase-sdk-python && pip install -e ".[test]" && pytest
docker build --target php-test --file Dockerfile.sdk-tests .
docker build --target dotnet-test --file Dockerfile.sdk-tests .
```

## Quick start

```bash
# Run with Docker
docker run -d --name axonbase -p 8000:8000 axonbase/axonbase:latest

# Execute AxonQL via HTTP
curl -X POST http://127.0.0.1:8000/sql \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data "CREATE person CONTENT {name: 'Ana', age: 30}"

curl -X POST http://127.0.0.1:8000/sql \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data "SELECT * FROM person"
```

```javascript
// Node.js SDK
import { Axon } from '@axonbase/sdk';
const axon = await Axon.connect('ws://127.0.0.1:8000/rpc/ws');
await axon.use('test', 'dev');
await axon.create('person', { name: 'Ana', age: 30 });
const rows = await axon.select('person');
```

## Use cases

- **Financial services**: sagas for distributed ledger transactions, Data Rules for PII masking, AI Audit for Bacen/LGPD compliance, ICP-Brasil certificates
- **AI agents**: MCP for agent connectivity, AI Audit for query governance, Data Rules for row-level access control, vector + full-text hybrid search for RAG
- **E-commerce**: BM25 product search, HNSW recommendations, graph traversal for related items, geo for local inventory, live queries for real-time inventory updates
- **Healthcare**: Data Rules for patient PII protection, AI Audit for access governance, full-text search over clinical notes, sagas for distributed care workflows
- **IoT**: columnar indexes for real-time sensor aggregation, geo for fleet tracking, live queries for telemetry streaming, Raft cluster for high availability

## Documentation

- `USAGE.md` — CLI, HTTP, SDK usage guide
- `CONNECTOR.md` — wire protocol specification
- `FEATURES.md` — full feature inventory
- `CICD.md` — SDK publishing guide
- `PLAN.md` — implementation roadmap

## License

MIT
