<?php

declare(strict_types=1);

namespace Xyp;

/**
 * The approval of a citizen (auth:) or of the staff member making the request
 * (operator:). Mirrors the WSDL's authorizationEntity. Prefer the static
 * constructors; build one by hand only for a combination they do not cover.
 *
 * otp is always sent, 0 when there is no code, because some XYP WSDLs declare it
 * as a required int and the official samples do the same.
 */
final readonly class Auth
{
    /**
     * @param ?string $fingerprint the raw image bytes; the SDK sends them base64-encoded
     */
    public function __construct(
        public ?string $regnum = null,
        public ?string $civilId = null,
        public ?AuthType $authType = null,
        #[\SensitiveParameter]
        public int $otp = 0,
        #[\SensitiveParameter]
        public ?string $fingerprint = null,
        #[\SensitiveParameter]
        public ?string $signature = null,
        public ?string $certFingerprint = null,
        #[\SensitiveParameter]
        public ?string $appAuthToken = null,
        public ?string $authAppName = null,
    ) {}

    /** Approval by a one-time code the person received by SMS. */
    public static function otp(string $regnum, #[\SensitiveParameter] int $otp): self
    {
        return new self(regnum: $regnum, authType: AuthType::SmsOtp, otp: $otp);
    }

    /** Approval by a one-time code issued through the government SSO. */
    public static function ssoOtp(string $regnum, #[\SensitiveParameter] int $otp): self
    {
        return new self(regnum: $regnum, authType: AuthType::SsoOtp, otp: $otp);
    }

    /**
     * Approval by the person's digital signature and the fingerprint of the
     * certificate that produced it.
     */
    public static function signature(
        string $regnum,
        #[\SensitiveParameter]
        string $signature,
        string $certFingerprint,
    ): self {
        return new self(
            regnum: $regnum,
            authType: AuthType::DigitalSignature,
            signature: $signature,
            certFingerprint: $certFingerprint,
        );
    }

    /**
     * Approval by a freshly scanned fingerprint image.
     *
     * @param string $fingerprint the raw image bytes, not base64
     */
    public static function fingerprint(string $regnum, #[\SensitiveParameter] string $fingerprint): self
    {
        return new self(regnum: $regnum, authType: AuthType::Fingerprint, fingerprint: $fingerprint);
    }

    /** Approval through the ДАН mobile app. */
    public static function danApp(string $regnum): self
    {
        return new self(regnum: $regnum, authType: AuthType::DanApp);
    }

    /**
     * Keeps the one-time code, the fingerprint and the signatures out of var_dump
     * and print_r: they prove a citizen's consent and must not end up in a log.
     *
     * @return array<string, mixed>
     */
    public function __debugInfo(): array
    {
        return [
            'regnum' => $this->regnum,
            'civilId' => $this->civilId,
            'authType' => $this->authType,
            'otp' => '(hidden)',
            'fingerprint' => $this->fingerprint === null ? null : '(hidden)',
            'signature' => $this->signature === null ? null : '(hidden)',
            'certFingerprint' => $this->certFingerprint,
            'appAuthToken' => $this->appAuthToken === null ? null : '(hidden)',
            'authAppName' => $this->authAppName,
        ];
    }
}
