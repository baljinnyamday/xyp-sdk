<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Psr\Log\LoggerInterface;
use Xyp\Mismatch;

/**
 * Writes the one log line this SDK produces. Field paths and problems only: the
 * values are citizen data and stay out of logs.
 *
 * @internal
 */
final class MismatchReporter
{
    public const ISSUES_URL = 'https://github.com/baljinnyamday/xyp-sdk/issues';

    public const WARNING = "XYP's response does not fit the SDK's model. The call succeeded and "
        . 'nothing was lost: the listed fields kept their default value and their raw values are in the '
        . "returned object's ->xyp->mismatches. This is a gap in the SDK's models, generated from XYP's public "
        . 'catalog — not an error from XYP or in your code. Please report it at ' . self::ISSUES_URL;

    public function __construct(private readonly ?LoggerInterface $logger) {}

    /**
     * @param list<Mismatch> $mismatches
     */
    public function report(string $operation, array $mismatches): void
    {
        if ($mismatches === []) {
            return;
        }
        $fields = implode('; ', array_map(static fn(Mismatch $mismatch): string => (string) $mismatch, $mismatches));
        if ($this->logger !== null) {
            $this->logger->warning(self::WARNING, ['operation' => $operation, 'fields' => $fields]);

            return;
        }
        error_log(sprintf('xyp: %s operation=%s fields=%s', self::WARNING, $operation, $fields));
    }
}
