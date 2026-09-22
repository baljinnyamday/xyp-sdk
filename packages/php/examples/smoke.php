<?php

// First contact with the real XYP, from a machine on the National Data Center VPN:
//
//     XYP_ACCESS_TOKEN=… XYP_PRIVATE_KEY=private.key XYP_REGNUM=РД00000000 \
//         php packages/php/examples/smoke.php
//
// It prints field names and shapes only: never the token, the key or citizen data.

declare(strict_types=1);

require dirname(__DIR__, 3) . '/vendor/autoload.php';

use Xyp\Citizen\GetCitizenIDCardInfoParams;
use Xyp\Exception\ApiException;
use Xyp\Exception\XypException;
use Xyp\XypClient;

$regnum = getenv('XYP_REGNUM');
if (!is_string($regnum) || $regnum === '') {
    fwrite(STDERR, "Set XYP_REGNUM (and XYP_ACCESS_TOKEN, XYP_PRIVATE_KEY).\n");
    exit(2);
}

try {
    $xyp = new XypClient();
    $card = $xyp->citizen->getCitizenIDCardInfo(new GetCitizenIDCardInfoParams(regnum: $regnum));
} catch (ApiException $error) {
    // Reaching this means signing, TLS and the envelope all worked: XYP answered.
    printf("XYP answered resultCode %d (requestId %s): %s\n", $error->resultCode, $error->requestId, $error->getMessage());
    exit(1);
} catch (XypException $error) {
    printf("%s (%s): %s\n", $error::class, $error->origin()->value, $error->getMessage());
    exit(1);
}

$fields = array_keys(array_filter(get_object_vars($card), static fn(mixed $value): bool => $value !== null && $value !== []));
echo 'OK: WS100101_getCitizenIDCardInfo returned ', count($fields), " fields\n";
echo '  ', implode(', ', $fields), "\n";
foreach ($card->xyp->mismatches as $mismatch) {
    echo '  model mismatch: ', $mismatch, "\n";
}
