import { createSign, createVerify, generateKeyPairSync } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { XypConfigError } from "../src/errors.js";
import { createSigner } from "../src/signer.js";
import { TEST_KEY_PEM, TEST_KEYS, TEST_TOKEN } from "./helpers.js";

const FIXED_TIMESTAMP = 1_700_000_000;

describe("createSigner", () => {
  it("signs accessToken.timeStamp with RSA-SHA256 PKCS#1 v1.5", () => {
    const headers = createSigner(TEST_TOKEN, TEST_KEY_PEM).headers(FIXED_TIMESTAMP);

    expect(headers.accessToken).toBe(TEST_TOKEN);
    expect(headers.timeStamp).toBe(String(FIXED_TIMESTAMP));
    const verified = createVerify("RSA-SHA256")
      .update(`${TEST_TOKEN}.${FIXED_TIMESTAMP}`)
      .verify(TEST_KEYS.publicKey, headers.signature ?? "", "base64");
    expect(verified).toBe(true);
    // PKCS#1 v1.5 is deterministic: same bytes as any other correct implementation.
    const reference = createSign("RSA-SHA256")
      .update(`${TEST_TOKEN}.${FIXED_TIMESTAMP}`)
      .sign(TEST_KEYS.privateKey, "base64");
    expect(headers.signature).toBe(reference);
  });

  it("defaults the timestamp to now, in whole seconds", () => {
    const stamp = Number(createSigner(TEST_TOKEN, TEST_KEY_PEM).headers().timeStamp);
    expect(Math.abs(stamp - Date.now() / 1000)).toBeLessThan(5);
  });

  it("accepts a path, PEM text and DER bytes", () => {
    const file = join(mkdtempSync(join(tmpdir(), "xyp-")), "private.key");
    writeFileSync(file, TEST_KEY_PEM);
    const der = TEST_KEYS.privateKey.export({ type: "pkcs8", format: "der" });
    const expected = createSigner(TEST_TOKEN, TEST_KEY_PEM).headers(FIXED_TIMESTAMP);

    const pkcs1 = TEST_KEYS.privateKey.export({ type: "pkcs1", format: "der" });
    for (const source of [
      file,
      new Uint8Array(der),
      new Uint8Array(pkcs1),
      Buffer.from(TEST_KEY_PEM),
    ]) {
      expect(createSigner(TEST_TOKEN, source).headers(FIXED_TIMESTAMP)).toEqual(expected);
    }
  });

  it("rejects bad input without leaking key material", () => {
    expect(() => createSigner("", TEST_KEY_PEM)).toThrow(/accessToken/);
    expect(() => createSigner(TEST_TOKEN, "/no/such/key.pem")).toThrow(/Cannot read.*ENOENT/);
    const broken = "-----BEGIN PRIVATE KEY-----\nsecret-material\n-----END PRIVATE KEY-----";
    expect(() => createSigner(TEST_TOKEN, broken)).toThrow(XypConfigError);
    try {
      createSigner(TEST_TOKEN, broken);
    } catch (error) {
      expect(String(error)).not.toContain("secret-material");
    }
    const ec = generateKeyPairSync("ec", { namedCurve: "P-256" });
    const ecPem = ec.privateKey.export({ type: "pkcs8", format: "pem" }).toString();
    expect(() => createSigner(TEST_TOKEN, ecPem)).toThrow(/RSA/);
  });
});
