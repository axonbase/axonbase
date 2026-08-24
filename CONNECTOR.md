# Protocolo de conexão do AxonBase (especificação para conectores)

Este documento define o wire protocol do AxonBase, tal como é usado pelo servidor e pelo SDK Java. Serve como referência para implementar os conectores de Node.js, Rust, Go e C#.

## Formato

O protocolo canônico é JSON-RPC (JSON Remote Procedure Call) sobre WebSocket, no endpoint `/rpc/ws`. Existe também a variante sobre HTTP (`POST /rpc`) para requests sem estado. O conteúdo é sempre JSON (JavaScript Object Notation) com codificação UTF-8.

## Envelope de request

Cada request é um objeto JSON com os seguintes campos:

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `id` | number | sim | Marcador do cliente, copiado na resposta |
| `method` | string | sim | Nome do método (ver tabela abaixo) |
| `params` | array | não | Argumentos ordenados do método |
| `session` | string (UUID) | não | Identificador de sessão (em conexões persistentes) |
| `txn` | string (UUID) | não | Identificador de transação |
| `version` | number | não | Versão do protocolo (1) |

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
{"id": 1, "error": {"code": -32003, "message": "método não suportado: foo"}}
```

### Códigos de erro

Seguem a convenção JSON-RPC:

| Código | Significado |
|---|---|
| `-32700` | erro de parse |
| `-32600` | requisição RPC inválida |
| `-32601` | método não encontrado |
| `-32602` | método não permitido |
| `-32603` | parâmetros inválidos |
| `-32000` | erro interno |
| `-32002` | autenticação inválida |
| `-32003` | erro de execução da consulta |
| `-32009` | conflito de transação |

## Métodos

| Método | Params | Retorno |
|---|---|---|
| `ping` | `[]` | `true` |
| `version` | `[]` | string da versão |
| `use` | `[ns, db]` | `{"namespace": "...", "database": "..."}` |
| `signin` | `[{user, pass, access?}]` | string com o JWT |
| `authenticate` | `[token]` | `null` (relaciona o token na sessão) |
| `query` | `[sql, vars?]` | valor do resultado da consulta AxonQL |
| `let`/`set` | `[chave, valor]` | `null` |
| `unset` | `[chave]` | `null` |
| `select` | `[what]` | registros |
| `create` | `[what, data]` | registros criados |
| `update` | `[what, data]` | registros atualizados |
| `delete` | `[what]` | registros antes do delete |
| `begin` | `[]` | `null` (abre transação na sessão) |
| `commit` | `[]` | `null` (confirma a transação da sessão) |
| `cancel` | `[]` | `null` (cancela a transação da sessão) |
| `live` | `[tabela, diff?]` | string com o id da live query |
| `kill` | `[id]` | `true` se a live query existia |

O SDK Java (`axonbase-sdk-java`) implementa `ping`, `use`, `signin`, `authenticate`, `query`, `version`, os helpers `create`, `select`, `update`, `delete`, as live queries `live`/`kill` e as transações `begin`/`commit`/`cancel` (via `query`).

## Live queries (tempo real)

Uma live query observa uma tabela e recebe as mudanças enquanto a conexão estiver aberta. Há dois caminhos para registá-la:

- pelo método `live`, com o nome da tabela: `{"id": 1, "method": "live", "params": ["person", false]}`;
- por AxonQL, através de `query`: `LIVE SELECT * FROM person WHERE age > 18`, que aceita filtro e projeção de campos.

Os dois devolvem o identificador da live query, um UUID (Universally Unique Identifier) em texto. O `KILL "<id>"` em AxonQL e o método `kill` cancelam a subscription.

As mudanças chegam como frames sem `id` de request, distinguíveis pela chave `notification`:

```json
{"notification": {"id": "3f0a...", "action": "CREATE", "result": {"name": "Ana", "id": "person:ana"}}}
```

- `action` vale `CREATE`, `UPDATE` ou `DELETE`.
- `result` é o registro afetado, já com a projeção do SELECT aplicada. No `DELETE`, é o registro tal como estava antes da remoção.
- No modo `DIFF` (`LIVE SELECT ... DIFF` ou `live` com o segundo parâmetro em `true`), o `result` de `CREATE` e `UPDATE` é uma lista de operações no estilo JSON Patch:

```json
{"notification": {"id": "3f0a...", "action": "UPDATE", "result": [{"op": "replace", "path": "/age", "value": 31}]}}
```

Regras que o conector deve respeitar:

1. Um frame com a chave `notification` nunca é resposta a um request e não deve consumir nenhum `id` pendente.
2. Uma notificação pode chegar antes de a resposta do próprio `live` ser processada; guarde as notificações de ids ainda desconhecidos até registar o handler.
3. Dentro de uma transação, as notificações ficam retidas no servidor e só são enviadas no `COMMIT`. O `CANCEL` descarta-as.
4. Ao fechar a conexão, o servidor cancela as live queries da sessão; não é preciso enviar `kill` antes de desconectar.

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
- No WebSocket, `signin`/`authenticate` seguidos de query devém a relação na sessão; sem token, uma query sem auth retorna erro `-32003 usuario não autenticado`.
- No HTTP `/rpc`, o estado não persiste entre requests. Com `requireAuth`, envie `Authorization: Bearer <token>` em cada request, além de `Axon-Ns` e `Axon-Db`; transações `begin`/`commit`/`cancel` continuam sendo próprias da sessão persistente do WebSocket.

## Headers usados pelos endpoints REST e pelo RPC HTTP

- `Axon-Ns`: namespace de uso
- `Axon-Db`: banco de dados de uso
- `Content-Type: application/json`
- `Authorization: Bearer <JWT>` quando `requireAuth` está ativo no HTTP

## Notas de implementação para conectores

1. Utilize uma biblioteca WebSocket que suporte frames de texto UTF-8.
2. O envio da resposta com o `id` correspondente permite associar respostas a requests.
3. Os valores de retorno de `query` são valores JSON arbitrários (objeto, array, número, string) ou `null` para `NONE`.
4. Os valores em `vars` (segundo parâmetro de `query`) são objetos JSON simples.
5. O SDK de referência (Java) usa o cliente WebSocket de Jetty com um `WebSocketAdapter`; qualquer stack equivalente (Node `ws`, Rust `tungstenite`, Go `gorilla/websocket`, C# `ClientWebSocket`) funciona.
