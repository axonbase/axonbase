<?php

declare(strict_types=1);

require __DIR__ . '/../src/Error.php';

spl_autoload_register(function (string $class): void {
    $prefix = 'AxonBase\\';
    if (str_starts_with($class, $prefix)) {
        require __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
    }
});

use AxonBase\Axon;
use AxonBase\Error;
use AxonBase\Migration\Migrator;
use AxonBase\NotLeaderError;
use AxonBase\SagaError;
use AxonBase\SagaParticipantTransaction;
use AxonBase\SagaTransaction;
use AxonBase\Transport;

final class FakeTransport implements Transport {
    public array $sent = [];
    private int $called = 0;
    private array $pending = [];
    public function __construct(private array $frames) {
        $this->pending = $frames;
    }
    public function send(string $message): void { $this->sent[] = json_decode($message, true); }
    public function receive(): string {
        if ($this->pending === []) {
            throw new RuntimeException('no more frames');
        }
        $frame = array_shift($this->pending);
        $this->called++;
        return $frame;
    }
    public function close(): void {}
    public function remaining(): int { return count($this->pending); }
    public function calledCount(): int { return $this->called; }
}

function expect(bool $condition, string $message): void { if (!$condition) throw new RuntimeException($message); }

// --- Existing tests ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1,"server":"test","methods":["use","query"]}}',
    '{"id":1,"result":{"namespace":"app","database":"main"}}',
    '{"notification":{"id":"live-1","action":"CREATE","result":{}}}',
    '{"id":2,"result":[{"name":"Ana"}]}',
]);
$axon = Axon::fromTransport($transport);
expect($axon->hello()['protocol'] === 1, 'hello was not retained');
$axon->use('app', 'main');
expect($axon->namespace() === 'app' && $axon->database() === 'main', 'use state was not retained');
expect($axon->query('SELECT * FROM person', ['when' => ['$datetime' => '2026-01-01T00:00:00Z']])[0]['name'] === 'Ana', 'query result was not returned');
expect($transport->sent[0] === ['id' => 1, 'method' => 'use', 'params' => ['app', 'main'], 'version' => 1], 'use envelope is invalid');
expect($transport->sent[1]['method'] === 'query' && $transport->sent[1]['version'] === 1, 'query envelope is invalid');

$transport = new FakeTransport(['{"hello":{"protocol":1}}', '{"id":1,"error":{"code":-32010,"message":"leader","leader":"n1","leader_address":"127.0.0.1:8000"}}']);
try { Axon::fromTransport($transport)->create('person', []); throw new RuntimeException('expected NotLeaderError'); } catch (NotLeaderError $error) { expect($error->leaderAddress === '127.0.0.1:8000', 'leader address was not retained'); }

$directory = sys_get_temp_dir() . '/axonbase-php-migration-' . uniqid();
mkdir($directory);
$file = $directory . '/001_create_person.axql';
file_put_contents($file, 'DEFINE TABLE person SCHEMAFULL;');
$checksum = hash_file('sha256', $file);
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":[]}',
    '{"id":2,"result":null}',
    '{"id":3,"result":null}',
    '{"id":4,"result":null}',
    '{"id":5,"result":[{"version":"001","checksum":"' . $checksum . '"}]}',
    '{"id":6,"result":[{"version":"001","checksum":"' . $checksum . '"}]}',
]);
$migrator = new Migrator(Axon::fromTransport($transport));
$before = $after = [];
$migrator->onBefore(static function ($migration) use (&$before): void { $before[] = $migration->name; });
$migrator->onAfter(static function ($migration) use (&$after): void { $after[] = $migration->name; });
expect(count($migrator->up($directory)) === 1, 'migration was not applied');
expect($migrator->up($directory) === [], 'applied migration was run again');
expect($migrator->status($directory)[0]['applied'] === true, 'migration status was not applied');
expect($before === ['001_create_person'] && $after === ['001_create_person'], 'migration hooks were not invoked');
expect(str_starts_with($transport->sent[1]['params'][0], 'DEFINE TABLE _migration'), 'migration table was not created');
expect($transport->sent[2]['params'][0] === 'DEFINE TABLE person SCHEMAFULL;', 'migration SQL was not executed');
expect($transport->sent[3]['params'][1]['c'] === $checksum, 'migration checksum was not tracked');
unlink($file);
rmdir($directory);

