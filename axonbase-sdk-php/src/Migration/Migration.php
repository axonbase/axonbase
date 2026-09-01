<?php

declare(strict_types=1);

namespace AxonBase\Migration;

final class Migration
{
    public function __construct(
        public readonly string $version,
        public readonly string $name,
        public readonly string $path,
        public readonly string $checksum,
        public readonly string $sql,
    ) {}
}
