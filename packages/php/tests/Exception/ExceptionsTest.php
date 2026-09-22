<?php

declare(strict_types=1);

namespace Xyp\Tests\Exception;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Exception\AccessDeniedException;
use Xyp\Exception\ApiException;
use Xyp\Exception\AuthRequiredException;
use Xyp\Exception\CitizenDataException;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\FingerprintException;
use Xyp\Exception\InternalException;
use Xyp\Exception\InvalidRequestException;
use Xyp\Exception\NotFoundException;
use Xyp\Exception\Origin;
use Xyp\Exception\ProviderException;
use Xyp\Exception\ResponseException;
use Xyp\Exception\SignatureException;
use Xyp\Exception\TimeoutException;

final class ExceptionsTest extends TestCase
{
    /**
     * @param class-string<ApiException> $class
     */
    #[DataProvider('documentedCodes')]
    public function testEveryDocumentedCodeMapsToItsSubclass(int $code, string $class): void
    {
        $error = ApiException::fromResult($code, 'm', 'id');

        self::assertSame($class, $error::class);
        self::assertSame($code, $error->resultCode);
        self::assertSame($code, $error->getCode());
        self::assertSame('m', $error->resultMessage);
        self::assertSame('id', $error->requestId);
        self::assertSame('[' . $code . '] m', $error->getMessage());
        self::assertSame(Origin::Xyp, $error->origin());
    }

    /**
     * @return iterable<string, array{int, class-string<ApiException>}>
     */
    public static function documentedCodes(): iterable
    {
        $groups = [
            NotFoundException::class => [1],
            InternalException::class => [2],
            InvalidRequestException::class => [3],
            AuthRequiredException::class => [200, 201, 202],
            AccessDeniedException::class => [203, 501],
            FingerprintException::class => [301, 302, 303, 304],
            CitizenDataException::class => [401, 402],
            SignatureException::class => [601, 602, 603, 604, 605],
            ProviderException::class => [801, 802],
            ApiException::class => [9999, 4, 199, 204, 300, 305, 500, 502, 600, 606, 800, 803, -1],
        ];
        foreach ($groups as $class => $codes) {
            foreach ($codes as $code) {
                yield (string) $code => [$code, $class];
            }
        }
    }

    public function testEveryExceptionSaysWhoseSideItIsOn(): void
    {
        // The class hierarchy itself (XypException, InvalidArgumentException,
        // RuntimeException) is enforced by the type system and PHPStan.
        $cases = [
            [new ConfigException('x'), Origin::Config],
            [ConnectionException::because('refused'), Origin::Network],
            [TimeoutException::timedOut(), Origin::Network],
            [new ResponseException('x', 502), Origin::Xyp],
            [ApiException::fromResult(1, 'm', ''), Origin::Xyp],
        ];
        foreach ($cases as [$error, $origin]) {
            self::assertSame($origin, $error->origin());
        }
    }

    public function testConnectionMessagesNameTheCauseAndWhatToCheck(): void
    {
        $cause = new \RuntimeException('curl said no');
        $error = ConnectionException::because('Connection refused', $cause);

        self::assertSame(
            'could not reach XYP (Connection refused). Check the VPN connection, the hosts entry for xyp.gov.mn and the TLS settings.',
            $error->getMessage(),
        );
        self::assertSame($cause, $error->getPrevious());
        self::assertStringContainsString('(timeout)', TimeoutException::timedOut()->getMessage());
    }

    public function testAResponseExceptionKeepsItsStatus(): void
    {
        $previous = new ResponseException('inner');
        $error = new ResponseException('outer', 500, $previous);

        self::assertSame(500, $error->statusCode);
        self::assertSame($previous, $error->getPrevious());
        self::assertNull($previous->statusCode);
    }
}
