<?php

declare(strict_types=1);

namespace AxonBase;

interface Transport
{
    public function send(string $message): void;
    public function receive(): string;
    public function close(): void;
}

final class WebSocketTransport implements Transport
{
    private object $client;

    public function __construct(string $url, array $sslContext = [])
    {
        if (!class_exists(\WebSocket\Client::class)) {
            throw new Error('textalk/websocket is required; run composer install');
        }
        $this->client = new \WebSocket\Client($url, ['context' => $sslContext]);
    }

    public function send(string $message): void
    {
        $this->client->send($message);
    }

    public function receive(): string
    {
        return $this->client->receive();
    }

    public function close(): void
    {
        $this->client->close();
    }
}
