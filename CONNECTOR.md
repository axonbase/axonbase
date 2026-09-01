# Protocolo de conexão do AxonBase (especificação para conectores)

Este documento define o wire protocol do AxonBase, tal como é usado pelo servidor e pelo SDK (Software Development Kit) Java. Serve como referência para implementar os conectores de Node.js, Rust, Go e C#.

## Formato

O protocolo canônico é JSON-RPC (JSON Remote Procedure Call) sobre WebSocket, no endpoint `/rpc/ws`. Existe também a variante sobre HTTP (Hypertext Transfer Protocol) (`POST /rpc`) para requests sem estado. O conteúdo é sempre JSON (JavaScript Object Notation) com codificação UTF-8.

## Versão de protocolo

O protocolo de wire tem uma versão própria (`PROTOCOL_VERSION`), independente da versão do artefato do servidor. A versão atual é `1`. Conectores devem:

- ler o frame de handshake `hello` (abaixo) na primeira mensagem do WebSocket;
- enviar `"version": <PROTOCOL_VERSION>` em qualquer request que queira negociar compatibilidade.

Quando um request traz um campo `version` divergente do protocolo do servidor, a resposta é um erro estruturado `-32600 PROTOCOL_MISMATCH` **sem executar** o método:

```json
{"id": 5, "error": {"code": -32600, "message": "PROTOCOL_MISMATCH", "kind": "PROTOCOL_MISMATCH", "protocol": 1, "version": 2}}
```

## Handshake `hello`

Ao abrir um WebSocket, o servidor envia imediatamente um frame `hello` antes de processar qualquer request:

```json
{"hello": {"protocol": 1, "server": "0.1.0-SNAPSHOT", "methods": ["ping", "use", "query", "let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate", "signin", "signup", "authenticate", "invalidate", "live", "kill", "version", "begin", "commit", "cancel", "kv_get", "kv_set", "kv_del", "kv_scan"]}}
```

| Campo | Tipo | Descrição |
|---|---|---|
| `protocol` | número | `PROTOCOL_VERSION` do servidor |
| `server` | string | versão do artefato servidor |
| `methods` | array de strings | métodos RPC suportados pela conexão |

## Envelope de request

Cada request é um objeto JSON com os seguintes campos:

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `id` | valor JSON | não | Marcador do cliente, copiado na resposta; ausente resulta em `null` |
| `method` | string | sim | Nome do método (ver tabela abaixo) |
| `params` | array | não | Argumentos ordenados do método |
| `version` | número | não | `PROTOCOL_VERSION` do cliente; se divergir, o request é recusado com `PROTOCOL_MISMATCH` sem efeito |

No WebSocket, a sessão é a própria conexão. No HTTP, cada request recebe uma sessão nova. O dispatcher não lê campos `session` ou `txn`.

Exemplo:

```json
{"id": 1, "method": "query", "params": ["SELECT * FROM person"]}
```

## Envelope de resposta

Uma resposta é um objeto com `id` e com `result` ou `error`:

```json
{"id": 1, "result": [{"name": "Ana", "id": "person:41e33792-bed3-4943-932e-461221e18c42"}]}
```

Erro:

```json
{"id": 1, "error": {"code": -32601, "message": "método não suportado: foo"}}
```

### Códigos de erro

Seguem a convenção JSON-RPC:

| Código | Significado |
|---|---|---|
| `-32700` | erro de parse |
| `-32600` | requisição RPC inválida ou `PROTOCOL_MISMATCH` (campo `version` divergente) |
| `-32601` | método não encontrado |
| `-32602` | parâmetros com tipo ou formato inválido |
| `-32000` | erro interno do servidor |
| `-32002` | autenticação inválida (credenciais recusadas) |
| `-32003` | erro de execução, parâmetros ausentes ou autenticação tratados pelo dispatcher |
| `-32009` | `TXN_CONFLICT`: conflito de versão numa transação concorrente |
| `-32010` | `NOT_LEADER`: uma escrita chegou a um seguidor |
| `-32011` | `NO_QUORUM`: não houve maioria para confirmar uma escrita |

O erro `PROTOCOL_MISMATCH` não tem a forma `{id, error}` simples: inclui `kind`, `protocol` (versão do servidor) e `version` (versão enviada pelo cliente), como mostrado na seção de versão acima.

