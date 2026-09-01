<?php

declare(strict_types=1);

namespace AxonBase;

final class SagaParticipantTransaction
{
    private bool $begun = false;
    private bool $finished = false;

    public function __construct(
        private readonly Axon $axon,
        private readonly string $correlationId,
    ) {}

    public function correlationId(): string { return $this->correlationId; }
    public function isBegun(): bool { return $this->begun; }
    public function isFinished(): bool { return $this->finished; }

    public function begin(): void
    {
        if ($this->begun) {
            throw new SagaError("Transaction already begun: {$this->correlationId}");
        }
        $this->axon->begin();
        try {
            $this->axon->query("LET \$saga_corr = '{}'", ['{}' => $this->correlationId]);
        } catch (\Throwable $e) {
            $this->axon->cancel();
            throw $e;
        }
        $this->begun = true;
    }

    public function step(string $axonql): mixed
    {
        $this->ensureActive();
        return $this->axon->query($axonql);
    }

    public function commit(): void
    {
        $this->ensureActive();
        $this->axon->commit();
        $this->finished = true;
    }

    public function rollback(): void
    {
        if (!$this->begun || $this->finished) {
            return;
        }
        $this->axon->cancel();
        $this->finished = true;
    }

    private function ensureActive(): void
    {
        if (!$this->begun) {
            throw new SagaError('Transaction not begun');
        }
        if ($this->finished) {
            throw new SagaError('Transaction already finished');
        }
    }
}