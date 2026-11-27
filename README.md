# AxonBase

Multi-model database written in Java 21+, combining document, graph, relational, key-value, full-text, vector, and geospatial models in a single engine with a unified query language (AxonQL), HTTP and WebSocket server, and SDKs in 8 languages.

Release candidate notes (in Portuguese): [1.0.0-rc2](docs/notas-1.0.0-rc2.md), including behavior changes, and [1.0.0-rc1](docs/notas-1.0.0-rc1.md).

## Features

- **Multi-model engine**: documents, graph edges, relational tables, key-value, time series, geospatial (GeoJSON), and vectors in one ACID-compliant engine
- **AxonQL**: SQL-like query language with graph traversal (`->`/`<-`), subqueries, aggregations, 130+ built-in functions, and EXPLAIN/EXPLAIN ANALYZE
- **Native sagas**: `BEGIN SAGA`, `COMMIT SAGA`, `CANCEL SAGA` with correlation IDs, automatic compensation, remote rollback via Database Link, and ledger tracking
- **AI Audit**: LLM-generated security rules classify every query as `SAFE`, `WARNING`, or `DANGER` before execution — no production data reaches the AI provider. An audited user cannot decide their own case or change audits and users, and an audit that is assigned but missing from the current database denies the statement
- **Data Rules**: row-level security with predicate ANDing and automatic masking of top level fields (see [coverage and limits](#data-rules-coverage-and-limits))
- **Search engines**: BM25 full-text with configurable analyzers, HNSW vector search (cosine/Euclidean/Manhattan), geo spatial with an H3 hierarchical hexagonal index, graph traversal, and columnar indexes for direct aggregation (count/sum/avg/min/max)
- **Multi-model indexes**: unique, count, full-text (SEARCH ANALYZER), HNSW vector, GEO spatial, and COLUMNAR analytical
- **MVCC transactions**: snapshot isolation, savepoints, optimistic retry, phantom protection via read ranges, and `VersionConflictException`
- **Raft consensus**: TCP Raft with leader election, quorum, dynamic membership (join/leave as one log entry per change, without joint consensus; run one change at a time), catch-up, snapshots, and consistent hash sharding
- **Authentication**: JWT (HS256) with root/namespace/database scope, certificate-based (ICP-Brasil compatible), mTLS, and Data Rules from JWT claims
- **Protocols**: JSON-RPC over WebSocket and HTTP, REST (`/sql`, `/table`), GraphQL, MCP (Model Context Protocol), and JDBC
- **Observability**: Prometheus metrics (`/metrics`), health/ready/status endpoints, structured JSON logging, S3 backup with AES-256-GCM encryption
- **Configuration**: file-based (`axonbase.conf`), environment variables (`AXON_*`), and CLI flags with precedence chain

## Data Rules coverage and limits

A data rule filters rows in `SELECT` (including `JOIN`), `UPDATE` and `DELETE`, and restricts what `CREATE`, `INSERT` and `UPSERT` can write. It masks top level fields in query results and write responses, including `SELECT VALUE`, aliases, access by record id and `FETCH`. Access is denied when a rule assigned to the user does not exist in the current database, or when the rule needs a value the session does not have (for example `$auth.tenant` without that claim). A user with a data rule cannot open `LIVE SELECT`, call `/export` or read the `AUDIT_LOG` and `AUDIT_CASES` virtual tables. An `UPSERT` over an existing record that a rule or a permission refuses leaves it untouched.

Table `PERMISSIONS` follow the same principle for token data: a condition that mentions a missing `$auth` or `$token` value denies. The database export carries table permissions and data rules, never users, passwords, tokens, accesses or link credentials.

In an authenticated session, a database administrator is required to drop a table, to declare `PERMISSIONS`, and to change an existing one (`DEFINE`, `CREATE` or `ALTER TABLE`, `DROP INDEX`, partitions and TTL (Time To Live), and replacing a field or index, or adding one to a table with permissions). Creating a new table without permissions, adding fields and indexes to an unprotected table and repeating a definition unchanged stay open to writers.

`$auth` and `$token` hold the claims of the session token (`$auth.id` is the user name). An authenticated session cannot set them.

The following paths are **not** covered, so a data rule is not a complete isolation boundary:

- `WHERE` and `ORDER BY` on a masked field use the real value, which can therefore be inferred
- a write that copies a masked field into another field of the same row (`SET copy = cpf`, `SET extra.copy = cpf`) exposes the value. A `PATCH` list is stricter: `test`, `copy` and `move` are refused on a masked field and on the whole record
- masks do not reach nested fields such as `address.cpf`
- edge properties read through graph traversal are not masked
- a record id stored in a field is read back as text, so a predicate such as `owner.team = 'a'` does not follow it and denies the row. Graph traversal (`->member->team.name`) is followed
- event bodies (`DEFINE EVENT`, which requires an administrator) receive `$before` and `$after` without masks. They run as the writer but only read and write data of the table's database: schema, user, access, saga and link statements are refused, and so is any other database
- functions that read storage directly, such as `kv::`, and queries sent through a database link
- schema statements, as well as `RELATE` and `UNRELATE`, are not restricted by data rules (dropping or changing an existing table requires a database administrator, see below)
- error messages, such as a unique index or primary key violation, can reveal that a hidden value exists
- a program declared `SECURITY DEFINER` runs with the rules of its owner
- backup and management routes, which require an administrative role

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

The server refuses to start with the default root password or the default JWT secret when it listens on a non-loopback address (the Docker image binds `0.0.0.0`), so set both. Authentication is on by default: sign in first and send the token.

```bash
# Run with Docker
export AXON_ROOT_PASSWORD='choose-a-strong-password'
export AXON_SECRET="$(openssl rand -hex 32)"
docker run -d --name axonbase -p 8000:8000 \
  -v axonbase-data:/data \
  -e AXON_ROOT_PASSWORD -e AXON_SECRET \
  axonbase/axonbase:1.0.0-rc2

# Sign in (returns a JWT valid for 30 minutes)
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/signin \
  -H 'Content-Type: application/json' \
  -d "{\"user\":\"root\",\"pass\":\"$AXON_ROOT_PASSWORD\"}" | jq -r .token)

# Execute AxonQL via HTTP
curl -X POST http://127.0.0.1:8000/sql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data "CREATE person CONTENT {name: 'Ana', age: 30}"

curl -X POST http://127.0.0.1:8000/sql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data "SELECT * FROM person"
```

```javascript
// Node.js SDK
import { Axon } from '@axonbase/sdk';
const axon = await Axon.connect('ws://127.0.0.1:8000/rpc/ws');
await axon.signin('root', process.env.AXON_ROOT_PASSWORD);
await axon.use('test', 'dev');
await axon.create('person', { name: 'Ana', age: 30 });
const rows = await axon.select('person');
```

For local experiments only, `AXON_ALLOW_INSECURE_DEFAULTS=true` lifts the startup check, and `--no-auth` turns authentication off.

## Certificate revocation

Certificate login (HTTP and RPC) always rejects a revoked certificate. The server asks the issuer through OCSP (Online Certificate Status Protocol) and falls back to the CRL (Certificate Revocation List) named in the certificate. When the status cannot be verified (service down, no network, certificate with no lookup address) the login is rejected. Setting `AXON_CERT_ALLOW_UNKNOWN_REVOCATION=true`, or `cert_allow_unknown_revocation = true` in `axonbase.conf`, accepts that case with a warning in the log. It never accepts a revoked certificate and never relaxes chain, validity or key usage checks. Details, in Portuguese: [certificate revocation](docs/operacao-e-resiliencia.md#revogação-de-certificado-no-login).

## Inspect a table

Use `DESCRIBE <table>` to inspect a table definition. The result is a row set that JDBC clients can display directly. Every row contains the table name, its schema mode, and field metadata.

```sql
DESCRIBE person;
```

For a `SCHEMAFULL` table, `DESCRIBE` returns fields declared with `DEFINE FIELD`. For a `SCHEMALESS` table, it also infers fields and types from stored documents. Declared fields take precedence over inferred metadata.

```text
name    schema      field         type
person  SCHEMALESS  age           int
person  SCHEMALESS  id            string
person  SCHEMALESS  municipio_id  string
person  SCHEMALESS  name          string
```

## Use cases

- **Financial services**: sagas for distributed ledger transactions, Data Rules for PII masking, AI Audit for Bacen/LGPD compliance, ICP-Brasil certificates
- **AI agents**: MCP for agent connectivity, AI Audit for query governance, Data Rules for row-level access control, vector + full-text hybrid search for RAG
- **E-commerce**: BM25 product search, HNSW recommendations, graph traversal for related items, geo for local inventory, live queries for real-time inventory updates
- **Healthcare**: Data Rules for patient PII protection, AI Audit for access governance, full-text search over clinical notes, sagas for distributed care workflows
- **IoT**: columnar indexes for real-time sensor aggregation, geo for fleet tracking, live queries for telemetry streaming, Raft cluster for high availability

## About the author

**Álvaro Brito** is a Backend & AI Engineer with more than 10 years of Java experience, architecting secure, scalable microservices and delivering modern LLM and RAG solutions. He combines deep proficiency in Node.js, TypeScript, Python, Express, and PostgreSQL with infrastructure-grade execution on AWS, Docker, Terraform, and event-driven architectures. He builds observable AI workflows with LangChain and Langfuse, instrumenting LLM evaluations and operational metrics with Prometheus and Grafana to improve quality and reliability. His work turns complex workflows into production-ready platforms through CI/CD automation, real-time orchestration, and high-throughput data flows with FastAPI and Kafka.

LinkedIn: https://www.linkedin.com/in/alvarogomes/

## License

MIT
