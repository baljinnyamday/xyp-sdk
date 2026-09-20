# xyp-sdk

Typed Node.js SDK for **XYP (ХУР)**, Mongolia's government data exchange system.
All 499 services, fully typed, zero configuration for TLS and signing.

```bash
npm install xyp-sdk@alpha     # pnpm add / yarn add / bun add work the same
```

```ts
import { Xyp } from "xyp-sdk";

const xyp = new Xyp({ accessToken: "...", privateKey: "private.key" });

const card = await xyp.citizen.getCitizenIDCardInfo({ regnum: "РД00000000" });
console.log(card.firstname, card.lastname);
```

That is the whole integration. The SDK signs every request, builds the SOAP
envelope, verifies XYP's certificate and turns the XML into typed objects.

Works with ESM `import` and CommonJS `require`, in Node.js 18+, Bun, Next.js
route handlers, NestJS, Express, and anything else that runs on a server. It
ships its own types; no `@types` package needed. It does not run in browsers:
XYP needs your private key and the government VPN.

> **Status: alpha.** The production system is only reachable from the National
> Data Center VPN, so the SDK is verified differentially instead: for every
> operation in the WSDLs we hold, the request XML is byte-identical to what
> [zeep](https://docs.python-zeep.org) — the SOAP library behind the official
> samples and known-working integrations — builds from the WSDL. Endpoints whose
> WSDL is not public are covered by the same code paths but not by that
> comparison. Please open an issue with anything that differs in practice.

## Credentials

```ts
new Xyp({ accessToken, privateKey: "private.key" });   // file path
new Xyp({ accessToken, privateKey: pemTextOrBytes });  // from your secret manager
new Xyp();                                             // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
```

Keep the token and key out of your source code. Neither ever appears in
`console.log(xyp)`, `JSON.stringify`, warnings or error messages.

You also need a working connection to the National Data Center VPN with
`xyp.gov.mn` in your hosts file
([connection guide](https://developer.xyp.gov.mn/docs/guide)).

## Finding a service

Services are grouped by XYP endpoint and keep XYP's own names, minus the code:
`WS100101_getCitizenIDCardInfo` is `xyp.citizen.getCitizenIDCardInfo`. Your
editor autocompletes all of them, with the original name and description in
the hover text.

`citizen` (83) · `health` (76) · `insurance` (67) · `governmentService` (54) ·
`transport` (48) · `payment` (33) · `property` (30) · `statistic` (24) ·
`laborWelfare` (19) · `legalEntity` (19) · `education` (18) · `tax` (9) ·
`meta` (6) · `foreignService` (6) · `privateService` (6) · `pki` (1)

Prefer the original names, or need a service newer than this SDK version?

```ts
await xyp.call("WS100101_getCitizenIDCardInfo", { regnum: "РД00000000" }); // raw data
await xyp.call("WS109999_brandNew", { regnum }, { endpoint: "citizen-1.5.0" });
```

Types for annotations:

```ts
import type { GetCitizenIDCardInfoParams, GetCitizenIDCardInfoResponse } from "xyp-sdk";

function show(card: GetCitizenIDCardInfoResponse) {}
```

## Citizen approval (OTP, fingerprint, digital signature, ДАН)

```ts
import { auth } from "xyp-sdk";

// 1. ask XYP to text the citizen a code for the services you are going to call
await xyp.meta.registerOTPRequest(
  { regnum, jsonWSList: '[{"ws": "WS100101_getCitizenIDCardInfo"}]', isSms: 1 },
  { auth: { regnum } },
);

// 2. call the service with the code they read out to you
const card = await xyp.citizen.getCitizenIDCardInfo(
  { regnum },
  { auth: auth.otp({ regnum, otp: 123456 }) },
);
```

Also: `auth.ssoOtp`, `auth.signature`, `auth.fingerprint`, `auth.danApp`, and
`{ operator: ... }` for services that need the operator's approval too.

## Errors

```ts
import { NotFoundError, XypApiError, XypConnectionError } from "xyp-sdk";

try {
  const card = await xyp.citizen.getCitizenIDCardInfo({ regnum });
} catch (error) {
  if (error instanceof NotFoundError) return null;          // resultCode 1
  if (error instanceof XypApiError) log(error.resultCode, error.resultMessage, error.requestId);
  if (error instanceof XypConnectionError) retryLater();    // VPN, hosts entry, TLS, timeout
  throw error;
}
```

| Error | `resultCode` |
| --- | --- |
| `NotFoundError` | 1 |
| `InternalError` | 2 |
| `InvalidRequestError` | 3 (missing input, bad token / timestamp / signature header) |
| `AuthRequiredError` | 200–202 |
| `AccessDeniedError` | 203, 501 |
| `FingerprintError` | 301–304 |
| `CitizenDataError` | 401–402 |
| `SignatureError` | 601–605 |
| `ProviderError` | 801–802 |

Every error has an `origin`, so you (and your logs) know whose side it is on:

| `error.origin` | Meaning | Errors |
| --- | --- | --- |
| `"config"` | how the client was set up | `XypConfigError` |
| `"network"` | VPN, hosts entry, TLS, timeout | `XypConnectionError`, `XypTimeoutError` |
| `"xyp"` | XYP or the data provider answered with an error | `XypApiError` and subclasses, `XypResponseError` |
| `"sdk"` | a gap in this SDK — please report it | any other `XypError` |

`requestId` is what XYP support will ask you for.

### When XYP's data does not match the types

The response types are generated from XYP's public catalog, which is typed by
hand, so real data can disagree with them. That never costs you a call:

```ts
const card = await xyp.citizen.getCitizenIDCardInfo({ regnum }); // succeeds
card.firstname;      // everything that fits is typed as usual
card.birthDate;      // null: this field did not fit
card.xypMismatches;  // [{ path: "birthDate", problem: "..." }] with the raw .value
```

Node also prints an `XypModelMismatchWarning` naming the field and saying
plainly that the SDK's model is wrong — not XYP, not your code. It contains
field names only, never values, so citizen data stays out of your logs.
`xypMismatches` and each mismatch's `value` are non-enumerable for the same
reason: they never end up in `JSON.stringify` or `console.log` by accident.

## TLS

XYP's certificate is issued by the Mongolian national CA, which no operating
system trusts by default — the reason most integrations (including the official
Node.js sample, which sets `NODE_TLS_REJECT_UNAUTHORIZED=0` for the whole
process) switch verification off. This SDK bundles the national root and
issuing CA instead and verifies against exactly those, for XYP requests only.

```ts
new Xyp({ verify: true });            // default: bundled national CAs
new Xyp({ verify: { ca: pemText } }); // your own CA, e.g. for your HTTPS proxy
new Xyp({ verify: false });           // off — read docs/tls.md first
```

Going through your own proxy? Set `baseUrl: "https://proxy.example"` and a
`verify` that matches the proxy's certificate. Details and the reasoning:
[docs/tls.md](https://github.com/baljinnyamday/xyp-sdk/blob/main/docs/tls.md).

## Types

- Every response field is `T | null`: providers leave fields out freely.
- Unknown fields XYP sends are kept on the object.
- `int`/`long` are `number`; a value JavaScript cannot represent exactly is
  reported as a mismatch rather than silently rounded. `BigDecimal` stays a `string`.
- Date fields are a `Date` when XYP sends ISO 8601 and the original `string`
  otherwise; formats are not documented, so nothing is rejected.
- `byte[]` fields (photos, PDFs) arrive as `Uint8Array`.

## Development

```bash
pnpm install
pnpm test        # vitest, including type tests
pnpm typecheck
pnpm lint        # biome
pnpm build       # tsup: ESM + CJS + types
```

`src/operations`, `src/services`, `src/types`, `src/groups.ts` and
`src/registry.ts` are generated — change `generator/` or `spec/` and run
`uv run xyp-generate` from `generator/` instead of editing them.
`tests/fixtures/envelopes.json` comes from `scripts/make_fixtures.py`.

### Releasing

Bump `version` in `package.json`, commit, then push a tag `typescript-v<version>`:
the release workflow tests, builds and publishes with npm Trusted Publishing (no
token). Versions with a suffix (`0.1.0-alpha.2`) are published under that dist-tag
(`alpha`), so a plain `npm install xyp-sdk` never picks up a pre-release.

## License

MIT