// --- relate ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":[{"id":"person:1->like->person:2"}]}',
]);
$axon = Axon::fromTransport($transport);
$rel = $axon->relate('person:1', 'like', 'person:2', ['since' => '2026-01-01']);
expect($transport->sent[0]['method'] === 'relate', 'relate envelope method is invalid');
expect($transport->sent[0]['params'] === ['person:1', 'like', 'person:2', ['since' => '2026-01-01']], 'relate params are invalid');
expect($rel[0]['id'] === 'person:1->like->person:2', 'relate result was not returned');

// --- begin / commit / cancel ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":null}',
    '{"id":2,"result":null}',
    '{"id":3,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$axon->begin();
expect($transport->sent[0]['method'] === 'begin', 'begin envelope method is invalid');
$axon->commit();
expect($transport->sent[1]['method'] === 'commit', 'commit envelope method is invalid');
$axon->cancel();
expect($transport->sent[2]['method'] === 'cancel', 'cancel envelope method is invalid');

// --- KV helpers ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"value":"bar"}}',
    '{"id":2,"result":null}',
    '{"id":3,"result":true}',
    '{"id":4,"result":true}',
    '{"id":5,"result":[{"key":"k1","value":"v1"},{"key":"k2","value":"v2"}]}',
    '{"id":6,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$get = $axon->kv_get('ns1', 'db1', 'foo');
expect($transport->sent[0]['method'] === 'kv_get', 'kv_get envelope method is invalid');
expect($transport->sent[0]['params'] === ['ns1', 'db1', 'foo'], 'kv_get params are invalid');
expect($get['value'] === 'bar', 'kv_get result was not returned');

$axon->kv_set('ns1', 'db1', 'k1', 'v1');
expect($transport->sent[1]['method'] === 'kv_set', 'kv_set envelope method is invalid');
expect($transport->sent[1]['params'] === ['ns1', 'db1', 'k1', 'v1'], 'kv_set params are invalid');

$axon->kv_set('ns1', 'db1', 'k2', 'v2', 3600);
expect($transport->sent[2]['method'] === 'kv_set', 'kv_set with ttl envelope method is invalid');
expect($transport->sent[2]['params'] === ['ns1', 'db1', 'k2', 'v2', 3600], 'kv_set with ttl params are invalid');

$del = $axon->kv_del('ns1', 'db1', 'k1');
expect($transport->sent[3]['method'] === 'kv_del', 'kv_del envelope method is invalid');
expect($del === true, 'kv_del result was not returned');

$scan = $axon->kv_scan('ns1', 'db1', 'k');
expect($transport->sent[4]['method'] === 'kv_scan', 'kv_scan envelope method is invalid');
expect($transport->sent[4]['params'] === ['ns1', 'db1', 'k'], 'kv_scan params are invalid');
expect($scan[0]['key'] === 'k1' && $scan[1]['value'] === 'v2', 'kv_scan result was not returned');

// --- live / kill ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":"live-abc"}',
    '{"id":2,"result":true}',
]);
$axon = Axon::fromTransport($transport);
$notifications = [];
$id = $axon->live('person', function (string $id, string $action, $result) use (&$notifications): void {
    $notifications[] = ['id' => $id, 'action' => $action, 'result' => $result];
});
expect($transport->sent[0]['method'] === 'live', 'live envelope method is invalid');
expect($transport->sent[0]['params'] === ['person', false], 'live params are invalid');
expect($id === 'live-abc', 'live id was not returned');

$killed = $axon->kill('live-abc');
expect($transport->sent[1]['method'] === 'kill', 'kill envelope method is invalid');
expect($transport->sent[1]['params'] === ['live-abc'], 'kill params are invalid');
expect($killed === true, 'kill result was not returned');

