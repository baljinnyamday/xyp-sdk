<?php

declare(strict_types=1);

namespace Xyp;

use Psr\Log\LoggerInterface;
use Xyp\Exception\ApiException;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\ResponseException;
use Xyp\Http\Request;
use Xyp\Http\Transport;
use Xyp\Internal\Decode\Decoder;
use Xyp\Internal\Decode\Schemas;
use Xyp\Internal\Envelope;
use Xyp\Internal\MismatchReporter;
use Xyp\Internal\ResponseReader;
use Xyp\Internal\Settings;
use Xyp\Internal\Signer;

/**
 * Talks to XYP. Build one and share it: it keeps its connection alive and
 * remembers the namespaces it has read.
 *
 *     $xyp = new XypClient(accessToken: $token, privateKey: 'private.key');
 *     $card = $xyp->citizen->getCitizenIDCardInfo(new GetCitizenIDCardInfoParams(regnum: 'РД00000000'));
 *
 * Services are grouped by XYP endpoint ($xyp->citizen, $xyp->insurance, ...) and
 * keep XYP's own names minus the code: WS100101_getCitizenIDCardInfo is
 * $xyp->citizen->getCitizenIDCardInfo(). call() and invoke() reach any operation
 * by its original name, including ones newer than this SDK.
 *
 * Every exception implements Exception\XypException, whose origin() says whose
 * side the problem is on.
 */
final class XypClient
{
    use ServiceGroups;

    public const DEFAULT_BASE_URL = Settings::DEFAULT_BASE_URL;

    public const DEFAULT_TIMEOUT = 30.0;

    /** Read when accessToken: is not given. */
    public const ACCESS_TOKEN_ENV = Settings::ACCESS_TOKEN_ENV;

    /** Read, as a path to the key file, when privateKey: is not given. */
    public const PRIVATE_KEY_ENV = Settings::PRIVATE_KEY_ENV;

    /** Where a model mismatch should be reported. */
    public const ISSUES_URL = MismatchReporter::ISSUES_URL;

    private const SOAP_CONTENT_TYPE = 'text/xml; charset=utf-8';

    /**
     * One URL path segment such as "citizen-1.5.0". endpoint: comes from the caller,
     * and must not be able to reach another path or host.
     */
    private const ENDPOINT_NAME = '/^[A-Za-z0-9][A-Za-z0-9._-]*$/D';

    /** Reads the namespace of an endpoint the generated registry does not know. */
    private const TARGET_NAMESPACE = '/targetNamespace="([^"]+)"/';

    private readonly Signer $signer;

    private readonly string $baseUrl;

    /** Not $transport: that name belongs to the generated `transport` service group. */
    private readonly Transport $http;

    private readonly MismatchReporter $reporter;

    /** @var array<string, string> namespaces read from a live WSDL, by endpoint */
    private array $namespaces = [];

    /**
     * Validates the options and loads the key. Performs no I/O against XYP.
     *
     * @param ?string                           $accessToken the token issued by the National Data Center;
     *                                                       falls back to XYP_ACCESS_TOKEN
     * @param string|\OpenSSLAsymmetricKey|null $privateKey  the RSA key that signs every request: a file path
     *                                                       (PEM or DER), PEM text or a loaded key; null reads
     *                                                       the file XYP_PRIVATE_KEY names
     * @param ?string                           $passphrase  for an encrypted key
     * @param string                            $baseUrl     change it only when you reach XYP through your own
     *                                                       proxy; must start with https:// or http://
     * @param float                             $timeout     seconds one whole HTTP request may take
     * @param bool|string                       $verify      true trusts the bundled national CAs; false turns
     *                                                       verification off (read docs/tls.md first); a string
     *                                                       is a CA file path or PEM text
     * @param ?LoggerInterface                  $logger      receives the one warning this SDK writes, when a
     *                                                       response does not fit its model; error_log() by default
     * @param ?Transport                        $transport   sends the requests; timeout and verify only
     *                                                       configure the default CurlTransport
     *
     * @throws ConfigException
     */
    public function __construct(
        #[\SensitiveParameter]
        ?string $accessToken = null,
        #[\SensitiveParameter]
        string|\OpenSSLAsymmetricKey|null $privateKey = null,
        #[\SensitiveParameter]
        ?string $passphrase = null,
        string $baseUrl = self::DEFAULT_BASE_URL,
        float $timeout = self::DEFAULT_TIMEOUT,
        bool|string $verify = true,
        ?LoggerInterface $logger = null,
        ?Transport $transport = null,
    ) {
        $settings = Settings::resolve(
            $accessToken,
            $privateKey,
            $passphrase,
            $baseUrl,
            $timeout,
            $verify,
            $transport,
            static fn(): int => time(),
        );
        $this->signer = $settings->signer;
        $this->baseUrl = $settings->baseUrl;
        $this->http = $settings->transport;
        $this->reporter = new MismatchReporter($logger);
        $this->initServiceGroups();
    }

