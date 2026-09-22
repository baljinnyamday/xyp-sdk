<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

use Psr\Log\AbstractLogger;

final class RecordingLogger extends AbstractLogger
{
    /** @var list<array{level: mixed, message: string, context: array<mixed>}> */
    public array $records = [];

    /**
     * @param array<mixed> $context
     */
    public function log(mixed $level, string|\Stringable $message, array $context = []): void
    {
        $this->records[] = ['level' => $level, 'message' => (string) $message, 'context' => $context];
    }

    /** Everything written, as one string, for "does it mention X" assertions. */
    public function dump(): string
    {
        return implode("\n", array_map(
            static fn(array $record): string => $record['message'] . ' ' . json_encode($record['context'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR),
            $this->records,
        ));
    }
}
