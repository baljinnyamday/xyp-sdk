<?php

declare(strict_types=1);

namespace Xyp\Http;

use Xyp\Exception\ConnectionException;
use Xyp\Exception\TimeoutException;

/**
 * Sends one HTTP request. The default is CurlTransport; Psr18Transport plugs in
 * any PSR-18 client. Write your own for anything else (a test double, a proxy).
 */
interface Transport
{
    /**
     * Returns every response XYP sends, whatever its status.
     *
     * @throws TimeoutException    when the request ran out of time
     * @throws ConnectionException when XYP could not be reached at all
     */
    public function send(Request $request): Response;
}
