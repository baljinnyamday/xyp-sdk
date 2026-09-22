<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Exception\ConfigException;
use Xyp\Internal\KeyLoader;
use Xyp\Tests\Support\Keys;

final class KeyLoaderTest extends TestCase
{
    private ?string $directory = null;

    protected function tearDown(): void
    {
        if ($this->directory !== null) {
            $files = glob($this->directory . '/*');
            foreach ($files === false ? [] : $files as $file) {
                chmod($file, 0o600);
                unlink($file);
            }
            rmdir($this->directory);
        }
    }

    #[DataProvider('encodings')]
    public function testAcceptsPemAndDerInBothRsaEncodings(string $encoding): void
    {
        $key = Keys::rsa();
        $data = match ($encoding) {
            'PKCS#8 PEM' => Keys::pem($key),
            'PKCS#1 PEM' => Keys::pkcs1Pem($key),
            'PKCS#8 DER' => Keys::der(Keys::pem($key)),
            'PKCS#1 DER' => Keys::der(Keys::pkcs1Pem($key)),
            default => self::fail($encoding),
        };

        self::assertSame(Keys::publicPem($key), Keys::publicPem(KeyLoader::load($data)));
        self::assertSame(Keys::publicPem($key), Keys::publicPem(KeyLoader::load($this->file($data))), 'from a file');
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function encodings(): iterable
    {
        foreach (['PKCS#8 PEM', 'PKCS#1 PEM', 'PKCS#8 DER', 'PKCS#1 DER'] as $encoding) {
            yield $encoding => [$encoding];
        }
    }

    public function testAcceptsALoadedKey(): void
    {
        self::assertSame(Keys::rsa(), KeyLoader::load(Keys::rsa()));
    }

    public function testDecryptsAnEncryptedKeyWithItsPassphrase(): void
    {
        $encrypted = Keys::pem(Keys::rsa(), 'correct horse');
        self::assertStringContainsString('ENCRYPTED PRIVATE KEY', $encrypted);

        self::assertSame(Keys::publicPem(Keys::rsa()), Keys::publicPem(KeyLoader::load($encrypted, 'correct horse')));
        self::assertSame(Keys::publicPem(Keys::rsa()), Keys::publicPem(KeyLoader::load($this->file($encrypted), 'correct horse')));
        self::assertSame(Keys::publicPem(Keys::rsa()), Keys::publicPem(KeyLoader::load(Keys::der($encrypted), 'correct horse')), 'encrypted DER');
    }

    public function testNeverAsksForAPassphraseOnTheTerminal(): void
    {
        // Encrypted DER looks like any other DER, so it is first tried without a
        // passphrase; OpenSSL must not fall back to prompting on the terminal.
        $php = escapeshellarg(PHP_BINARY);
        $script = escapeshellarg(__DIR__ . '/prompt_probe.php');
        $output = shell_exec("{$php} {$script} 2>&1 < /dev/null");

        self::assertSame('rejected', $output);
    }

    public function testAnEncryptedKeyWithoutItsPassphraseSaysWhatToPass(): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('passphrase:');
        KeyLoader::load(Keys::pem(Keys::rsa(), 'correct horse'));
    }

    public function testAWrongPassphraseIsAConfigException(): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('could not be decrypted');
        KeyLoader::load(Keys::pem(Keys::rsa(), 'correct horse'), 'wrong');
    }

    public function testReadsTheFileNamedByAPath(): void
    {
        $path = $this->file(Keys::pem(Keys::rsa()));

        self::assertSame(Keys::publicPem(Keys::rsa()), Keys::publicPem(KeyLoader::load($path)));
    }

    #[DataProvider('badInput')]
    public function testRejectsBadInputWithoutLeakingKeyMaterial(string $data, string $want): void
    {
        try {
            KeyLoader::load($data);
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertStringContainsString($want, $error->getMessage());
            self::assertStringNotContainsString('secret-material', $error->getMessage());
            self::assertStringNotContainsString('c2VjcmV0', $error->getMessage());
            self::assertNull($error->getPrevious(), 'OpenSSL\'s own error text can echo key material');
        }
        self::assertFalse(openssl_error_string(), 'OpenSSL\'s error queue was left for the next caller');
    }

    /**
     * @return iterable<string, array{string, string}>
     */
    public static function badInput(): iterable
    {
        $secret = base64_encode('secret-material');

        yield 'garbage PEM' => ["-----BEGIN PRIVATE KEY-----\n{$secret}\n-----END PRIVATE KEY-----", 'not a valid PEM or DER private key'];
        // Real DER always holds a NUL byte (the version INTEGER is 0), which is how it
        // is told apart from a path.
        yield 'garbage DER' => ["\x30\x82\x00\x10secret-material", 'not a valid PEM or DER private key'];
        yield 'an EC key' => [Keys::pem(Keys::ec()), 'must be an RSA key'];
        yield 'a public key' => [Keys::publicPem(Keys::rsa()), 'not a valid PEM or DER private key'];
        yield 'a PKCS#8 encrypted key' => ["-----BEGIN ENCRYPTED PRIVATE KEY-----\n{$secret}\n-----END ENCRYPTED PRIVATE KEY-----", 'passphrase:'];
        yield 'a legacy encrypted key' => [
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n\n{$secret}\n-----END RSA PRIVATE KEY-----",
            'passphrase:',
        ];
    }

    public function testRejectsALoadedKeyThatIsNotAnRsaPrivateKey(): void
    {
        try {
            KeyLoader::load(Keys::ec());
            self::fail('an EC key was accepted');
        } catch (ConfigException $error) {
            self::assertStringContainsString('RSA', $error->getMessage());
        }
        $public = openssl_pkey_get_public(Keys::publicPem(Keys::rsa()));
        self::assertNotFalse($public);
        $this->expectExceptionMessage('public key');
        KeyLoader::load($public);
    }

    public function testNamesOnlyTheFailureClassOfAFile(): void
    {
        $missing = sys_get_temp_dir() . '/xyp-absent-' . bin2hex(random_bytes(4)) . '/secret-name.key';
        $this->assertFileProblem($missing, 'no such file');
        $this->assertFileProblem(sys_get_temp_dir(), 'unreadable');
        if (\function_exists('posix_geteuid') && posix_geteuid() !== 0) {
            $unreadable = $this->file(Keys::pem(Keys::rsa()));
            chmod($unreadable, 0o000);
            $this->assertFileProblem($unreadable, 'permission denied');
        }
        $notAKey = $this->file('secret-material, not a key');
        try {
            KeyLoader::load($notAKey);
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertStringNotContainsString('secret-material', $error->getMessage());
            self::assertStringNotContainsString($notAKey, $error->getMessage());
        }
    }

    private function assertFileProblem(string $path, string $problem): void
    {
        try {
            KeyLoader::load($path);
            self::fail('no ConfigException for ' . $problem);
        } catch (ConfigException $error) {
            self::assertSame('cannot read the private key file (' . $problem . ')', $error->getMessage());
        }
    }

    private function file(string $contents): string
    {
        if ($this->directory === null) {
            $this->directory = sys_get_temp_dir() . '/xyp-keys-' . bin2hex(random_bytes(6));
            mkdir($this->directory, 0o700);
        }
        $path = $this->directory . '/' . bin2hex(random_bytes(4)) . '.key';
        file_put_contents($path, $contents);

        return $path;
    }
}
