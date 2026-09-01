<?php

declare(strict_types=1);

namespace AxonBase;

final class SagaTransaction
{
    private bool $begun = false;
    private bool $finished = false;

    public function __construct(
        private readonly Axon $axon,
        private readonly string $sagaName,
        private readonly string $correlationId,
    ) {}

    public static function escape(string $value): string
    {
        return str_replace(['\\', '\''], ['\\\\', "''"], $value);
    }

    public function sagaName(): string { return $this->sagaName; }
    public function correlationId(): string { return $this->correlationId; }
    public function isBegun(): bool { return $this->begun; }
    public function isFinished(): bool { return $this->finished; }

    public function begin(): void
    {
        if ($this->begun) {
            throw new SagaError("Saga already begun: {$this->correlationId}");
        }
        $result = $this->axon->query(
            "BEGIN SAGA " . self::escape($this->sagaName)
            . " WITH CORRELATION '" . self::escape($this->correlationId) . "'"
        );
        if (is_array($result) && ($result['status'] ?? null) === 'RUNNING') {
            $this->begun = true;
            return;
        }
        throw new SagaError('Failed to begin saga: ' . json_encode($result));
    }

    public function step(string $axonql): mixed
    {
        $this->ensureActive();
        return $this->axon->query($axonql);
    }

    public function commit(): void
    {
        $this->ensureActive();
        $result = $this->axon->query(
            "COMMIT SAGA " . self::escape($this->sagaName)
            . " WITH CORRELATION '" . self::escape($this->correlationId) . "'"
        );
        $this->finished = true;
        if (is_array($result) && ($result['status'] ?? null) === 'COMMITTED') {
            return;
        }
        throw new SagaError('Saga commit failed: ' . json_encode($result));
    }

    public function rollback(): void
    {
        if (!$this->begun || $this->finished) {
            return;
        }
        $this->axon->query(
            "CANCEL SAGA " . self::escape($this->sagaName)
            . " WITH CORRELATION '" . self::escape($this->correlationId) . "'"
        );
        $this->finished = true;
    }

    public function describe(): mixed
    {
        return $this->axon->query(
            "SHOW SAGA TRANSACTION " . self::escape($this->sagaName)
            . " '" . self::escape($this->correlationId) . "'"
        );
    }

    private function ensureActive(): void
    {
        if (!$this->begun) {
            throw new SagaError('Saga not begun');
        }
        if ($this->finished) {
            throw new SagaError('Saga already finished');
        }
    }
}