Erros de domínio `AxonError` originados em `query`, `live`, `begin` ou `commit` preservam seu código, inclusive `-32000` (interno), `-32002` (autenticação) e `-32009` (conflito de transação). Outros `RuntimeException` desses métodos viram `-32003`.

Uma escrita enviada ao seguidor devolve um erro estruturado:

```json
{"id": 1, "error": {"code": -32010, "message": "escrita deve ser enviada ao líder n1 em 10.0.0.1:8000", "kind": "NOT_LEADER", "leader": "n1", "leader_address": "10.0.0.1:8000"}}
```

Durante uma eleição, `leader` e `leader_address` são strings vazias. `NO_QUORUM` não inclui campos extras:

```json
{"id": 1, "error": {"code": -32011, "message": "quórum indisponível no grupo app/main"}}
```

## Métodos

| Método | Params | Retorno |
|---|---|---|
| `ping` | `[]` | `true` |
| `version` | `[]` | string da versão do artefato |
| `use` | `[ns, db]` | `{"namespace": "...", "database": "..."}` |
| `let` / `set` | `[key, value]` | o valor armazenado (variável de sessão, sem tocar o backend) |
| `unset` | `[key]` | `true` |
| `signin` | `[{user, pass, access?}]` | string com o JSON Web Token (JWT) |
| `signup` | `[{user, pass, ns?, db?}]` | string com o JWT (cria o usuário no escopo) |
| `authenticate` | `[token]` | `null` (relaciona o token na sessão) |
| `invalidate` | `[]` | `null` (limpa a autenticação da sessão local) |
| `query` | `[sql, vars?]` | valor do resultado da consulta AxonQL |
| `select` | `[tabela\|SQL, where?]` | registros da tabela (leitura, roda em qualquer réplica) |
| `create` | `[tabela\|record, dados]` | registros criados (escrita, roteada ao líder) |
| `insert` | `[tabela, dados]` | registros inseridos (escrita, roteada ao líder) |
| `update` | `[alvo, dados]` | registros atualizados (escrita, roteada ao líder) |
| `upsert` | `[alvo, dados]` | registros atualizados ou criados se faltarem (escrita, roteada ao líder) |
| `delete` | `[alvo, where?]` | registros removidos (escrita, roteada ao líder) |
| `relate` | `[de, tipo, para, data?]` | aresta criada (escrita, roteada ao líder) |
| `begin` | `[]` | `null` (abre transação na sessão) |
| `commit` | `[]` | `null` (confirma a transação da sessão) |
| `cancel` | `[]` | `null` (cancela a transação da sessão) |
| `live` | `[tabela, diff?]` | string com o id da live query |
| `kill` | `[id]` | `true` se a live query existia |

`select`, `create`, `insert`, `update`, `upsert`, `delete` e `relate` delegam no motor AxonQL. `select` é leitura e roda em qualquer réplica; os demais mutam estado replicado e, num cluster, só executam no líder (erro `-32010 NOT_LEADER` num seguidor). Os comandos AxonQL como `LET`, `CREATE`, `SELECT`, `UPDATE`, `DELETE`, `BEGIN`, `COMMIT` e `CANCEL` também podem ser enviados pelo método `query`.

O SDK Java (`axonbase-sdk-java`) implementa `use`, `signin`, `authenticate`, `query`, os helpers `create`, `select`, `update`, `delete`, as live queries `live`/`kill` e `close`. O método `version` do SDK devolve a versão local do artefato; não faz uma chamada RPC. O SDK não expõe `ping`, `begin`, `commit` ou `cancel` como métodos públicos.

## Variáveis tipadas em `query`

O segundo parâmetro de `query` é um mapa de variáveis (nome → valor). Valores que não têm literal JSON (datetime, duração, UUID, decimal, bytes, table e record) são marcados com envelope de uma chave:

| Envelope | Valor | Tipo AxonQL resultante |
|---|---|---|
| `{"$datetime": "<RFC3339>"}` | `"2026-01-01T00:00:00Z"` | `datetime` |
| `{"$duration": "<texto>"}` | `"1h"`, `"30m"`, `"500ms"`, `"2d"` | `duration` |
| `{"$uuid": "<texto>"}` | `"73e9a5c7-91f2-4b6e-b9a9-123456789abc"` | `uuid` |
| `{"$decimal": "<texto>"}` | `"123.45"` | `decimal` |
| `{"$bytes": "<base64>"}` | `"SGk="` | `bytes` |
| `{"$table": "<nome>"}` | `"person"` | `table` |
| `{"$record": "<tabela>:<chave>"}` | `"person:ana"` | `record` |

