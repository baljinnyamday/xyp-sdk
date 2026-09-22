<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ConfigException;

/**
 * The CA certificates CurlTransport trusts. The two bundled files are
 * byte-for-byte the ones published at https://esign.gov.mn/MNRCA.zip and
 * https://esign.gov.mn/MNICA.zip.
 *
 * @internal
 */
final class CertificateAuthorities
{
    public const BUNDLED = ['MNRCA-2021.pem', 'MNICA-2022.pem'];

    /** CURLOPT_CAINFO_BLOB needs libcurl 7.77.0. */
    private const BLOB_VERSION = 0x074D00;

    /** A bundle written to disk for a libcurl too old for blobs; deleted with this object. */
    private ?string $temporaryFile = null;

    public function __destruct()
    {
        if ($this->temporaryFile !== null && is_file($this->temporaryFile)) {
            unlink($this->temporaryFile);
        }
    }

    public static function directory(): string
    {
        return dirname(__DIR__, 2) . '/resources/certs';
    }

    /**
     * MNRCA-2021 and MNICA-2022 as one PEM text.
     *
     * @throws ConfigException when the package is missing its certificates
     */
    public function bundled(): string
    {
        $pem = '';
        foreach (self::BUNDLED as $name) {
            $pem .= $this->read(self::directory() . '/' . $name, 'the bundled CA certificate ' . $name) . "\n";
        }

        return $pem;
    }

    /**
     * Checks a CA file the caller named, so a typo fails when the client is built
     * rather than as a TLS error on the first call.
     *
     * @throws ConfigException
     */
    public function file(string $path): string
    {
        $this->read($path, 'the CA file passed as verify:');

        return $path;
    }

    /**
     * The curl options that make exactly $pem the trusted CAs.
     *
     * @return array<int, string>
     */
    public function curlOptions(string $pem): array
    {
        $version = curl_version();
        if (defined('CURLOPT_CAINFO_BLOB') && is_array($version) && $version['version_number'] >= self::BLOB_VERSION) {
            return [CURLOPT_CAINFO_BLOB => $pem];
        }

        return [CURLOPT_CAINFO => $this->temporaryFile($pem)];
    }

    private function temporaryFile(string $pem): string
    {
        // tempnam creates the file 0600 under a fresh name, so no other local user
        // can plant their own CA in it first.
        try {
            $path = Guard::run(static fn(): string|false => tempnam(sys_get_temp_dir(), 'xyp-ca-'));
            if ($path === false || Guard::run(static fn(): int|false => file_put_contents($path, $pem)) === false) {
                throw new ConfigException('cannot write the CA bundle to the temporary directory');
            }
        } catch (\ErrorException $error) {
            throw new ConfigException('cannot write the CA bundle to the temporary directory', 0, $error);
        }
        $this->temporaryFile = $path;

        return $path;
    }

    /**
     * @throws ConfigException
     */
    private function read(string $path, string $what): string
    {
        $problem = FileProblem::of($path);
        if ($problem === null) {
            try {
                $contents = Guard::run(static fn(): string|false => file_get_contents($path));
            } catch (\ErrorException) {
                $contents = false;
            }
            if (is_string($contents) && str_contains($contents, '-----BEGIN CERTIFICATE-----')) {
                return $contents;
            }
            $problem = is_string($contents) ? 'not a PEM certificate' : 'unreadable';
        }

        throw new ConfigException(sprintf('cannot read %s (%s)', $what, $problem));
    }
}
