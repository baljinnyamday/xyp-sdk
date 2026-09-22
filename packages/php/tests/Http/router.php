<?php

// Router for the `php -S` server CurlTransportTest starts: it answers with what it
// received, so the test sees the request exactly as it arrived on the wire.

declare(strict_types=1);

$uri = $_SERVER['REQUEST_URI'] ?? '/';
$path = parse_url(is_string($uri) ? $uri : '/', PHP_URL_PATH);

if ($path === '/slow') {
    usleep(2_000_000);
}
if (is_string($path) && preg_match('#^/status/(\d{3})$#', $path, $match) === 1) {
    http_response_code((int) $match[1]);
    echo 'status ', $match[1];

    return true;
}

header('Content-Type: application/json');
echo json_encode([
    'method' => $_SERVER['REQUEST_METHOD'] ?? '',
    'uri' => $uri,
    'headers' => getallheaders(),
    'body' => file_get_contents('php://input'),
], JSON_THROW_ON_ERROR);

return true;
