<?php

// Run by KeyLoaderTest in a child process: loads encrypted DER without a
// passphrase and prints what happened. A passphrase prompt would show up in the
// output, since the parent captures stderr too.

declare(strict_types=1);

require dirname(__DIR__, 4) . '/vendor/autoload.php';

use Xyp\Exception\ConfigException;
use Xyp\Internal\KeyLoader;
use Xyp\Tests\Support\Keys;

try {
    KeyLoader::load(Keys::der(Keys::pem(Keys::rsa(), 'correct horse')));
    echo 'loaded';
} catch (ConfigException) {
    echo 'rejected';
}