// notification dispatch via call()
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":"live-xyz"}',
    '{"notification":{"id":"live-xyz","action":"CREATE","result":{"id":"person:1"}}}',
    '{"notification":{"id":"live-xyz","action":"UPDATE","result":{"id":"person:1","name":"Bob"}}}',
    '{"id":2,"result":[{"name":"Ana"}]}',
]);
$axon = Axon::fromTransport($transport);
$notifications = [];
$liveId = $axon->live('person', function (string $id, string $action, $result) use (&$notifications): void {
    $notifications[] = ['action' => $action, 'result' => $result];
});
$axon->query('SELECT * FROM person');
expect(count($notifications) === 2, 'notifications were not dispatched');
expect($notifications[0]['action'] === 'CREATE', 'first notification action is invalid');
expect($notifications[1]['action'] === 'UPDATE', 'second notification action is invalid');
expect($notifications[0]['result']['id'] === 'person:1', 'first notification result is invalid');

// --- certificate helpers ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"id":"cert-1","challenge":"abc123","expires_at":"2026-12-31T23:59:59Z"}}',
    '{"id":2,"result":"pem-certificate-data"}',
]);
$axon = Axon::fromTransport($transport);
$challenge = $axon->certificateBegin('my_store');
expect($transport->sent[0]['method'] === 'certificate.begin', 'certificate.begin envelope method is invalid');
expect($transport->sent[0]['params'] === [['store' => 'my_store']], 'certificate.begin params are invalid');
expect($challenge['id'] === 'cert-1' && $challenge['challenge'] === 'abc123', 'certificate.begin result was not returned');

$completion = $axon->certificateComplete([
    'id' => 'cert-1',
    'challenge' => 'abc123',
    'store' => 'my_store',
    'user' => 'admin',
    'chain' => ['cert1', 'cert2'],
    'signature' => 'sig',
]);
expect($transport->sent[1]['method'] === 'certificate.complete', 'certificate.complete envelope method is invalid');
expect($transport->sent[1]['params'][0]['id'] === 'cert-1', 'certificate.complete params are invalid');
expect($completion === 'pem-certificate-data', 'certificate.complete result was not returned');

// --- SagaTransaction ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"status":"RUNNING"}}',
    '{"id":2,"result":[{"id":"person:1"}]}',
    '{"id":3,"result":{"status":"COMMITTED"}}',
]);
$axon = Axon::fromTransport($transport);
$saga = new SagaTransaction($axon, 'test_saga', 'corr-123');
expect($saga->sagaName() === 'test_saga', 'saga name was not retained');
expect($saga->correlationId() === 'corr-123', 'saga correlation id was not retained');
expect($saga->isBegun() === false, 'saga should not be begun');
expect($saga->isFinished() === false, 'saga should not be finished');

$saga->begin();
expect($saga->isBegun() === true, 'saga should be begun after begin');
expect(str_contains($transport->sent[0]['params'][0], "BEGIN SAGA test_saga WITH CORRELATION 'corr-123'"), 'saga begin SQL is invalid');

$saga->step('CREATE person SET name = "Alice"');
expect($transport->sent[1]['params'][0] === 'CREATE person SET name = "Alice"', 'saga step SQL is invalid');

$saga->commit();
expect($saga->isFinished() === true, 'saga should be finished after commit');
expect(str_contains($transport->sent[2]['params'][0], "COMMIT SAGA test_saga WITH CORRELATION 'corr-123'"), 'saga commit SQL is invalid');

// saga rollback
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"status":"RUNNING"}}',
    '{"id":2,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$saga = new SagaTransaction($axon, 'test_saga', 'corr-456');
$saga->begin();
$saga->rollback();
expect($saga->isFinished() === true, 'saga should be finished after rollback');
expect(str_contains($transport->sent[1]['params'][0], "CANCEL SAGA test_saga WITH CORRELATION 'corr-456'"), 'saga rollback SQL is invalid');

// saga double begin error
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"status":"RUNNING"}}',
]);
$axon = Axon::fromTransport($transport);
$saga = new SagaTransaction($axon, 's', 'c');
$saga->begin();
try { $saga->begin(); throw new RuntimeException('expected SagaError'); } catch (SagaError $e) { expect(true, 'saga double begin threw SagaError'); }

