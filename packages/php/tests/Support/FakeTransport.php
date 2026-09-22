<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

use Xyp\Http\Request;
use Xyp\Http\Response;
use Xyp\Http\Transport;

/**
 * A local stand-in for xyp.gov.mn: $answer decides what each request gets, and
 * every request is kept for the test to inspect.
 */
final class FakeTransport implements Transport
{
    /** @var list<Request> */
    public array $requests = [];

    /**
     * @param \Closure(Request): Response $answer
     */
    public function __construct(private readonly \Closure $answer) {}

    public static function answering(string $body, int $status = 200): self
    {
        return new self(static fn(): Response => new Response($status, $body));
    }

    public function send(Request $request): Response
    {
        $this->requests[] = $request;

        return ($this->answer)($request);
    }

    /**
     * @return list<Request>
     */
    public function gets(): array
    {
        return array_values(array_filter($this->requests, static fn(Request $request): bool => $request->method === 'GET'));
    }
}
