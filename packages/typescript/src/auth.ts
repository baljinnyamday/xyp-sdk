/**
 * Citizen and operator approval sent in a request's `<auth>` block.
 *
 *   xyp.citizen.getCitizenIDCardInfo(
 *     { regnum },
 *     { auth: auth.otp({ regnum, otp: 123456 }) },
 *   );
 */

export const AuthType = {
  SMS_OTP: 1,
  DIGITAL_SIGNATURE: 2,
  FINGERPRINT: 3,
  SSO_OTP: 4,
  DAN_APP: 5,
} as const;
export type AuthType = (typeof AuthType)[keyof typeof AuthType];

/** Mirrors the WSDL's `authorizationEntity`. Prefer the `auth.*` constructors. */
export interface AuthEntity {
  readonly regnum?: string;
  readonly civilId?: string;
  readonly authType?: AuthType;
  readonly otp?: number;
  readonly fingerprint?: Uint8Array;
  readonly signature?: string;
  readonly certFingerprint?: string;
  readonly appAuthToken?: string;
  readonly authAppName?: string;
}

export const auth = {
  /** One-time code the person received by SMS. */
  otp: (input: { regnum: string; otp: number }): AuthEntity => ({
    ...input,
    authType: AuthType.SMS_OTP,
  }),
  /** One-time code issued through the government SSO. */
  ssoOtp: (input: { regnum: string; otp: number }): AuthEntity => ({
    ...input,
    authType: AuthType.SSO_OTP,
  }),
  /** The person's own digital signature and their certificate's fingerprint. */
  signature: (input: {
    regnum: string;
    signature: string;
    certFingerprint: string;
  }): AuthEntity => ({
    ...input,
    authType: AuthType.DIGITAL_SIGNATURE,
  }),
  /** A freshly scanned fingerprint image. */
  fingerprint: (input: { regnum: string; fingerprint: Uint8Array }): AuthEntity => ({
    ...input,
    authType: AuthType.FINGERPRINT,
  }),
  /** Approval through the ДАН mobile app. */
  danApp: (input: { regnum: string }): AuthEntity => ({ ...input, authType: AuthType.DAN_APP }),
} as const;

const NO_OTP = 0;

/**
 * Field names and order as declared by the WSDL; unset fields are omitted.
 * `otp` is always sent: some XYP WSDLs declare it as a required int, and the
 * official samples send 0 when there is no code.
 */
export function authToWire(entity: AuthEntity): Record<string, unknown> {
  return {
    appAuthToken: entity.appAuthToken,
    authAppName: entity.authAppName,
    authType: entity.authType,
    certFingerprint: entity.certFingerprint,
    civilId: entity.civilId,
    fingerprint: entity.fingerprint,
    otp: entity.otp ?? NO_OTP,
    regnum: entity.regnum,
    signature: entity.signature,
  };
}
