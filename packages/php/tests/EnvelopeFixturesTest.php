<?php

declare(strict_types=1);

namespace Xyp\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Auth;
use Xyp\Internal\Envelope;

/**
 * The fixtures are the Python SDK's output, which is verified against zeep (the
 * SOAP library the known-working XYP clients use) for every operation in
 * spec/wsdl. Same input, same bytes.
 */
final class EnvelopeFixturesTest extends TestCase
{
    /** The WSDLs we hold cover this many operations; fewer means the export went wrong. */
    private const MINIMUM_FIXTURES = 160;

    public function testTheFixtureFileHoldsEveryOperation(): void
    {
        self::assertGreaterThan(self::MINIMUM_FIXTURES, \count(self::fixtures()));
    }

    /**
     * @param array<string, mixed> $params
     */
    #[DataProvider('fixtureCases')]
    public function testEnvelopeIsByteIdenticalToTheZeepVerifiedFixture(
        string $operation,
        string $namespace,
        array $params,
        ?Auth $citizen,
        ?Auth $operator,
        string $envelope,
    ): void {
        self::assertSame($envelope, Envelope::build($operation, $namespace, $params, $citizen, $operator));
    }

    /**
     * @return iterable<string, array{string, string, array<string, mixed>, ?Auth, ?Auth, string}>
     */
    public static function fixtureCases(): iterable
    {
        foreach (self::fixtures() as $fixture) {
            $params = [];
            // A PHP array keeps paramOrder, the schema order the service expects.
            foreach ($fixture['paramOrder'] as $name) {
                self::assertArrayHasKey($name, $fixture['params'], "paramOrder names {$name}, which params does not hold");
                $value = $fixture['params'][$name];
                if (\in_array($name, $fixture['dateFields'], true)) {
                    self::assertIsString($value);
                    $value = new \DateTimeImmutable($value);
                }
                $params[$name] = $value;
            }
            $citizen = $operator = null;
            if ($fixture['auth'] !== null) {
                $citizen = Auth::otp($fixture['auth']['citizen']['regnum'], $fixture['auth']['citizen']['otp']);
                $fingerprint = base64_decode($fixture['auth']['operator']['fingerprintBase64'], true);
                self::assertIsString($fingerprint);
                $operator = Auth::fingerprint($fixture['auth']['operator']['regnum'], $fingerprint);
            }

            yield $fixture['operation'] => [$fixture['operation'], $fixture['namespace'], $params, $citizen, $operator, $fixture['envelope']];
        }
    }

    /**
     * @return list<array{
     *     operation: string,
     *     namespace: string,
     *     params: array<string, mixed>,
     *     paramOrder: list<string>,
     *     dateFields: list<string>,
     *     auth: ?array{citizen: array{regnum: string, otp: int}, operator: array{regnum: string, fingerprintBase64: string}},
     *     envelope: string,
     * }>
     */
    private static function fixtures(): array
    {
        $payload = file_get_contents(__DIR__ . '/fixtures/envelopes.json');
        self::assertIsString($payload);
        $fixtures = json_decode($payload, true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($fixtures);

        /** @var list<array{operation: string, namespace: string, params: array<string, mixed>, paramOrder: list<string>, dateFields: list<string>, auth: ?array{citizen: array{regnum: string, otp: int}, operator: array{regnum: string, fingerprintBase64: string}}, envelope: string}> $fixtures */
        return $fixtures;
    }
}
