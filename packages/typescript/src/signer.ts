/** Request signing: the three HTTP headers XYP requires on every call. */

import { createPrivateKey, createSign, type KeyObject } from "node:crypto";
import { readFileSync } from "node:fs";
import { XypConfigError } from "./errors.js";

/** A file path, PEM text, or PEM/DER bytes. */
export type PrivateKeySource = string | Uint8Array;

const PEM_MARKER = "-----BEGIN";
const MILLISECONDS_PER_SECOND = 1000;

export interface Signer {
  /** Headers for one request. XYP rejects stale timestamps, so never reuse them. */
  headers(timestamp?: number): Record<string, string>;
}

export function createSigner(
  accessToken: string,
  privateKey: PrivateKeySource,
  passphrase?: string,
): Signer {
  if (!accessToken) throw new XypConfigError("accessToken must not be empty");
  const key = loadPrivateKey(privateKey, passphrase);
  return {
    headers(timestamp = Math.floor(Date.now() / MILLISECONDS_PER_SECOND)) {
      const timeStamp = String(timestamp);
      const signature = createSign("RSA-SHA256")
        .update(`${accessToken}.${timeStamp}`, "utf8")
        .sign(key, "base64");
      return { accessToken, timeStamp, signature };
    },
  };
}

function readKeyBytes(source: PrivateKeySource): Buffer {
  if (typeof source !== "string") return Buffer.from(source);
  if (source.includes(PEM_MARKER)) return Buffer.from(source, "ascii");
  try {
    return readFileSync(source);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code ?? "unknown error";
    throw new XypConfigError(`Cannot read private key file (${code})`);
  }
}

function loadPrivateKey(source: PrivateKeySource, passphrase?: string): KeyObject {
  const data = readKeyBytes(source);
  const isPem = data.includes(PEM_MARKER);
  // PEM names its own format; DER does not, so both common RSA encodings are tried.
  const attempts = isPem
    ? [{ format: "pem" as const }]
    : [
        { format: "der" as const, type: "pkcs8" as const },
        { format: "der" as const, type: "pkcs1" as const },
      ];
  const key = attempts.reduce<KeyObject | undefined>(
    (found, attempt) => found ?? tryCreateKey(data, attempt, passphrase),
    undefined,
  );
  if (key === undefined) {
    // Deliberately no detail from the underlying error: it can echo key material.
    throw new XypConfigError(
      "privateKey is not a valid PEM/DER private key (or the passphrase is wrong)",
    );
  }
  if (key.asymmetricKeyType !== "rsa") throw new XypConfigError("privateKey must be an RSA key");
  return key;
}

function tryCreateKey(
  key: Buffer,
  encoding: { format: "pem" } | { format: "der"; type: "pkcs8" | "pkcs1" },
  passphrase?: string,
): KeyObject | undefined {
  try {
    return createPrivateKey({
      key,
      ...encoding,
      ...(passphrase === undefined ? {} : { passphrase }),
    });
  } catch {
    return undefined;
  }
}