`RFC 3339` significa Request for Comments 3339 e define o formato do valor `datetime`.

Exemplo de query com variáveis tipadas:

```json
{"id": 3, "method": "query", "version": 1, "params": [
  "CREATE visita CONTENT {quem: $quem, quando: $quando, custo: $custo}",
  {
    "quem": {"$record": "person:ana"},
    "quando": {"$datetime": "2026-01-01T10:30:00Z"},
    "custo": {"$decimal": "99.90"}
  }
]}
```

O servidor converte os envelopes para o tipo AxonQL real antes da execução, e o resultado serializado devolve os mesmos marcadores (`RETURN $v` ecoa `{"$datetime":"..."}`, etc.).

## Savepoints (SAVEPOINT / RELEASE / ROLLBACK TO)

O protocolo não expõe savepoints como métodos RPC próprios. Em vez disso, o conector emite comandos AxonQL pelo método `query`:

| Comando | Efeito |
|---|---|
| `SAVEPOINT <nome>` | Cria um checkpoint dos writes e deletes pendentes |
| `RELEASE <nome>` | Descarta o checkpoint, mantendo as mudanças posteriores |
| `ROLLBACK TO <nome>` | Retrocede writes e deletes ao estado do checkpoint |

Exemplo:

```json
{"id": 10, "method": "query", "params": ["SAVEPOINT sp1"]}
{"id": 11, "method": "query", "params": ["SAVEPOINT sp2"]}
{"id": 12, "method": "query", "params": ["CREATE person:ana CONTENT {nome: 'Ana'}"]}
{"id": 13, "method": "query", "params": ["ROLLBACK TO sp1"]}
{"id": 14, "method": "query", "params": ["COMMIT"]}
```

O motor retém o snapshot de leitura original; o `ROLLBACK TO` apenas desfaz writes e deletes do buffer. `RELEASE` elimina o marcador sem desfazer nada.

## Key-Value público

O AxonBase expõe quatro métodos RPC para acesso Key-Value direto, fora da AxonQL:

| Método | Params | Retorno | Descrição |
|---|---|---|---|
| `kv_get` | `[ns, db, key]` | o valor armazenado ou `null` | Leitura direta de uma chave |
| `kv_set` | `[ns, db, key, value, ttl?]` | o valor definido | Escrita atômica de uma chave (rótulo por `ttl` em segundos, opcional) |
| `kv_del` | `[ns, db, key]` | `true` se a chave existia | Remoção de uma chave |
| `kv_scan` | `[ns, db, prefix]` | array de `{k, v}` pares | Varredura por prefixo, ordenada |

Os métodos `kv_set` e `kv_del` alteram estado replicado e, em cluster, só executam no líder (erro `-32010 NOT_LEADER` num seguidor).

O SDK também expõe funções `kv::*` na AxonQL:

```sql
SELECT * FROM kv::scan('ns', 'db', 'prefix');
RETURN kv::get('ns', 'db', 'chave');
```

## Backup, restauração e membership

### Snapshot autoritativo

| Endpoint | Método | Descrição |
|---|---|---|
| `/admin/cluster/snapshot` | POST | Dispara um snapshot completo do estado Raft |
| `/admin/cluster/backup` | GET | Descarrega o snapshot como arquivo compactado |
| `/admin/cluster/restore` | POST | Restaura estado a partir de um snapshot enviado |

### Membership dinâmica

| Endpoint | Método | Descrição |
|---|---|---|
| `/admin/cluster/join` | POST | Adiciona um peer ao cluster (joint consensus) |
| `/admin/cluster/leave` | POST | Remove um peer do cluster (joint consensus) |

## Observabilidade e configuração do servidor

O servidor expõe métricas no formato Prometheus pelo endpoint `/metrics`:

