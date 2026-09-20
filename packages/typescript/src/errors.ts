/**
 * Errors thrown by the SDK. XYP answers every request with a `resultCode`; 0 is
 * success and everything else is thrown as an `XypApiError` subclass.
 * Codes: https://developer.xyp.gov.mn/docs/result-code
 */

/** Whose side a problem is on. */
export type Origin = "config" | "network" | "xyp" | "sdk";

export class XypError extends Error {
  /**
   * "config" (how the client was set up), "network" (VPN, DNS, TLS, timeout),
   * "xyp" (XYP or the data provider answered with an error), "sdk" (a gap in this SDK).
   */
  readonly origin: Origin = "sdk";

  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** The client was configured incorrectly (bad key, unknown operation, ...). */
export class XypConfigError extends XypError {
  override readonly origin: Origin = "config";
}

/** XYP could not be reached (network, VPN, DNS or TLS failure). */
export class XypConnectionError extends XypError {
  override readonly origin: Origin = "network";
}

/** XYP did not answer within the configured timeout. */
export class XypTimeoutError extends XypConnectionError {}

/** XYP answered with something that is not a valid service response. */
export class XypResponseError extends XypError {
  override readonly origin: Origin = "xyp";

  constructor(
    message: string,
    readonly statusCode?: number,
  ) {
    super(message);
  }
}

/** XYP processed the request and returned a non-zero `resultCode`. */
export class XypApiError extends XypError {
  override readonly origin: Origin = "xyp";

  constructor(
    readonly resultCode: number,
    readonly resultMessage: string,
    /** What XYP support will ask you for. */
    readonly requestId: string | null,
  ) {
    super(`[${resultCode}] ${resultMessage}`);
  }
}

/** 1: the data provider has no record for this request. */
export class NotFoundError extends XypApiError {}
/** 2: XYP internal error. */
export class InternalError extends XypApiError {}
/** 3: missing input, wrong endpoint, bad token/timestamp/signature header. */
export class InvalidRequestError extends XypApiError {}
/** 200-202: the `auth` block (citizen and/or operator) is missing. */
export class AuthRequiredError extends XypApiError {}
/** 203, 501: your access token is not allowed to call this service. */
export class AccessDeniedError extends XypApiError {}
/** 301-304: fingerprint not registered, not matched, or matching failed. */
export class FingerprintError extends XypApiError {}
/** 401-402: the citizen must visit the registry, or is not the owner. */
export class CitizenDataError extends XypApiError {}
/** 601-605: the citizen's digital signature or certificate was rejected. */
export class SignatureError extends XypApiError {}
/** 801-802: the data provider's database is unreachable or timed out. */
export class ProviderError extends XypApiError {}

export const RESULT_CODE_OK = 0;

type ApiErrorClass = new (code: number, message: string, requestId: string | null) => XypApiError;

const ERROR_BY_CODE: ReadonlyMap<number, ApiErrorClass> = new Map<number, ApiErrorClass>([
  [1, NotFoundError],
  [2, InternalError],
  [3, InvalidRequestError],
  [200, AuthRequiredError],
  [201, AuthRequiredError],
  [202, AuthRequiredError],
  [203, AccessDeniedError],
  [301, FingerprintError],
  [302, FingerprintError],
  [303, FingerprintError],
  [304, FingerprintError],
  [401, CitizenDataError],
  [402, CitizenDataError],
  [501, AccessDeniedError],
  [601, SignatureError],
  [602, SignatureError],
  [603, SignatureError],
  [604, SignatureError],
  [605, SignatureError],
  [801, ProviderError],
  [802, ProviderError],
]);

/** The most specific error for a non-zero `resultCode`. */
export function apiError(code: number, message: string, requestId: string | null): XypApiError {
  const ErrorClass = ERROR_BY_CODE.get(code) ?? XypApiError;
  return new ErrorClass(code, message, requestId);
}
