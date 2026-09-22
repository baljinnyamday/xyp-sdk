<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * Codes 200-202: the auth block (citizen and/or operator approval) is missing.
 */
final class AuthRequiredException extends ApiException {}
