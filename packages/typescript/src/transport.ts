/** HTTP transport: signs, posts the envelope, maps failures to SDK errors. */

import { request as httpRequest } from "node:http";
import { Agent, request as httpsRequest } from "node:https";
import type { AuthEntity } from "./auth.js";
import { BUNDLED_CA } from "./certs.js";
import {
  apiError,
  RESULT_CODE_OK,
  XypConfigError,
  XypConnectionError,
  XypResponseError,
  XypTimeoutError,
} from "./errors.js";
import { NAMESPACES, OPERATIONS } from "./registry.js";
import type { Signer } from "./signer.js";
import { buildEnvelope, parseResponse } from "./soap.js";

export const DEFAULT_BASE_URL = "https://xyp.gov.mn";
export const DEFAULT_TIMEOUT_MS = 30_000;

const SOAP_HEADERS = { "Content-Type": "text/xml; charset=utf-8", SOAPAction: '""' } as const;
const TARGET_NAMESPACE = /targetNamespace="([^"]+)"/;
const HTTP_ERROR_STATUS = 400;

/**
 * `true` (default) verifies against the bundled Mongolian national CAs only.
 * `{ ca }` trusts your own CA bundle (PEM) instead, e.g. for your HTTPS proxy.
 * `false` disables verification: read docs/tls.md first.
 */
export type Verify = boolean | { readonly ca: string | readonly string[] };

export interface CallOptions {
  /** Approval from the citizen whose data is requested. */
  readonly auth?: AuthEntity;
  /** Approval from the staff member (operator) making the request. */
  readonly operator?: AuthEntity;
}

export interface RawCallOptions extends CallOptions {
  /** e.g. "citizen-1.5.0": for a service that is newer than this SDK version. */
  readonly endpoint?: string;
}

export interface TransportOptions {
  readonly signer: Signer;
  readonly baseUrl: string;
  readonly timeoutMs: number;
  readonly verify: Verify;
}

interface HttpReply {
  readonly status: number;
  readonly body: string;
}

export class Transport {
  readonly #options: TransportOptions;
  readonly #agent: Agent;
  // Seeded with namespaces verified from WSDLs; others are read once per endpoint.
  #namespaces: Readonly<Record<string, string>> = NAMESPACES;

  constructor(options: TransportOptions) {
    this.#options = { ...options, baseUrl: options.baseUrl.replace(/\/+$/, "") };
    this.#agent = new Agent({ keepAlive: true, ...tlsOptions(options.verify) });
  }

  async call(
    operation: string,
    params: Readonly<Record<string, unknown>>,
    options: RawCallOptions = {},
  ): Promise<unknown> {
    const endpoint = options.endpoint ?? OPERATIONS[operation];
    if (endpoint === undefined) {
      throw new XypConfigError(
        `Unknown operation "${operation}". Pass { endpoint: "<name>-<version>" } ` +
          "to call a service this SDK version does not know about.",
      );
    }
    const url = `${this.#options.baseUrl}/${endpoint}/ws`;
    const namespace = this.#namespaces[endpoint] ?? (await this.#learnNamespace(endpoint, url));
    const envelope = buildEnvelope(operation, namespace, params, options.auth, options.operator);
    const reply = await this.#send("POST", url, envelope, {
      ...SOAP_HEADERS,
      ...this.#options.signer.headers(),
    });
    return unwrap(reply);
  }

  close(): void {
    this.#agent.destroy();
  }

  async #learnNamespace(endpoint: string, url: string): Promise<string> {
    const reply = await this.#send("GET", `${url}?WSDL`, undefined, {});
    const namespace = TARGET_NAMESPACE.exec(reply.body)?.[1];
    if (reply.status >= HTTP_ERROR_STATUS || namespace === undefined) {
      throw new XypResponseError(`Could not read the WSDL of endpoint "${endpoint}"`, reply.status);
    }
    this.#namespaces = { ...this.#namespaces, [endpoint]: namespace };
    return namespace;
  }

  #send(
    method: "GET" | "POST",
    url: string,
    body: string | undefined,
    headers: Record<string, string>,
  ): Promise<HttpReply> {
    const isHttps = url.startsWith("https:");
    const send = isHttps ? httpsRequest : httpRequest;
    return new Promise<HttpReply>((resolve, reject) => {
      const request = send(
        url,
        {
          method,
          headers,
          timeout: this.#options.timeoutMs,
          ...(isHttps ? { agent: this.#agent } : {}),
        },
        (response) => {
          const chunks: Buffer[] = [];
          response.on("data", (chunk: Buffer) => chunks.push(chunk));
          response.on("error", (error) => reject(connectionError(error)));
          response.on("end", () =>
            resolve({
              status: response.statusCode ?? 0,
              body: Buffer.concat(chunks).toString("utf8"),
            }),
          );
        },
      );
      request.on("timeout", () =>
        request.destroy(new XypTimeoutError("XYP did not answer in time")),
      );
      request.on("error", (error) => reject(connectionError(error)));
      request.end(body);
    });
  }
}

export function tlsOptions(verify: Verify): { ca?: string[]; rejectUnauthorized?: boolean } {
  if (verify === false) return { rejectUnauthorized: false };
  if (verify === true) return { ca: [...BUNDLED_CA] };
  return { ca: [verify.ca].flat() };
}

function connectionError(error: Error): XypConnectionError {
  if (error instanceof XypConnectionError) return error;
  const code = (error as NodeJS.ErrnoException).code ?? error.name;
  return new XypConnectionError(
    `Could not reach XYP (${code}). Check the VPN connection, ` +
      "the hosts entry for xyp.gov.mn and the TLS settings.",
    { cause: error },
  );
}

function unwrap(reply: HttpReply): unknown {
  let result: ReturnType<typeof parseResponse>;
  try {
    result = parseResponse(reply.body);
  } catch (error) {
    if (reply.status >= HTTP_ERROR_STATUS) {
      throw new XypResponseError(`XYP answered with HTTP ${reply.status}`, reply.status);
    }
    throw error;
  }
  if (result.resultCode !== RESULT_CODE_OK) {
    throw apiError(result.resultCode, result.message, result.requestId);
  }
  return result.data;
}
