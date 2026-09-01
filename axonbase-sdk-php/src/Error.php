<?php

declare(strict_types=1);

namespace AxonBase;

class Error extends \RuntimeException
{
    public function __construct(string $message, int $code = 0)
    {
        parent::__construct($message, $code);
    }
}

final class AuthError extends Error {}
final class ConflictError extends Error {}
final class NoQuorumError extends Error {}
final class RateLimitError extends Error {}

final class NotLeaderError extends Error
{
    public function __construct(string $message, public readonly string $leader = '', public readonly string $leaderAddress = '')
    {
        parent::__construct($message, -32010);
    }
}

final class ProtocolMismatchError extends Error
{
    public function __construct(string $message, public readonly int $protocol, public readonly int $serverVersion)
    {
        parent::__construct($message, -32600);
    }
}

final class SagaError extends Error
{
    public function __construct(string $message)
    {
        parent::__construct($message, 0);
    }
}
