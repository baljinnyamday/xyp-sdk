<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * Codes 203 and 501: your access token may not call this service.
 */
final class AccessDeniedException extends ApiException {}
