# SDK PHP do AxonBase

Cliente PHP (PHP: Hypertext Preprocessor) 8.1+ para o protocolo JSON-RPC (JSON Remote Procedure Call) sobre WebSocket do AxonBase. Este SDK (Software Development Kit) implementa o cliente.

## Instalação

No diretório do SDK, instale as dependências com Composer:

```bash
composer install
```

Em uma aplicação que referencia o pacote, carregue o autoloader do Composer e importe `AxonBase\Axon`.

## Uso

```php
<?php

require 'vendor/autoload.php';

use AxonBase\Axon;

$axon = Axon::connect('ws://127.0.0.1:8000/rpc/ws');
$axon->use('test', 'dev');
$axon->query('CREATE person CONTENT {name: "Ana", age: 30}');

$rows = $axon->select('person');
var_dump($rows);

$axon->close();
```

## Autenticação

Quando o servidor usa `require_auth = true`, autentique a sessão WebSocket antes de consultar. `signin` devolve o JWT (JSON Web Token); em seguida, passe o token para `authenticate`. `authenticate` também aceita um token obtido por outro meio.

```php
$axon = Axon::connect('ws://127.0.0.1:8000/rpc/ws');
$axon->use('test', 'dev');
$token = $axon->signin('root', 'root');
$axon->authenticate($token);
// Ou: $axon->authenticate(getenv('AXON_TOKEN'));

$rows = $axon->query('SELECT * FROM person');
$axon->close();
```

O Bearer é um cabeçalho dos endpoints HTTP: `Authorization: Bearer <JWT>`. Este SDK usa WebSocket, portanto a autorização da sessão é feita por `signin` ou `authenticate`, não por esse cabeçalho.

## Migrações

Use `AxonBase\Migration\Migrator` para aplicar arquivos `.axql` em ordem alfabética. O prefixo antes de `_` é a versão, por exemplo `001_create_person.axql`. As versões aplicadas são registradas na tabela `_migration` com checksum SHA-256 (Secure Hash Algorithm 256-bit), e uma execução posterior não reaplica a mesma versão.

```php
use AxonBase\Migration\Migrator;

$migrator = new Migrator($axon);
$migrator->ensureTable();
$applied = $migrator->up(__DIR__ . '/migrations');
$status = $migrator->status(__DIR__ . '/migrations');
```

`up` cria a tabela de controle quando há migrações pendentes. Ele falha se o conteúdo de uma migração já aplicada for alterado. `onBefore` e `onAfter` recebem cada objeto `Migration` antes e depois de sua aplicação.

Métodos disponíveis: `use`, `signin`, `authenticate`, `query`, `select`, `create`, `insert`, `update`, `upsert`, `delete`, `ping`, `version` e `close`.