- `axon_requests_total` (contador por método e status)
- `axon_ws_open` (gauge de conexões WebSocket ativas)
- `axon_uptime_seconds`
- `axon_raft_leader_changes`, `axon_raft_quorum_size`, `axon_raft_snapshot_size`

### Timeouts configuráveis

| Variável | Padrão | Descrição |
|---|---|---|
| `AXON_QUERY_TIMEOUT` | 0 (ilimitado) | ms máximo por execução de query |
| `AXON_TXN_TIMEOUT` | 0 (ilimitado) | ms máximo de uma transação aberta |
| `AXON_SHUTDOWN_TIMEOUT` | 5000 | ms de espera no shutdown gracioso antes de forçar parada |

### Rate limiting

`AXON_RATE_LIMIT` define o número máximo de requests por minuto (padrão: 0, desligado). Requests excedentes recebem HTTP 429.

### CORS (Cross-Origin Resource Sharing)

`AXON_CORS_ORIGINS` define origens permitidas para CORS (separadas por vírgula). Padrão: vazio (CORS desligado).

### TLS (Transport Layer Security) e mTLS (mutual Transport Layer Security)

| Variável | Descrição |
|---|---|
| `AXON_TLS_CERT` | Caminho para certificado TLS (PKCS #12, Public-Key Cryptography Standards #12) |
| `AXON_TLS_KEY` | Senha do key store |
| `AXON_TLS_CA` | (opcional) Caminho para a CA (Certificate Authority); quando presente, ativa mTLS (autenticação de cliente obrigatória) |

### Pool Jetty

`AXON_HTTP_THREADS` (padrão: 50) e `AXON_HTTP_QUEUE` (padrão: 200) controlam o pool de threads do Jetty.

### Logging estruturado

O servidor registra cada request numa linha JSON:

```
{"event":"request","method":"POST","path":"/rpc","status":200,"elapsed":12}
```

## Live queries (tempo real)

Uma live query observa uma tabela e recebe as mudanças enquanto a conexão estiver aberta. Há dois caminhos para registá-la:

- pelo método `live`, com o nome da tabela: `{"id": 1, "method": "live", "params": ["person", false]}`;
- por AxonQL, através de `query`: `LIVE SELECT * FROM person WHERE age > 18`, que aceita filtro e projeção de campos.

Os dois devolvem o identificador da live query, um UUID (Universally Unique Identifier) em texto. O `KILL "<id>"` em AxonQL e o método `kill` cancelam a subscription.

As mudanças chegam como frames WebSocket sem `id` de request, distinguíveis pela chave `notification`:

```json
{"notification":{"id":"3f0a...","action":"CREATE","result":{"name":"Ana","id":"person:ana"}}}
```

- `id` é o UUID retornado por `live` ou por `query` com `LIVE SELECT`.
- `action` vale `CREATE`, `UPDATE` ou `DELETE`.
- `result` é o registro afetado, já com a projeção do SELECT aplicada. No `DELETE`, é o registro tal como estava antes da remoção.
- No modo `DIFF` (`LIVE SELECT ... DIFF` ou `live` com o segundo parâmetro em `true`), o `result` de `CREATE` e `UPDATE` é uma lista de operações no estilo JSON Patch:

```json
{"notification":{"id":"3f0a...","action":"UPDATE","result":[{"op":"replace","path":"/age","value":31}]}}
```

Regras que o conector deve respeitar:

1. Um frame com a chave `notification` nunca é resposta a um request e não deve consumir nenhum `id` pendente.
2. Uma notificação pode chegar antes de a resposta do próprio `live` ser processada; guarde as notificações de ids ainda desconhecidos até registar o handler.
3. Dentro de uma transação, as notificações ficam retidas no servidor e só são enviadas no `COMMIT`. O `CANCEL` descarta-as.
4. Ao fechar a conexão, o servidor cancela as live queries da sessão; não é preciso enviar `kill` antes de desconectar.
5. No modo `DIFF`, `CREATE` e `UPDATE` retornam operações `add`, `replace` ou `remove`; uma operação `remove` não tem `value`. `DELETE` continua retornando o registro anterior com a projeção aplicada.

## Sessão

No WebSocket, cada conexão mantém uma sessão com namespace e banco de dados, um estado de autenticação e o estado da transação ativa. O WebSocket exige JWT (JSON Web Token) nas queries quando o servidor roda com `requireAuth`.

O JWT traz o escopo da identidade: `ROOT`, `NAMESPACE` ou `DATABASE`. Depois de `authenticate`, `use` e `query` só aceitam o namespace e o banco permitidos por esse escopo. O servidor configura identidades por AxonQL:

```sql
DEFINE USER alice ON DATABASE PASSWORD "senha-forte" ROLES editor;
DEFINE ACCESS app_login ON DATABASE;
```

O `access` opcional em `signin` precisa existir no mesmo escopo:

```json
{"id": 1, "method": "signin", "params": [{"user": "alice", "pass": "senha-forte", "access": "app_login"}]}
```

A diferença entre WebSocket e HTTP/RPC:
- No WebSocket, `signin`/`authenticate` seguidos de query mantêm a relação na sessão; sem token, uma query sem auth retorna erro `-32003 usuario non autenticado`.
- No HTTP `/rpc`, o estado não persiste entre requests. Com `requireAuth`, envie `Authorization: Bearer <token>` em cada request, além de `Axon-Ns` e `Axon-Db`; transações `begin`/`commit`/`cancel` continuam sendo próprias da sessão persistente do WebSocket.

## Autenticação nos endpoints HTTP

Com `requireAuth` ativo, os endpoints REST (REpresentational State Transfer) `POST /sql`, `GET /table/{nome}` e `POST /table/{nome}`, o `POST /rpc`, o `POST /graphql` e os endpoints MCP (Model Context Protocol) exigem um JWT (JSON Web Token) válido. Envie-o em todas as requisições HTTP, inclusive ao abrir a conexão SSE (Server-Sent Events) do MCP:

```http
Authorization: Bearer <JWT>
```

Os endpoints que operam no banco também usam `Axon-Ns` e `Axon-Db`. O token precisa autorizar esse namespace e banco. Ausência de token, token inválido ou escopo incompatível resulta em HTTP 401 nos endpoints REST, GraphQL e MCP. No RPC HTTP, a falha é retornada no envelope JSON-RPC.

No WebSocket, o cabeçalho Bearer não substitui a autenticação da sessão: chame `signin` ou `authenticate` antes de executar consultas protegidas.

## GraphQL

`POST /graphql` recebe `{"query":"...","variables":{...}}` e aceita consultas de leitura por tabela e introspecção `__schema`. Com `requireAuth`, envie os cabeçalhos Bearer, `Axon-Ns` e `Axon-Db`.

## MCP

O MCP usa SSE em `GET /mcp/sse`; o evento `endpoint` informa a URL para enviar mensagens `POST /mcp/message?session_id=...`. As duas requisições devem levar o Bearer quando `requireAuth` estiver ativo, além de `Axon-Ns` e `Axon-Db` para selecionar o banco.

Por padrão, o servidor expõe somente ferramentas de leitura: `axon_select`, com `table` e `limit` opcional de no máximo 100, e `axon_info`, para o catálogo do banco atual. Não há ferramenta MCP para consulta AxonQL arbitrária, nem para escrita.

## Headers usados pelos endpoints REST e pelo RPC HTTP

- `Axon-Ns`: namespace de uso
- `Axon-Db`: banco de dados de uso
- `Content-Type: application/json`
- `Authorization: Bearer <JWT>` quando `requireAuth` está ativo no HTTP

## Notas de implementação para conectores

1. Utilize uma biblioteca WebSocket que suporte frames de texto UTF-8.
2. O envio da resposta com o `id` correspondente permite associar respostas a requests.
3. Os valores de retorno de `query` são valores JSON arbitrários (objeto, array, número, string) ou `null` para `NONE` e `null`.
4. Os valores em `vars` (segundo parâmetro de `query`) são objetos JSON simples; tipos sem literal JSON usam os envelopes `$datetime`, `$duration`, `$uuid`, `$decimal`, `$bytes`, `$table` e `$record` descritos acima.
5. O frame de handshake `hello` é o primeiro frame de cada conexão WebSocket; o conector deve lê-lo antes de enviar requests.
6. O SDK de referência (Java) usa o cliente WebSocket de Jetty com um `WebSocketAdapter`; qualquer stack equivalente (Node `ws`, Rust `tungstenite`, Go `gorilla/websocket`, C# `ClientWebSocket`) funciona.