    /**
     * Runs a service by its original XYP name and returns the response tree: nested
     * arrays keyed by element name, lists for repeated elements, strings and nulls.
     *
     *     $tree = $xyp->call('WS100101_getCitizenIDCardInfo', ['regnum' => 'РД00000000']);
     *
     * @param array<string, mixed>|object|null $params sent in array order, or an object's public
     *                                                 properties in declaration order
     * @param ?string                          $endpoint e.g. "citizen-1.5.0", for a service newer than
     *                                                   this SDK version
     *
     * @throws ConfigException     when the call is set up incorrectly; nothing was sent
     * @throws ConnectionException when XYP could not be reached
     * @throws ResponseException   when XYP answered with something that is not a service response
     * @throws ApiException        when XYP answered with a non-zero resultCode
     */
    public function call(
        string $operation,
        array|object|null $params = null,
        ?Auth $auth = null,
        ?Auth $operator = null,
        ?string $endpoint = null,
    ): mixed {
        return $this->send($operation, $params, $auth, $operator, $endpoint);
    }

    /**
     * Runs a service and builds $class from the response.
     *
     * A field the response does not fit keeps its default and is listed in the
     * class's Extras parameter (->xyp on generated classes); the call itself still
     * succeeds, because the SDK's models come from a hand-typed catalog and real
     * data sometimes disagrees with them. Extras::$raw holds the whole response.
     *
     * @template T of object
     *
     * @param array<string, mixed>|object|null $params
     * @param class-string<T>                  $class
     *
     * @return T
     *
     * @throws ConfigException     when $class cannot be built; checked before anything is sent
     * @throws ConnectionException
     * @throws ResponseException
     * @throws ApiException
     */
    public function invoke(
        string $operation,
        array|object|null $params,
        string $class,
        ?Auth $auth = null,
        ?Auth $operator = null,
        ?string $endpoint = null,
    ): object {
        // A class the SDK cannot fill is a mistake in the caller's code; there is no
        // reason to ask XYP for data first.
        Schemas::of($class);
        $tree = $this->send($operation, $params, $auth, $operator, $endpoint);
        [$object, $mismatches] = Decoder::decode($class, $tree);
        $this->reporter->report($operation, $mismatches);

        return $object;
    }

    /**
     * Shows the base URL and nothing else: the client holds the access token and
     * the private key.
     *
     * @return array{baseUrl: string}
     */
    public function __debugInfo(): array
    {
        return ['baseUrl' => $this->baseUrl];
    }

    /**
     * @return never
     */
    public function __serialize(): array
    {
        throw new \LogicException('an XypClient holds a private key and cannot be serialized; build a new one instead');
    }

    /**
     * @param array<mixed> $data
     */
    public function __unserialize(array $data): never
    {
        throw new \LogicException('an XypClient holds a private key and cannot be unserialized; build a new one instead');
    }

    /**
     * @param array<string, mixed>|object|null $params
     */
    private function send(string $operation, array|object|null $params, ?Auth $auth, ?Auth $operator, ?string $endpoint): mixed
    {
        $endpoint ??= Registry::ENDPOINTS[$operation] ?? throw new ConfigException(sprintf(
            'unknown operation "%s". Pass endpoint: \'<name>-<version>\' to call a service this SDK version does not know about.',
            $operation,
        ));
        if (preg_match(self::ENDPOINT_NAME, $endpoint) !== 1) {
            throw new ConfigException(sprintf('endpoint "%s" is not a name like "citizen-1.5.0"', $endpoint));
        }
        // Built before the namespace is looked up, so a bad parameter costs no request.
        $body = Envelope::operation($operation, $params, $auth, $operator);
        $address = $this->baseUrl . '/' . $endpoint . '/ws';
        $envelope = Envelope::wrap($this->namespaceOf($endpoint, $address), $body);
        $headers = [
            'Content-Type' => self::SOAP_CONTENT_TYPE,
            'SOAPAction' => '""',
        ] + $this->signer->headers();

        return ResponseReader::unwrap($this->http->send(new Request('POST', $address, $headers, $envelope)));
    }

    /**
     * The endpoint's XML namespace, read from the live WSDL the first time an
     * endpoint outside the generated registry is used.
     */
    private function namespaceOf(string $endpoint, string $address): string
    {
        $known = $this->namespaces[$endpoint] ?? Registry::NAMESPACES[$endpoint] ?? null;
        if ($known !== null) {
            return $known;
        }
        $response = $this->http->send(new Request('GET', $address . '?WSDL'));
        if ($response->status >= ResponseReader::HTTP_ERROR_STATUS
            || preg_match(self::TARGET_NAMESPACE, $response->body, $match) !== 1) {
            throw new ResponseException(sprintf('could not read the WSDL of endpoint "%s"', $endpoint), $response->status);
        }

        return $this->namespaces[$endpoint] = $match[1];
    }
}
