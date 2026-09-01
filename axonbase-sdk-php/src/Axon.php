<?php

declare(strict_types=1);

namespace AxonBase;

final class Axon
{
    public const PROTOCOL_VERSION = 1;

    private int $nextId = 1;
    private array $hello;
    private string $namespace = '';
    private string $database = '';
    private ?string $token = null;

    /** @var array<string, callable> */
    private array $liveHandlers = [];

    private function __construct(private readonly Transport $transport, private readonly int $timeoutSeconds = 30)
    {
        $frame = $this->decode($transport->receive());
        if (!isset($frame['hello']) || !is_array($frame['hello'])) {
            throw new Error('expected hello as first WebSocket frame');
        }
        $this->hello = $frame['hello'];
    }

    public static function connect(string $url, int $timeoutSeconds = 30): self
    {
        return new self(new WebSocketTransport($url), $timeoutSeconds);
    }

    /** @internal Enables deterministic tests and custom WebSocket implementations. */
    public static function fromTransport(Transport $transport, int $timeoutSeconds = 30): self
    {
        return new self($transport, $timeoutSeconds);
    }

    public function use(string $namespace, string $database): array
    {
        $result = $this->call('use', [$namespace, $database]);
        $this->namespace = (string) ($result['namespace'] ?? $namespace);
        $this->database = (string) ($result['database'] ?? $database);
        return $result;
    }

    public function signin(string $user, string $pass, ?string $access = null): string
    {
        $credentials = ['user' => $user, 'pass' => $pass];
        if ($access !== null) {
            $credentials['access'] = $access;
        }
        return $this->token = (string) $this->call('signin', [$credentials]);
    }

    public function authenticate(string $token): void
    {
        $this->call('authenticate', [$token]);
        $this->token = $token;
    }

    public function query(string $sql, ?array $vars = null): mixed
    {
        return $this->call('query', $vars === null ? [$sql] : [$sql, $vars]);
    }

    public function select(string $target, mixed $where = null): mixed
    {
        return $this->call('select', $where === null ? [$target] : [$target, $where]);
    }

    public function create(string $target, array $data): mixed { return $this->call('create', [$target, $data]); }
    public function insert(string $table, array $data): mixed { return $this->call('insert', [$table, $data]); }
    public function update(string $target, array $data): mixed { return $this->call('update', [$target, $data]); }
    public function upsert(string $target, array $data): mixed { return $this->call('upsert', [$target, $data]); }
    public function delete(string $target, mixed $where = null): mixed { return $this->call('delete', $where === null ? [$target] : [$target, $where]); }
    public function ping(): bool { return (bool) $this->call('ping'); }
    public function version(): string { return (string) $this->call('version'); }
    public function namespace(): string { return $this->namespace; }
    public function database(): string { return $this->database; }
    public function hello(): array { return $this->hello; }
    public function close(): void { $this->transport->close(); }

    public function relate(string $from, string $kind, string $to, array $data): mixed
    {
        return $this->call('relate', [$from, $kind, $to, $data]);
    }

    public function begin(): void
    {
        $this->call('begin');
    }

    public function commit(): void
    {
        $this->call('commit');
    }

    public function cancel(): void
    {
        $this->call('cancel');
    }

    public function kv_get(string $ns, string $db, string $key): mixed
    {
        return $this->call('kv_get', [$ns, $db, $key]);
    }

    public function kv_set(string $ns, string $db, string $key, mixed $value, ?int $ttl = null): mixed
    {
        $params = [$ns, $db, $key, $value];
        if ($ttl !== null) {
            $params[] = $ttl;
        }
        return $this->call('kv_set', $params);
    }

    public function kv_del(string $ns, string $db, string $key): mixed
    {
        return $this->call('kv_del', [$ns, $db, $key]);
    }

    public function kv_scan(string $ns, string $db, string $prefix): mixed
    {
        return $this->call('kv_scan', [$ns, $db, $prefix]);
    }

    public function live(string $table, callable $handler, bool $diff = false): string
    {
        $id = (string) $this->call('live', [$table, $diff]);
        $this->liveHandlers[$id] = $handler;
        return $id;
    }

    public function kill(string $id): mixed
    {
        $result = $this->call('kill', [$id]);
        unset($this->liveHandlers[$id]);
        return $result;
    }

    public function certificateBegin(string $store): mixed
    {
        return $this->call('certificate.begin', [['store' => $store]]);
    }

    public function certificateComplete(array $completion): mixed
    {
        return $this->call('certificate.complete', [$completion]);
    }

    private function call(string $method, array $params = []): mixed
    {
        $id = $this->nextId++;
        $this->transport->send(json_encode([
            'id' => $id,
            'method' => $method,
            'params' => $params,
            'version' => self::PROTOCOL_VERSION,
        ], JSON_THROW_ON_ERROR));

        while (true) {
            $response = $this->decode($this->transport->receive());
            if (isset($response['notification'])) {
                $this->dispatchNotification($response['notification']);
                continue;
            }
            if (($response['id'] ?? null) !== $id) {
                continue;
            }
            if (isset($response['error'])) {
                throw $this->rpcError($response['error']);
            }
            return $response['result'] ?? null;
        }
    }

    private function dispatchNotification(array $notification): void
    {
        $id = (string) ($notification['id'] ?? '');
        if ($id === '' || !isset($this->liveHandlers[$id])) {
            return;
        }
        $action = (string) ($notification['action'] ?? '');
        $result = $notification['result'] ?? null;
        ($this->liveHandlers[$id])($id, $action, $result);
    }

    private function decode(string $frame): array
    {
        $decoded = json_decode($frame, true, flags: JSON_THROW_ON_ERROR);
        if (!is_array($decoded)) {
            throw new Error('invalid JSON-RPC frame');
        }
        return $decoded;
    }

    private function rpcError(array $error): Error
    {
        $message = (string) ($error['message'] ?? 'RPC error');
        $code = (int) ($error['code'] ?? 0);
        return match ($code) {
            -32002 => new AuthError($message, $code),
            -32009 => new ConflictError($message, $code),
            -32010 => new NotLeaderError($message, (string) ($error['leader'] ?? ''), (string) ($error['leader_address'] ?? '')),
            -32011 => new NoQuorumError($message, $code),
            -32029 => new RateLimitError($message, $code),
            -32600 => ($error['kind'] ?? '') === 'PROTOCOL_MISMATCH'
                ? new ProtocolMismatchError($message, (int) ($error['protocol'] ?? 0), (int) ($error['version'] ?? 0))
                : new Error($message, $code),
            default => new Error($message, $code),
        };
    }
}