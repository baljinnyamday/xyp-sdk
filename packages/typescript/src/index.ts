/**
 * Typed Node.js SDK for XYP (ХУР), Mongolia's government data exchange system.
 *
 *   import { Xyp } from "xyp-sdk";
 *
 *   const xyp = new Xyp({ accessToken: "...", privateKey: "private.key" });
 *   const card = await xyp.citizen.getCitizenIDCardInfo({ regnum: "..." });
 */

export { type AuthEntity, AuthType, auth } from "./auth.js";
export { ACCESS_TOKEN_ENV, PRIVATE_KEY_ENV, Xyp, type XypOptions } from "./client.js";
export {
  AccessDeniedError,
  AuthRequiredError,
  CitizenDataError,
  FingerprintError,
  InternalError,
  InvalidRequestError,
  NotFoundError,
  type Origin,
  ProviderError,
  SignatureError,
  XypApiError,
  XypConfigError,
  XypConnectionError,
  XypError,
  XypResponseError,
  XypTimeoutError,
} from "./errors.js";
export { MISMATCH_WARNING, type Mismatch, type XypResult } from "./schema.js";
export type { PrivateKeySource } from "./signer.js";
export type { CallOptions, RawCallOptions, Verify } from "./transport.js";
export type * as types from "./types/index.js";
