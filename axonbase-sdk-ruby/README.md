# SDK Ruby do AxonBase

Cliente Ruby 2.6+ para o protocolo JSON-RPC (JSON Remote Procedure Call) sobre WebSocket do AxonBase. Este SDK (Software Development Kit) implementa o cliente.

## Instalação

Construa e instale a gem a partir do diretório do SDK:

```bash
gem build axonbase-sdk.gemspec
gem install axonbase-sdk-0.1.0.gem
```

## Uso

```ruby
require "axonbase"

axon = AxonBase::Client.connect("ws://127.0.0.1:8000/rpc/ws")
axon.use("test", "dev")
axon.query('CREATE person CONTENT {name: "Ana", age: 30}')

puts axon.select("person")
axon.close
```

## Autenticação

Quando o servidor usa `require_auth = true`, autentique a sessão WebSocket antes de consultar. `signin` devolve o JWT (JSON Web Token); em seguida, passe o token para `authenticate`. `authenticate` também aceita um token obtido por outro meio.

```ruby
require "axonbase"

axon = AxonBase::Client.connect("ws://127.0.0.1:8000/rpc/ws")
axon.use("test", "dev")
token = axon.signin("root", "root")
axon.authenticate(token)
# Ou: axon.authenticate(ENV.fetch("AXON_TOKEN"))

rows = axon.query("SELECT * FROM person")
axon.close
```

O Bearer é um cabeçalho dos endpoints HTTP: `Authorization: Bearer <JWT>`. Este SDK usa WebSocket, portanto a autorização da sessão é feita por `signin` ou `authenticate`, não por esse cabeçalho.

## Migrações

Use `AxonBase::Migrator` para aplicar arquivos `.axql` em ordem alfabética. O prefixo antes de `_` é a versão, por exemplo `001_create_person.axql`. As versões aplicadas são registradas na tabela `_migration` com checksum SHA-256 (Secure Hash Algorithm 256-bit), e uma execução posterior não reaplica a mesma versão.

```ruby
require "axonbase"

migrator = AxonBase::Migrator.new(axon)
migrator.ensure_table
applied = migrator.up(File.join(__dir__, "migrations"))
status = migrator.status(File.join(__dir__, "migrations"))
```

`up` cria a tabela de controle quando há migrações pendentes. Ele falha se o conteúdo de uma migração já aplicada for alterado. Os blocos de `on_before` e `on_after` recebem cada objeto `AxonBase::Migration` antes e depois de sua aplicação.

Métodos disponíveis: `use`, `signin`, `authenticate`, `query`, `select`, `create`, `insert`, `update`, `upsert`, `delete`, `ping`, `version` e `close`.
