<?php

// A project that depends on this package from the outside. CI copies it into a
// throwaway Composer project and runs it, once against the working tree (through a
// path repository) and once against the version Packagist serves. Everything it
// does works without a network route to XYP.

declare(strict_types=1);

require __DIR__ . '/vendor/autoload.php';

use Xyp\Citizen\GetCitizenIDCardInfoParams;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\Origin;
use Xyp\XypClient;

// Port 1 refuses connections, so nothing here waits on a real network.
const UNREACHABLE = 'http://127.0.0.1:1';

function check(): void
{
    $key = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048]);
    if ($key === false) {
        throw new RuntimeException('openssl could not generate a key');
    }

    try {
        new XypClient(accessToken: 't', privateKey: $key, baseUrl: 'xyp.gov.mn');
        throw new RuntimeException('a base URL without a scheme was accepted');
    } catch (ConfigException $error) {
        if ($error->origin() !== Origin::Config) {
            throw new RuntimeException('a ConfigException did not report the config origin');
        }
    }

    $xyp = new XypClient(accessToken: 't', privateKey: $key, baseUrl: UNREACHABLE, timeout: 5.0);

    try {
        $xyp->call('WS999999_doesNotExist');
        throw new RuntimeException('an unknown operation was accepted');
    } catch (ConfigException) {
    }

    // The generated classes are separate files, so this proves they ship and autoload.
    try {
        $xyp->citizen->getCitizenIDCardInfo(new GetCitizenIDCardInfoParams(regnum: 'РД00000000'));
        throw new RuntimeException('an unreachable XYP answered');
    } catch (ConnectionException $error) {
        if ($error->origin() !== Origin::Network) {
            throw new RuntimeException('a ConnectionException did not report the network origin');
        }
    }

    // The bundled CA certificates are resources, not classes: make sure they ship too.
    $reflection = new ReflectionClass(XypClient::class);
    $certs = dirname((string) $reflection->getFileName(), 2) . '/resources/certs';
    foreach (['MNRCA-2021.pem', 'MNICA-2022.pem'] as $name) {
        if (!is_file($certs . '/' . $name)) {
            throw new RuntimeException("the bundled certificate {$name} is missing");
        }
    }
}

try {
    check();
} catch (Throwable $error) {
    fwrite(STDERR, 'FAILED: ' . $error->getMessage() . "\n");
    exit(1);
}
echo "OK: the package installs, autoloads and behaves from outside\n";
