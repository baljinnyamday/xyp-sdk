<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * Code 3: missing input, wrong endpoint, or a bad accessToken/timeStamp/signature header.
 */
final class InvalidRequestException extends ApiException {}