// saga step without begin
$transport = new FakeTransport(['{"hello":{"protocol":1}}']);
$axon = Axon::fromTransport($transport);
$saga = new SagaTransaction($axon, 's', 'c');
try { $saga->step('SELECT 1'); throw new RuntimeException('expected SagaError'); } catch (SagaError $e) { expect(true, 'saga step without begin threw SagaError'); }

// saga describe
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":{"status":"RUNNING"}}',
    '{"id":2,"result":{"status":"RUNNING","steps":[]}}',
]);
$axon = Axon::fromTransport($transport);
$saga = new SagaTransaction($axon, 'test_saga', 'corr-789');
$saga->begin();
$desc = $saga->describe();
expect($desc['status'] === 'RUNNING', 'saga describe result is invalid');
expect(str_contains($transport->sent[1]['params'][0], "SHOW SAGA TRANSACTION test_saga 'corr-789'"), 'saga describe SQL is invalid');

// saga escape
expect(SagaTransaction::escape("a\\b'c") === "a\\\\b''c", 'saga escape is invalid');
expect(SagaTransaction::escape("normal") === "normal", 'saga escape of normal string is invalid');

// --- SagaParticipantTransaction ---
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":null}',
    '{"id":2,"result":null}',
    '{"id":3,"result":[{"id":"person:1"}]}',
    '{"id":4,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$pt = new SagaParticipantTransaction($axon, 'corr-999');
expect($pt->correlationId() === 'corr-999', 'participant correlation id was not retained');
expect($pt->isBegun() === false, 'participant should not be begun');
expect($pt->isFinished() === false, 'participant should not be finished');

$pt->begin();
expect($pt->isBegun() === true, 'participant should be begun after begin');
expect($transport->sent[0]['method'] === 'begin', 'participant begin RPC method is invalid');
expect($transport->sent[1]['method'] === 'query', 'participant let var query method is invalid');
expect(str_contains($transport->sent[1]['params'][0], "LET \$saga_corr"), 'participant LET var is invalid');

$pt->step('CREATE person SET name = "Bob"');
expect($transport->sent[2]['params'][0] === 'CREATE person SET name = "Bob"', 'participant step SQL is invalid');

$pt->commit();
expect($pt->isFinished() === true, 'participant should be finished after commit');
expect($transport->sent[3]['method'] === 'commit', 'participant commit RPC method is invalid');

// participant rollback
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":null}',
    '{"id":2,"result":null}',
    '{"id":3,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$pt = new SagaParticipantTransaction($axon, 'corr-111');
$pt->begin();
$pt->rollback();
expect($pt->isFinished() === true, 'participant should be finished after rollback');
expect($transport->sent[2]['method'] === 'cancel', 'participant rollback RPC method is invalid');

// participant double begin error
$transport = new FakeTransport([
    '{"hello":{"protocol":1}}',
    '{"id":1,"result":null}',
    '{"id":2,"result":null}',
]);
$axon = Axon::fromTransport($transport);
$pt = new SagaParticipantTransaction($axon, 'c');
$pt->begin();
try { $pt->begin(); throw new RuntimeException('expected SagaError'); } catch (SagaError $e) { expect(true, 'participant double begin threw SagaError'); }

// participant step without begin
$transport = new FakeTransport(['{"hello":{"protocol":1}}']);
$axon = Axon::fromTransport($transport);
$pt = new SagaParticipantTransaction($axon, 'c');
try { $pt->step('SELECT 1'); throw new RuntimeException('expected SagaError'); } catch (SagaError $e) { expect(true, 'participant step without begin threw SagaError'); }

// participant rollback without begin (idempotent)
$transport = new FakeTransport(['{"hello":{"protocol":1}}']);
$axon = Axon::fromTransport($transport);
$pt = new SagaParticipantTransaction($axon, 'c');
$pt->rollback();
expect($pt->isFinished() === false, 'participant rollback without begin should not mark finished');

print "PHP SDK tests passed\n";