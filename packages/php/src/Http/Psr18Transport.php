<?php

declare(strict_types=1);

namespace Xyp\Http;

use Psr\Http\Client\ClientExceptionInterface;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestFactoryInterface;
use Psr\Http\Message\StreamFactoryInterface;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\TimeoutException;

/**
 * Sends requests through your own PSR-18 client (Guzzle, Symfony HttpClient, ...),
 * for when your application already configures proxies, retries or telemetry there.
 * Needs psr/http-client and psr/http-factory, which this package only suggests.
 *
 * TLS is then your client's job, and so are the timeout and verify options of
 * XypClient, which this transport cannot apply. XYP's certificate is issued by the
 * Mongolian national PKI that no operating system trusts: point your client at the
 * CA files in resources/certs/ (Guzzle: 'verify' => '/path/to/bundle.pem') instead
 * of turning verification off. See docs/tls.md in the repository.
 */
final class Psr18Transport implements Transport
{
    public function __construct(
        private readonly ClientInterface $client,
        private readonly RequestFactoryInterface $requestFactory,
        private readonly StreamFactoryInterface $streamFactory,
    ) {}

    public function send(Request $request): Response
    {
        try {
            $message = $this->requestFactory->createRequest($request->method, $request->url);
            foreach ($request->headers as $name => $value) {
                // PSR-7 keeps the case a header name was set with, which XYP needs.
                $message = $message->withHeader($name, $value);
            }
        } catch (\InvalidArgumentException) {
            // A header value with a line break (a token read from a file, say) or a URL
            // the PSR-7 implementation refuses. Its message can quote the token.
            throw new ConfigException('the request cannot be expressed as a PSR-7 message: check baseUrl and the access token');
        }
        if ($request->body !== '') {
            $message = $message->withBody($this->streamFactory->createStream($request->body));
        }

        try {
            $response = $this->client->sendRequest($message);
        } catch (ClientExceptionInterface $error) {
            // PSR-18 has no timeout exception; every client says so in its message
            // ("cURL error 28: Operation timed out", "Idle timeout reached").
            if (preg_match('/timed? ?out/i', $error->getMessage()) === 1) {
                throw TimeoutException::timedOut($error);
            }

            throw ConnectionException::because($error->getMessage(), $error);
        }

        return new Response($response->getStatusCode(), (string) $response->getBody());
    }
}
