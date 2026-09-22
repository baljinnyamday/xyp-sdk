<?php

declare(strict_types=1);

namespace Xyp\Http;

use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\TimeoutException;
use Xyp\Internal\CertificateAuthorities;

/**
 * The default transport: ext-curl with one reused handle, so consecutive calls
 * share a kept-alive connection.
 *
 * XYP's certificate is issued by the Mongolian national PKI, which no operating
 * system trusts. Instead of turning verification off (what most integrations do,
 * including the official sample), this trusts exactly the two bundled national
 * CAs, and only for its own requests. See docs/tls.md in the repository.
 */
final class CurlTransport implements Transport
{
    /** CURLE_OPERATION_TIMEDOUT */
    private const TIMEOUT_ERRNO = 28;

    private ?\CurlHandle $handle = null;

    private readonly int $timeoutMs;

    /** @var array<int, mixed> */
    private readonly array $tlsOptions;

    private readonly CertificateAuthorities $authorities;

    /**
     * @param float       $timeout seconds the whole request may take, including reading the body
     * @param bool|string $verify  true trusts the bundled national CAs; false turns verification
     *                             off (read docs/tls.md first: the certificate is the only thing
     *                             proving you reached XYP); a string is a CA file path or PEM text,
     *                             for a proxy that re-terminates TLS
     *
     * @throws ConfigException when the timeout or the CA setting is unusable
     */
    public function __construct(float $timeout = 30.0, bool|string $verify = true)
    {
        if (!is_finite($timeout) || $timeout <= 0) {
            throw new ConfigException('timeout must be a positive number of seconds');
        }
        // At least 1 ms: 0 means "no timeout" to curl.
        $this->timeoutMs = max(1, (int) ceil($timeout * 1000));
        $this->authorities = new CertificateAuthorities();
        $this->tlsOptions = $this->tlsOptions($verify);
    }

    public function send(Request $request): Response
    {
        $handle = $this->handle();
        curl_reset($handle); // drops the last request's options, keeps the live connection
        if (!curl_setopt_array($handle, $this->options($request))) {
            throw new ConfigException('curl rejected the request options; is libcurl older than 7.77?');
        }

        $body = curl_exec($handle);
        if (!is_string($body)) {
            $errno = curl_errno($handle);
            $cause = new \RuntimeException(curl_error($handle), $errno);
            if ($errno === self::TIMEOUT_ERRNO) {
                throw TimeoutException::timedOut($cause);
            }

            $message = $cause->getMessage() !== '' ? $cause->getMessage() : (curl_strerror($errno) ?? 'curl error ' . $errno);

            throw ConnectionException::because($message, $cause);
        }

        return new Response(curl_getinfo($handle, CURLINFO_RESPONSE_CODE), $body);
    }

    private function handle(): \CurlHandle
    {
        if ($this->handle === null) {
            $handle = curl_init();
            if ($handle === false) {
                throw ConnectionException::because('curl could not start');
            }
            $this->handle = $handle;
        }

        return $this->handle;
    }

    /**
     * @return array<int, mixed>
     */
    private function options(Request $request): array
    {
        $headers = [];
        foreach ($request->headers as $name => $value) {
            // A line break would let a value (a token read from a file, say) start a
            // header of its own.
            if (strpbrk($name . $value, "\r\n\0") !== false) {
                throw new ConfigException(sprintf('the %s header holds a line break', $name));
            }
            $headers[] = $name . ': ' . $value; // verbatim: XYP wants "accessToken", not "Accesstoken"
        }
        // curl otherwise waits for a "100 Continue" before sending a body over 1 KiB.
        $headers[] = 'Expect:';

        $options = [
            CURLOPT_URL => $request->url,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_TIMEOUT_MS => $this->timeoutMs,
            // Without it, a timeout under one second is rounded up to one or ignored.
            CURLOPT_NOSIGNAL => true,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSLVERSION => CURL_SSLVERSION_TLSv1_2,
        ];
        match ($request->method) {
            'GET' => $options[CURLOPT_HTTPGET] = true,
            'POST' => $options[CURLOPT_POST] = true,
            default => $options[CURLOPT_CUSTOMREQUEST] = $request->method,
        };
        if ($request->method !== 'GET') {
            $options[CURLOPT_POSTFIELDS] = $request->body;
        }

        return $options + $this->tlsOptions;
    }

    /**
     * @return array<int, mixed>
     */
    private function tlsOptions(bool|string $verify): array
    {
        if ($verify === false) {
            return [CURLOPT_SSL_VERIFYPEER => false, CURLOPT_SSL_VERIFYHOST => 0];
        }
        $authorities = match (true) {
            $verify === true => $this->authorities->bundled(),
            str_contains($verify, '-----BEGIN') => $verify,
            default => null,
        };
        $options = [
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            // CAINFO alone does not make the trust exclusive: libcurl built against
            // OpenSSL also searches its compiled-in CA directory (/etc/ssl/certs on
            // Debian and Alpine), so any public CA would be accepted as XYP too.
            // Clearing the option breaks every connection (PHP passes "" rather
            // than NULL), so it points at a directory of ours instead. Its files are
            // not named by subject hash, so OpenSSL finds nothing extra there.
            CURLOPT_CAPATH => CertificateAuthorities::directory(),
        ];
        if (is_string($verify) && $authorities === null) {
            return $options + [CURLOPT_CAINFO => $this->authorities->file($verify)];
        }
        assert(is_string($authorities));

        return $options + $this->authorities->curlOptions($authorities);
    }
}
