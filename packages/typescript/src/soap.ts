/**
 * SOAP 1.1 document/literal envelopes for XYP. Pure functions, no I/O.
 *
 * Request (only the operation element is namespaced):
 *   <soap:Envelope><soap:Body><tns:OP>
 *     <request> <auth><citizen/><operator/></auth> ...fields... </request>
 *   </tns:OP></soap:Body></soap:Envelope>
 *
 * Response:
 *   <return> <requestId/> <resultCode/> <resultMessage/> <response>...</response> </return>
 */

import { XMLParser } from "fast-xml-parser";
import { type AuthEntity, authToWire } from "./auth.js";
import { XypResponseError } from "./errors.js";

const SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
const XML_DECLARATION = "<?xml version='1.0' encoding='utf-8'?>\n";
const ATTRIBUTE_PREFIX = "@_";
const TEXT_KEY = "#text";
const NIL_ATTRIBUTE = `${ATTRIBUTE_PREFIX}nil`;

export interface ServiceResult {
  readonly requestId: string | null;
  readonly resultCode: number;
  readonly message: string;
  readonly data: unknown;
}

export function buildEnvelope(
  operation: string,
  namespace: string,
  params: Readonly<Record<string, unknown>>,
  citizen?: AuthEntity,
  operator?: AuthEntity,
): string {
  // <auth> comes first: every request type extends `serviceRequest`, so that is its
  // position in the schema and where zeep (the known-working client) puts it.
  const authBlock =
    citizen || operator
      ? element("auth", {
          citizen: citizen ? authToWire(citizen) : undefined,
          operator: operator ? authToWire(operator) : undefined,
        })
      : "";
  const request = wrap("request", authBlock + fields(params));
  const body = wrap("soap:Body", wrap(`tns:${operation}`, request));
  const attributes = `xmlns:soap="${SOAP_NAMESPACE}" xmlns:tns="${escapeAttribute(namespace)}"`;
  return `${XML_DECLARATION}<soap:Envelope ${attributes}>${body}</soap:Envelope>`;
}

function wrap(name: string, inner: string): string {
  return inner === "" ? `<${name} />` : `<${name}>${inner}</${name}>`;
}

function fields(values: Readonly<Record<string, unknown>>): string {
  return Object.entries(values)
    .map(([name, value]) => element(name, value))
    .join("");
}

function element(name: string, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.map((item) => element(name, item)).join("");
  if (isPlainObject(value)) return wrap(name, fields(value));
  return wrap(name, escapeText(toText(value)));
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !(value instanceof Date) &&
    !(value instanceof Uint8Array)
  );
}

function toText(value: unknown): string {
  // "1"/"0" is valid for xs:boolean and xs:int alike; the portal calls some
  // xs:int flags "boolean", so this form is accepted whichever the server declares.
  if (typeof value === "boolean") return value ? "1" : "0";
  if (value instanceof Uint8Array) return Buffer.from(value).toString("base64");
  // Same lexical form zeep sends: UTC as "Z", no zero milliseconds.
  if (value instanceof Date) return value.toISOString().replace(/\.000Z$/, "Z");
  return String(value);
}

function escapeText(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function escapeAttribute(text: string): string {
  return escapeText(text).replace(/"/g, "&quot;");
}

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: ATTRIBUTE_PREFIX,
  textNodeName: TEXT_KEY,
  removeNSPrefix: true,
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: true,
  htmlEntities: true, // also decodes numeric character references such as &#1041;
});

export function parseResponse(payload: string): ServiceResult {
  // SOAP forbids DTDs, and refusing them outright rules out entity-expansion attacks.
  if (/<!DOCTYPE/i.test(payload)) {
    throw new XypResponseError("XYP returned a response that is not valid XML");
  }
  let root: unknown;
  try {
    root = parser.parse(payload, true);
  } catch {
    throw new XypResponseError("XYP returned a response that is not valid XML");
  }

  const fault = findElement(root, "Fault");
  if (fault !== undefined) {
    const reason = textOf(child(fault, "faultstring")) ?? "unknown SOAP fault";
    throw new XypResponseError(`XYP returned a SOAP fault: ${reason}`);
  }
  const result = findElement(root, "return");
  if (result === undefined) throw new XypResponseError("XYP response has no <return> element");

  const codeText = textOf(child(result, "resultCode"));
  if (codeText === null || !/^-?\d+$/.test(codeText)) {
    throw new XypResponseError("XYP response has no numeric <resultCode>");
  }
  return {
    requestId: textOf(child(result, "requestId")),
    resultCode: Number(codeText),
    message: textOf(child(result, "resultMessage")) ?? "",
    data: toPlain(child(result, "response")),
  };
}

function child(node: unknown, name: string): unknown {
  return isRecord(node) ? node[name] : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Depth-first search for the first element called `name`. */
function findElement(node: unknown, name: string): unknown {
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = findElement(item, name);
      if (found !== undefined) return found;
    }
    return undefined;
  }
  if (!isRecord(node)) return undefined;
  if (name in node) return node[name];
  return findElement(Object.values(node), name);
}

function textOf(node: unknown): string | null {
  const plain = toPlain(node);
  return typeof plain === "string" ? plain : null;
}

/** Leaf -> string | null, element with children -> object, repeated tags -> array. */
function toPlain(node: unknown): unknown {
  if (node === undefined || node === null || node === "") return null;
  if (Array.isArray(node)) return node.map(toPlain);
  if (!isRecord(node)) return String(node);
  if (node[NIL_ATTRIBUTE] === "true" || node[NIL_ATTRIBUTE] === "1") return null;

  const children = Object.entries(node).filter(
    ([key]) => !key.startsWith(ATTRIBUTE_PREFIX) && key !== TEXT_KEY,
  );
  if (children.length === 0) return toPlain(node[TEXT_KEY]);
  return Object.fromEntries(children.map(([key, value]) => [key, toPlain(value)]));
}
