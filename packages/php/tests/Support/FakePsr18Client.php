<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

use GuzzleHttp\Psr7\Response;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;

/**
 * The smallest PSR-18 client: it keeps the request and answers from a closure.
 */
final class FakePsr18Client implements ClientInterface
{
    public ?RequestInterface $request = null;

    /**
     * @param \Closure(RequestInterface): ResponseInterface $answer
     */
    public function __construct(private readonly \Closure $answer) {}

    public static function answering(int $status, string $body): self
    {
        return new self(static fn(): ResponseInterface => new Response($status, [], $body));
    }

    public function sendRequest(RequestInterface $request): ResponseInterface
    {
        $this->request = $request;

        return ($this->answer)($request);
    }
}
