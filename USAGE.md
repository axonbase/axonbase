# Uso do AxonBase

Guia rápido para levantar o servidor, consultar com HTTP e conectar com o SDK Java.

## Servidor

Compilar e rodar a CLI:

```bash
./run.sh start --path memory --port 8000
```

Opções:

| Flag | Valor padrão | Descrição |
|---|---|---|
| `--path` | `memory` | `memory` ou um diretório para persistência em arquivo (cria `axon.db`) |
| `--bind`/`-b` | `127.0.0.1` | endereço de bind |
| `--port` | `8000` | porta HTTP/WebSocket |
| `--secret` | `axonbase-dev-secret` | segredo para JWT (JSON Web Token) |
| `--user`/`-u` | `root` | usuário root (opcional) |
| `--pass`/`--password` | `root` | senha root (opcional) |
| `--config` | `axonbase.conf` | arquivo de configuração |
| `--no-auth` | desativado | inicia sem exigir JWT nas queries RPC |

### Configuração por arquivo

Copie `axonbase.conf.example` para `axonbase.conf` e ajuste os valores locais:

```ini
path = memory
bind = 127.0.0.1
port = 8000
require_auth = true
secret = axonbase-dev-secret
user = root
pass = root
```

A precedência é: valores padrão, arquivo, variáveis de ambiente `AXON_*` e flags da CLI (Command-Line Interface). Por exemplo, `AXON_PORT=9000 ./run.sh start` substitui a porta do arquivo.

Endpoints:

| Endpoint | Descrição |
|---|---|
| `GET /health` | status de saúde |
| `GET /version` | versão do servidor |
| `POST /sql` | executa AxonQL (raw body), com headers `Axon-Ns` e `Axon-Db` |
| `POST /table/{nome}` | cria um registro na tabela (body JSON) |
| `GET /table/{nome}` | lista registros da tabela |
| `POST /signin` | autentica e devolve um JWT |
| `POST /rpc` | JSON-RPC sobre HTTP |
| `WS /rpc/ws` | JSON-RPC sobre WebSocket |

## Exemplos com HTTP

```bash
# health
curl http://127.0.0.1:8000/health

# criar um registro
curl -X POST http://127.0.0.1:8000/sql \
  -H 'Content-Type: application/json' \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data 'CREATE person CONTENT {name: "Ana", age: 30}'

# selecionar
curl -X POST http://127.0.0.1:8000/sql \
  -H 'Content-Type: application/json' \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data 'SELECT * FROM person WHERE age >= 18'

# REST de tabela
curl -X POST http://127.0.0.1:8000/table/person \
  -H 'Content-Type: application/json' \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data '{"name":"Bob","age":21}'

curl http://127.0.0.1:8000/table/person \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev'

# signin (devolve um JWT)
curl -X POST http://127.0.0.1:8000/signin \
  -H 'Content-Type: application/json' \
  --data '{"user":"root","pass":"root"}'

# RPC
curl -X POST http://127.0.0.1:8000/rpc \
  -H 'Content-Type: application/json' \
  -H 'Axon-Ns: test' -H 'Axon-Db: dev' \
  --data '{"id":1,"method":"query","params":["SELECT * FROM person"]}'
```

## SDK Java

O módulo `axonbase-sdk-java` oferece `Axon`, um cliente WebSocket (JSON-RPC):

```java
import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;

try (Axon axon = Axon.connect("ws://127.0.0.1:8000/rpc/ws")) {
    axon.use("test", "dev");

    axon.query("CREATE person CONTENT {name: \"Ana\", age: 30}");

    AxonValue rows = axon.select("SELECT * FROM person WHERE age >= 18");
    System.out.println(AxonJson.write(rows));
}
```

Métodos disponíveis: `connect`, `use`, `query`, `create`, `select`, `update`, `delete`, `signin`, `authenticate`, `live`, `kill`, `version`, `close`.

## Tempo real (live queries)

Uma live query observa uma tabela e entrega as mudanças na própria conexão, sem polling:

```java
try (Axon axon = Axon.connect("ws://127.0.0.1:8000/rpc/ws")) {
    axon.use("test", "dev");

    String id = axon.live("person", (liveId, action, result) ->
        System.out.println(action + " -> " + AxonJson.write(result)));

    axon.query("CREATE person CONTENT {name: \"Ana\"}");   // imprime CREATE -> {...}

    axon.kill(id);
}
```

Em AxonQL, a mesma coisa com filtro e projeção:

```sql
LIVE SELECT name FROM person WHERE age >= 18;
KILL "3f0a1c22-...";
```

Com `DIFF`, cada notificação traz as operações de mudança em vez do registro inteiro:

```sql
LIVE SELECT * FROM person DIFF;
-- UPDATE person:ana SET age = 31 notifica [{"op": "replace", "path": "/age", "value": 31}]
```

Detalhes de comportamento:

- as ações notificadas são `CREATE`, `UPDATE` e `DELETE`; no `DELETE`, o resultado é o registro antes da remoção;
- dentro de uma transação, as notificações só saem no `COMMIT` e o `CANCEL` descarta-as;
- fechar a conexão cancela automaticamente as live queries daquela sessão.

## Eventos de tabela

`DEFINE EVENT` executa sentenças quando um registro muda, com acesso a `$event`, `$before`, `$after` e `$value`:

```sql
DEFINE EVENT auditoria ON TABLE person WHEN $event = "CREATE"
    THEN (CREATE audit CONTENT { acao: "novo", quando: time::now() });
```

## Trabalhando com dados

O modelo de dados do AxonBase é orientado a documentos (objetos JSON) com tabelas. Não há necessidade de schema para uso simples; `DEFINE TABLE ... SCHEMAFULL`, `DEFINE FIELD ... TYPE ...` e `DEFINE INDEX ... UNIQUE` estão disponíveis para esquemas e restrições.

Exemplo de schema:

```sql
DEFINE TABLE user SCHEMAFULL;
DEFINE FIELD email ON TABLE user TYPE string;
DEFINE INDEX email ON TABLE user COLUMNS email UNIQUE;
```

Uma violação de índice único, por exemplo, devolve um erro com código `-32000` (erro interno) no MVP.

## Subqueries, funções e metadados

Subqueries podem aparecer em expressões, filtros e como origem de um `SELECT`:

```sql
SELECT VALUE name FROM person WHERE city IN (SELECT VALUE name FROM city);
SELECT * FROM (SELECT name, age FROM person WHERE age >= 18);
SELECT name, (SELECT VALUE name FROM city WHERE name = $parent.city) AS city FROM person;
```

Há funções nos namespaces `string`, `math`, `array`, `object`, `type`, `record`, `time`, `rand`, `crypto`, `encoding` e `value`:

```sql
SELECT string::uppercase(name) AS grito FROM person;
RETURN array::distinct([1, 1, 2]);
RETURN crypto::sha256("abc");
```

Use `INFO` para inspecionar o que está definido. `INFO FOR ROOT` também lista as funções e identidades, sem expor hashes de senha:

```sql
INFO FOR ROOT;
INFO FOR NAMESPACE;
INFO FOR DATABASE;
INFO FOR TABLE person;
```

## Usuários e escopos

Usuários podem ser limitados ao servidor inteiro, a um namespace ou a um banco:

```sql
DEFINE USER root_admin ON ROOT PASSWORD "troque-esta-senha" ROLES owner;
DEFINE USER app_writer ON DATABASE PASSWORD "senha-forte" ROLES writer;
DEFINE ACCESS app_login ON DATABASE;
```

O JWT emitido pelo `signin` carrega esse escopo. Uma identidade de banco não pode usar outro banco depois de `authenticate`.
