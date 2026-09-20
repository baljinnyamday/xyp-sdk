/**
 * Soft validation of responses.
 *
 * Response shapes are generated from XYP's public catalog, which is typed by hand,
 * so real data can disagree with them. A successful call must never be lost to that:
 * a value that does not fit becomes `null`, its raw value is kept on
 * `result.xypMismatches`, and a warning says which field it was and that the SDK's
 * model (not XYP, not the caller) is wrong.
 */

export const ISSUES_URL = "https://github.com/baljinnyamday/xyp-sdk/issues";
export const MISMATCH_WARNING = "XypModelMismatchWarning";

export type ScalarType = "string" | "int" | "float" | "bool" | "decimal" | "bytes" | "date" | "any";
export type FieldSpec = ScalarType | { readonly list: FieldSpec } | { readonly object: Schema };
export type Schema = Readonly<Record<string, FieldSpec>>;

/** One field of a response that did not fit the SDK's model. */
export interface Mismatch {
  /** e.g. "listData[1].year"; "" is the response itself. */
  readonly path: string;
  readonly problem: string;
  /** Raw value from XYP. Not enumerable: it is citizen data and stays out of logs. */
  readonly value: unknown;
}

/** A decoded response. `xypMismatches` is normally empty and is not enumerable. */
export type XypResult<T> = T & { readonly xypMismatches: readonly Mismatch[] };

// The same lenient spellings pydantic accepts, so both SDKs read a response the same way.
const TRUE_TEXT = new Set(["true", "1", "t", "yes", "y", "on"]);
const FALSE_TEXT = new Set(["false", "0", "f", "no", "n", "off"]);
const INTEGER = /^[+-]?\d+(\.0+)?$/; // "34.0" is an integer written by a spreadsheet
const FLOAT = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
// Strict on purpose: Buffer.from(text, "base64") silently turns "N/A" into garbage bytes.
const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
// Must start with a full date: a bare "2020" is a year, not a timestamp.
const ISO_DATE = /^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/;

type Report = (path: string, problem: string, value: unknown) => void;

/** Marks a value that did not fit, as opposed to a legitimate null from XYP. */
const REJECTED = Symbol("rejected");

export function decodeResponse<T>(name: string, schema: Schema, data: unknown): XypResult<T> {
  const mismatches: Mismatch[] = [];
  const report: Report = (path, problem, value) => {
    const mismatch = { path, problem };
    Object.defineProperty(mismatch, "value", { value, enumerable: false });
    mismatches.push(mismatch as Mismatch);
  };
  const isObject = data === null || isRecord(data);
  if (!isObject) report("", "expected an object", data);
  const decoded = decodeObject(schema, isRecord(data) ? data : {}, "", report);

  Object.defineProperty(decoded, "xypMismatches", { value: Object.freeze(mismatches) });
  if (mismatches.length > 0) warn(name, mismatches);
  return decoded as XypResult<T>;
}

function warn(name: string, mismatches: readonly Mismatch[]): void {
  // Field paths and reasons only. Values are citizen data and stay out of logs.
  const where = mismatches.map((m) => `${m.path || "<response>"} (${m.problem})`).join("; ");
  process.emitWarning(
    `${name}: XYP's response does not fit the SDK's model at ${where}. The call succeeded ` +
      "and nothing was lost: those fields are null and their raw values are in " +
      "`.xypMismatches`. This is a gap in the SDK's models (generated from XYP's public " +
      `catalog), not an error from XYP or in your code. Please report it at ${ISSUES_URL}.`,
    MISMATCH_WARNING,
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function decodeObject(
  schema: Schema,
  data: Record<string, unknown>,
  path: string,
  report: Report,
): Record<string, unknown> {
  // Unknown fields are kept as XYP sent them; declared fields are always present.
  const declared = Object.entries(schema).map(([key, spec]) => {
    const childPath = path ? `${path}.${key}` : key;
    return [key, decodeValue(spec, data[key] ?? null, childPath, report)] as const;
  });
  return { ...data, ...Object.fromEntries(declared) };
}

/** A declared field: a value that does not fit is reported and becomes null. */
function decodeValue(spec: FieldSpec, value: unknown, path: string, report: Report): unknown {
  const decoded = decodeItem(spec, value, path, report);
  return decoded === REJECTED ? null : decoded;
}

/** Like `decodeValue`, but tells the caller when the value itself was rejected. */
function decodeItem(spec: FieldSpec, value: unknown, path: string, report: Report): unknown {
  if (value === null || spec === "any") return value;
  if (typeof spec === "string") return decodeScalar(spec, value, path, report);
  if ("list" in spec) return decodeList(spec.list, value, path, report);
  if (isRecord(value)) return decodeObject(spec.object, value, path, report);
  report(path, "expected an object", value);
  return REJECTED;
}

function decodeList(item: FieldSpec, value: unknown, path: string, report: Report): unknown {
  // XML cannot tell a one-item list from a single value; the schema can.
  // Empty/nil items carry no data and would break the `T[]` promise, so they are dropped.
  const items = (Array.isArray(value) ? value : [value]).filter((entry) => entry !== null);
  const decoded = items.map((entry, index) => decodeItem(item, entry, `${path}[${index}]`, report));
  // The types promise `T[]`, never `(T | null)[]`: when an item itself does not fit,
  // the whole list becomes null and the raw items stay available in the mismatches.
  return decoded.includes(REJECTED) ? REJECTED : decoded;
}

function decodeScalar(type: ScalarType, value: unknown, path: string, report: Report): unknown {
  if (typeof value !== "string") {
    report(path, `expected ${type}, got ${Array.isArray(value) ? "a list" : "an object"}`, value);
    return REJECTED;
  }
  const decoded = SCALAR_DECODERS[type](value);
  if (decoded === undefined) {
    report(path, `not a valid ${type}`, value);
    return REJECTED;
  }
  return decoded;
}

/** Each decoder returns `undefined` when the text does not fit. */
const SCALAR_DECODERS: Readonly<Record<ScalarType, (text: string) => unknown>> = {
  any: (text) => text,
  string: (text) => text,
  decimal: (text) => (FLOAT.test(text) ? text : undefined), // kept as text: no precision loss
  int: (text) => {
    const number = Number(text);
    return INTEGER.test(text) && Number.isSafeInteger(number) ? number : undefined;
  },
  float: (text) => (FLOAT.test(text) ? Number(text) : undefined),
  bool: (text) => {
    const lower = text.toLowerCase();
    if (TRUE_TEXT.has(lower)) return true;
    return FALSE_TEXT.has(lower) ? false : undefined;
  },
  bytes: (text) => {
    const compact = text.replace(/\s/g, "");
    return BASE64.test(compact) ? new Uint8Array(Buffer.from(compact, "base64")) : undefined;
  },
  // Providers fill date fields by hand and formats are not documented:
  // ISO 8601 becomes a Date, anything else is kept as text rather than rejected.
  date: (text) => {
    const parsed = ISO_DATE.test(text) ? new Date(text) : undefined;
    return parsed && !Number.isNaN(parsed.getTime()) ? parsed : text;
  },
};
