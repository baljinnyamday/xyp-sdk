<?php

declare(strict_types=1);

namespace Xyp;

/**
 * How a person proved they approve of the request.
 */
enum AuthType: int
{
    /** A one-time code the person received by SMS. */
    case SmsOtp = 1;
    /** The person's own digital signature. */
    case DigitalSignature = 2;
    /** A freshly scanned fingerprint image. */
    case Fingerprint = 3;
    /** A one-time code issued through the government SSO. */
    case SsoOtp = 4;
    /** Approval through the ДАН mobile app. */
    case DanApp = 5;
}
