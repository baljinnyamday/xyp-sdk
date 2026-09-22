# xyp-sdk for PHP

[![Packagist](https://img.shields.io/packagist/v/baljinnyamday/xyp-sdk?include_prereleases&label=Packagist)](https://packagist.org/packages/baljinnyamday/xyp-sdk)
[![PHP](https://img.shields.io/badge/php-%5E8.2-777bb4)](https://www.php.net/supported-versions.php)
[![php](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/php.yml/badge.svg)](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/php.yml)

Typed PHP SDK for **XYP (ХУР)**, Mongolia's government data exchange system.
All 499 services, zero configuration for TLS and signing, PHP 8.2+.

```bash
composer require baljinnyamday/xyp-sdk:0.1.0-alpha.1
```

While the package is pre-release, name the version (or `^0.1@alpha`): Composer
does not pick an alpha for a plain `composer require`.

```php
use Xyp\Citizen\GetCitizenIDCardInfoParams;
use Xyp\XypClient;

$xyp = new XypClient(accessToken: $token, privateKey: 'private.key');

$card = $xyp->citizen->getCitizenIDCardInfo(
    new GetCitizenIDCardInfoParams(regnum: 'РД00000000'),
);
echo $card->firstname, ' ', $card->lastname;
```

That is the whole integration. The SDK signs every request, builds the SOAP
envelope, verifies XYP's certificate and turns the XML into typed, readonly
objects. Build one `XypClient` and reuse it (a singleton in Laravel, a service in
Symfony): it keeps its connection alive between calls.

Dependencies: the `curl`, `openssl`, `xmlreader` and `libxml` extensions that
ship with PHP, and `psr/log`. Nothing else enters your `vendor/`.

> **Status: alpha.** The production system is only reachable from the National
> Data Center VPN, so the SDK is verified differentially instead: for every
> operation in the WSDLs we hold, the request XML is byte-identical to what
> [zeep](https://docs.python-zeep.org) — the SOAP library behind the official
> samples and known-working integrations — builds from the WSDL. Endpoints whose
> WSDL is not public are covered by the same code paths but not by that
> comparison. Please open an issue with anything that differs in practice.

## Credentials

```php
new XypClient(accessToken: $token, privateKey: 'private.key');          // PEM or DER file
new XypClient(accessToken: $token, privateKey: $pemFromSecretManager);  // PEM text
new XypClient(accessToken: $token, privateKey: $key, passphrase: $pw);  // encrypted key
new XypClient();                        // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
```

`privateKey` also takes an `OpenSSLAsymmetricKey`; it must be RSA. The
environment variables are read from `getenv()`, `$_ENV` and `$_SERVER`, so
`.env` loaders (Laravel, Symfony Dotenv) work.

Keep the token and key out of your source code. Neither appears in `var_dump`,
`print_r`, stack traces (`#[\SensitiveParameter]`), warnings or error messages,
and a client refuses to be serialized.

You also need a working connection to the National Data Center VPN with
`xyp.gov.mn` in your hosts file
([connection guide](https://developer.xyp.gov.mn/docs/guide)).

## Finding a service

Services are grouped by XYP endpoint and keep XYP's own names, minus the code:
`WS100101_getCitizenIDCardInfo` is `$xyp->citizen->getCitizenIDCardInfo()`,
with its input in `Xyp\Citizen\GetCitizenIDCardInfoParams` and its output in
`Xyp\Citizen\GetCitizenIDCardInfoResponse`. Your editor autocompletes all of
them, with the original name and description in the docblocks.

`citizen` (83) · `health` (76) · `insurance` (67) · `governmentService` (54) ·
`transport` (48) · `payment` (33) · `property` (30) · `statistic` (24) ·
`laborWelfare` (19) · `legalEntity` (19) · `education` (18) · `tax` (9) ·
`meta` (6) · `foreignService` (6) · `privateService` (6) · `pki` (1)

Prefer the original names, or need a service newer than this SDK version?

```php
$data = $xyp->call('WS100101_getCitizenIDCardInfo', ['regnum' => 'РД00000000']); // array tree
$data = $xyp->call('WS109999_brandNew', ['regnum' => $regnum], endpoint: 'citizen-1.5.0');
```

Parameters are sent in array order. `invoke()` does the same as `call()` but
decodes into a readonly class of your own, the way the generated ones are built:

```php
final readonly class IdCard
{
    public function __construct(
        public ?string $firstname = null,
        public ?\Xyp\Date $birthDate = null,
        public \Xyp\Extras $xyp = new \Xyp\Extras(),
    ) {}
}

$card = $xyp->invoke('WS100101_getCitizenIDCardInfo', ['regnum' => $regnum], IdCard::class);
```

## Citizen approval (OTP, fingerprint, digital signature, ДАН)

```php
use Xyp\Auth;
use Xyp\Meta\RegisterOTPRequestParams;

// 1. ask XYP to text the citizen a code for the services you are going to call
$xyp->meta->registerOTPRequest(
    new RegisterOTPRequestParams(
        regnum: $regnum,
        jsonWSList: '[{"ws": "WS100101_getCitizenIDCardInfo"}]',
        isSms: 1,
    ),
    auth: new Auth(regnum: $regnum),
);

// 2. call the service with the code they read out to you
$card = $xyp->citizen->getCitizenIDCardInfo(
    new GetCitizenIDCardInfoParams(regnum: $regnum),
    auth: Auth::otp($regnum, 123456),
);
```

Also: `Auth::ssoOtp()`, `Auth::signature()`, `Auth::fingerprint()`,
`Auth::danApp()`, and `operator:` for services that need the operator's approval
too.

## Errors

Every exception the SDK throws implements `Xyp\Exception\XypException`.

```php
use Xyp\Exception\ApiException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\NotFoundException;

try {
    $card = $xyp->citizen->getCitizenIDCardInfo($params);
} catch (NotFoundException) {
    return null;                                    // resultCode 1
} catch (ApiException $error) {
    $logger->error($error->getMessage(), ['requestId' => $error->requestId]);
    throw $error;
} catch (ConnectionException $error) {              // VPN, hosts entry, TLS, timeout
    retryLater($error);
}
```

| Exception (`Xyp\Exception\…`) | `resultCode` |
| --- | --- |
| `NotFoundException` | 1 |
| `InternalException` | 2 |
| `InvalidRequestException` | 3 (missing input, bad token / timestamp / signature header) |
| `AuthRequiredException` | 200–202 |
| `AccessDeniedException` | 203, 501 |
| `FingerprintException` | 301–304 |
| `CitizenDataException` | 401–402 |
| `SignatureException` | 601–605 |
| `ProviderException` | 801–802 |

They all extend `ApiException` (`resultCode`, `resultMessage`, `requestId` —
what XYP support will ask you for); a code outside the table is a plain
`ApiException`. Every exception also says whose side it is on:

| `$error->origin()` | Meaning | Exceptions |
| --- | --- | --- |
| `Origin::Config` | how the client was set up | `ConfigException` |
| `Origin::Network` | VPN, hosts entry, TLS, timeout | `ConnectionException`, `TimeoutException` |
| `Origin::Xyp` | XYP or the data provider answered with an error | `ApiException` and subclasses, `ResponseException` |
| `Origin::Sdk` | a gap in this SDK — please report it | anything else |

### When XYP's data does not match the types

The response classes are generated from XYP's public catalog, which is typed by
hand, so real data can disagree with them. That never costs you a call:

```php
$card = $xyp->citizen->getCitizenIDCardInfo($params); // succeeds
$card->firstname;          // everything that fits is typed as usual
$card->birthDate;          // null: this field did not fit
$card->xyp->mismatches;    // [Mismatch('birthDate', '…')] with the raw ->value()
$card->xyp->raw;           // the whole response, including undeclared fields
```

The SDK also logs one warning naming the fields and saying plainly that the
SDK's model is wrong — not XYP, not your code. It contains field names only,
never values, so citizen data stays out of your logs. Pass `logger:` (any
PSR-3 logger) to route it; without one it goes to `error_log()`.

## TLS

XYP's certificate is issued by the Mongolian national CA, which no operating
system trusts by default — the reason most integrations (including the official
PHP sample, with `verify_peer => false`) switch verification off. This SDK
bundles the national root and issuing CA instead and trusts exactly those, for
XYP requests only. It also points libcurl's CA directory at its own, so the
operating system's store is never consulted alongside them.

```php
new XypClient(verify: true);            // default: bundled national CAs only
new XypClient(verify: $proxyCaPem);     // your own CA (PEM text or a file path), e.g. for a proxy
new XypClient(verify: false);           // off — read docs/tls.md first
```

Going through your own proxy? Set `baseUrl:` too. Details and the reasoning:
[docs/tls.md](https://github.com/baljinnyamday/xyp-sdk/blob/main/docs/tls.md).

### Your own HTTP client

`transport:` takes any `Xyp\Http\Transport`. To send through a PSR-18 client
(Guzzle, Symfony HttpClient) for retries, tracing or a proxy:

```php
use GuzzleHttp\Client;
use GuzzleHttp\Psr7\HttpFactory;
use Xyp\Http\Psr18Transport;

$factory = new HttpFactory();
$xyp = new XypClient(transport: new Psr18Transport(new Client(['verify' => $caBundle]), $factory, $factory));
```

TLS is then your client's job: configure it to trust the national CAs in
`vendor/baljinnyamday/xyp-sdk/packages/php/resources/certs/`.

## Types

| XYP | PHP | Why |
| --- | --- | --- |
| `String` | `?string` | `null` is absent (XML cannot tell an empty string from a missing element) |
| `int`, `long` | `?int` | `null` is absent; `0` is a real answer |
| `double`, `float` | `?float` | same |
| `boolean` | `?bool` | same; `false` is a real answer |
| `BigDecimal` | `?string` | no precision is lost, whatever you parse it with |
| `Date` | `?Xyp\Date` | `->raw` is always the text XYP sent; `->time` is a `DateTimeImmutable` only when it is ISO 8601 (no zone means UTC). Formats are undocumented, so nothing is rejected |
| `byte[]` | `?string` | binary (photos, PDFs), base64 on the wire; text where bytes were expected becomes a mismatch instead of garbage |
| an object | a readonly class, or `null` | |
| a list | `list<…>` | a single element from XYP still becomes a one-item list |
| undocumented | `mixed` | the parsed subtree: arrays, strings and `null` |

Request parameters use the same types; `null` (and `''`) is left out of the
request. Date parameters also take any `DateTimeInterface`.

## Development

From the repository root (Packagist reads `composer.json` from there):

```bash
composer install
composer test       # PHPUnit 11
composer analyse    # PHPStan, level max + strict rules
composer cs         # php-cs-fixer, PER-CS 2.0 (composer cs:fix to fix)
```

`src/<Group>/`, `src/Registry.php` and `src/ServiceGroups.php` are generated —
change `generator/` or `spec/` and run `uv run xyp-generate` from `generator/`
instead of editing them. `tests/fixtures/envelopes.json` comes from
`packages/typescript/scripts/make_fixtures.py`.

`php packages/php/examples/smoke.php` is the first-contact check against the
real XYP, from a machine on the VPN.

## Versioning

Semantic versioning. PHP versions are plain tags on this repository, like
`v0.1.0-alpha.1`: Composer reads versions from tag names and ignores the other
SDKs' `python-v*`, `typescript-v*` and `packages/go/v*` tags. Only
`packages/php/src` and the certificates ship; `.gitattributes` keeps everything
else out of the archive Packagist serves.

Published versions are immutable: lock files everywhere already record the
commit, so a tag is never moved. A bad release is fixed by publishing a new one.

### Releasing

Push a tag `vX.Y.Z` on a commit whose `php` workflow passed. Packagist picks it
up through its GitHub hook; the release workflow tests the tag again, waits for
Packagist to list it, installs it into an empty project and creates the GitHub
release.

## License

MIT
