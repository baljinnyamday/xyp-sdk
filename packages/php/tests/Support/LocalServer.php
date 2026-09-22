<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

/**
 * A `php -S` server on a free local port, for tests that need real HTTP.
 */
final class LocalServer
{
    /** @var resource */
    private $process;

    public readonly string $baseUrl;

    public function __construct(string $router)
    {
        $port = self::freePort();
        // One process, no PHP_CLI_SERVER_WORKERS: forked workers outlive proc_terminate().
        $process = proc_open(
            [PHP_BINARY, '-S', '127.0.0.1:' . $port, $router],
            [0 => ['pipe', 'r'], 1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']],
            $pipes,
        );
        if (!\is_resource($process)) {
            throw new \RuntimeException('could not start php -S');
        }
        $this->process = $process;
        $this->baseUrl = 'http://127.0.0.1:' . $port;
        $this->waitUntilListening($port);
    }

    public function stop(): void
    {
        proc_terminate($this->process);
        proc_close($this->process);
    }

    private static function freePort(): int
    {
        $socket = stream_socket_server('tcp://127.0.0.1:0');
        if ($socket === false) {
            throw new \RuntimeException('no free port');
        }
        $name = (string) stream_socket_get_name($socket, false);
        fclose($socket);

        return (int) substr($name, (int) strrpos($name, ':') + 1);
    }

    private function waitUntilListening(int $port): void
    {
        $deadline = microtime(true) + 5;
        // A refused connection is expected while the server starts; it is not an error.
        set_error_handler(static fn(): bool => true);
        try {
            while (microtime(true) < $deadline) {
                $connection = stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $error, 0.1);
                if ($connection !== false) {
                    fclose($connection);

                    return;
                }
                usleep(20_000);
            }
        } finally {
            restore_error_handler();
        }

        throw new \RuntimeException('php -S did not start listening');
    }
}
