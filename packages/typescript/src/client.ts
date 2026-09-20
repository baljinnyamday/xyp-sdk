/** The entry point: `new Xyp({ accessToken, privateKey })`. */

import { XypConfigError } from "./errors.js";
import { ServiceGroups } from "./groups.js";
import { createSigner, type PrivateKeySource } from "./signer.js";
import {
  DEFAULT_BASE_URL,
  DEFAULT_TIMEOUT_MS,
  type RawCallOptions,
  Transport,
  type Verify,
} from "./transport.js";

export const ACCESS_TOKEN_ENV = "XYP_ACCESS_TOKEN";
export const PRIVATE_KEY_ENV = "XYP_PRIVATE_KEY";

export interface XypOptions {
  /** Token issued by the National Data Center. Falls back to `XYP_ACCESS_TOKEN`. */
  readonly accessToken?: string;
  /** RSA key as a file path, PEM text or PEM/DER bytes. Falls back to the path in `XYP_PRIVATE_KEY`. */
  readonly privateKey?: PrivateKeySource;
  /** Passphrase of an encrypted key. */
  readonly privateKeyPassphrase?: string;
  /** Change only when you reach XYP through your own proxy. */
  readonly baseUrl?: string;
  /** Milliseconds to wait for XYP. */
  readonly timeoutMs?: number;
  /** TLS verification; defaults to the bundled Mongolian national CAs. */
  readonly verify?: Verify;
}

/**
 * XYP client. Services are grouped by XYP endpoint (`xyp.citizen`, `xyp.insurance`, ...).
 * Reuse one client: it keeps connections alive. Call `close()` when done.
 *
 *   const xyp = new Xyp({ accessToken: "...", privateKey: "private.key" });
 *   const card = await xyp.citizen.getCitizenIDCardInfo({ regnum: "..." });
 */
export class Xyp extends ServiceGroups {
  readonly #transport: Transport;

  constructor(options: XypOptions = {}) {
    const accessToken = options.accessToken ?? process.env[ACCESS_TOKEN_ENV];
    const privateKey = options.privateKey ?? process.env[PRIVATE_KEY_ENV];
    if (!accessToken) {
      throw new XypConfigError(
        `Pass accessToken or set the ${ACCESS_TOKEN_ENV} environment variable`,
      );
    }
    if (!privateKey) {
      throw new XypConfigError(
        `Pass privateKey (path, PEM text or bytes) or set ${PRIVATE_KEY_ENV} to the key path`,
      );
    }
    const transport = new Transport({
      signer: createSigner(accessToken, privateKey, options.privateKeyPassphrase),
      baseUrl: options.baseUrl ?? DEFAULT_BASE_URL,
      timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      verify: options.verify ?? true,
    });
    super(transport);
    this.#transport = transport;
  }

  /**
   * Call a service by its original XYP name and get the raw response data.
   *
   *   await xyp.call("WS100101_getCitizenIDCardInfo", { regnum: "..." });
   *
   * `params` uses XYP's own field names. Pass `{ endpoint: "citizen-1.5.0" }` for a
   * service that is newer than this SDK version.
   */
  call(
    operation: string,
    params: Readonly<Record<string, unknown>> = {},
    options: RawCallOptions = {},
  ): Promise<unknown> {
    return this.#transport.call(operation, params, options);
  }

  /** Closes kept-alive connections. */
  close(): void {
    this.#transport.close();
  }
}
