# xyp-sdk for Go

[![Go Reference](https://pkg.go.dev/badge/github.com/baljinnyamday/xyp-sdk/packages/go/xyp.svg)](https://pkg.go.dev/github.com/baljinnyamday/xyp-sdk/packages/go/xyp)
[![go](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/go.yml/badge.svg)](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/go.yml)
[![Go Report Card](https://goreportcard.com/badge/github.com/baljinnyamday/xyp-sdk/packages/go)](https://goreportcard.com/report/github.com/baljinnyamday/xyp-sdk/packages/go)

Typed Go SDK for **XYP (ХУР)**, Mongolia's government data exchange system.
All 499 services, no third-party dependencies, zero configuration for TLS and
signing.

```bash
go get github.com/baljinnyamday/xyp-sdk/packages/go@v0.1.0-alpha.1
```

While the module is pre-release, `@latest` will not pick it: ask for the version
explicitly. Once a stable version exists, `go get …/packages/go@latest` works.

```go
import (
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp"
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp/citizen"
)

client, err := xyp.NewClient(xyp.Options{AccessToken: token, PrivateKey: key})
if err != nil {
	return err
}
defer client.Close()

card, err := citizen.New(client).GetCitizenIDCardInfo(ctx,
	citizen.GetCitizenIDCardInfoParams{Regnum: "РД00000000"})
fmt.Println(card.Firstname, card.Lastname)
```

That is the whole integration. The SDK signs every request, builds the SOAP
envelope, verifies XYP's certificate and turns the XML into your structs. One
`*xyp.Client` is meant to be shared: it is safe for concurrent use and keeps
connections alive.

Standard library only — nothing enters your dependency tree but this module.

> **Status: alpha.** The production system is only reachable from the National
> Data Center VPN, so the SDK is verified differentially instead: for every
> operation in the WSDLs we hold, the request XML is byte-identical to what
> [zeep](https://docs.python-zeep.org) — the SOAP library behind the official
> samples and known-working integrations — builds from the WSDL. Endpoints whose
> WSDL is not public are covered by the same code paths but not by that
> comparison. Please open an issue with anything that differs in practice.

## Credentials

```go
key, err := xyp.LoadPrivateKey("private.key")     // PEM or DER file
key, err := xyp.ParsePrivateKey(pemOrDERBytes)    // from your secret manager
xyp.NewClient(xyp.Options{})                      // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
```

`Options.PrivateKey` is a `crypto.Signer`, so a key that never leaves an HSM or
a KMS works as well as one on disk. Encrypted key files are not supported
(Go's standard library cannot decrypt PKCS#8, and the legacy PEM encryption is
broken): decrypt once with `openssl pkey -in encrypted.key -out private.key`, or
supply your own `crypto.Signer`.

Keep the token and key out of your source code. Neither appears in
`fmt.Sprintf("%+v", client)`, `%#v`, warnings or error messages.

You also need a working connection to the National Data Center VPN with
`xyp.gov.mn` in your hosts file
([connection guide](https://developer.xyp.gov.mn/docs/guide)).

## Finding a service

Services are grouped by XYP endpoint, one Go package each, and keep XYP's own
names minus the code: `WS100101_getCitizenIDCardInfo` is
`citizen.Service.GetCitizenIDCardInfo`. Your editor autocompletes all of them,
with the original name and description in the hover text.

`citizen` (83) · `health` (76) · `insurance` (67) · `governmentservice` (54) ·
`transport` (48) · `payment` (33) · `property` (30) · `statistic` (24) ·
`laborwelfare` (19) · `legalentity` (19) · `education` (18) · `tax` (9) ·
`meta` (6) · `foreignservice` (6) · `privateservice` (6) · `pki` (1)

Prefer the original names, or need a service newer than this SDK version?

```go
data, err := client.Call(ctx, "WS100101_getCitizenIDCardInfo",
	xyp.Params{{Name: "regnum", Value: "РД00000000"}})          // response tree

data, err := client.Call(ctx, "WS109999_brandNew", params,
	xyp.WithEndpoint("citizen-1.5.0"))
```

`Invoke` does the same but decodes into a struct of your own:

```go
type IDCard struct {
	Firstname string     `xyp:"firstname"`
	BirthDate xyp.Date   `xyp:"birthDate"`
	Xyp       xyp.Extras `xyp:"-"`
}

var card IDCard
card.Xyp, err = client.Invoke(ctx, "WS100101_getCitizenIDCardInfo", params, &card)
```

Request parameters may be `nil`, a struct with `xyp:"wireName"` tags (sent in
field order), `xyp.Params` (sent in your order), or a map with string keys
(sorted, because a Go map has no order — use a struct or `xyp.Params` when the
schema order matters).

## Citizen approval (OTP, fingerprint, digital signature, ДАН)

```go
// 1. ask XYP to text the citizen a code for the services you are going to call
_, err := meta.New(client).RegisterOTPRequest(ctx, meta.RegisterOTPRequestParams{
	Regnum:     regnum,
	JsonWSList: `[{"ws": "WS100101_getCitizenIDCardInfo"}]`,
	IsSms:      xyp.Ptr(int64(1)),
}, xyp.WithAuth(xyp.Auth{Regnum: regnum}))

// 2. call the service with the code they read out to you
card, err := citizen.New(client).GetCitizenIDCardInfo(ctx,
	citizen.GetCitizenIDCardInfoParams{Regnum: regnum},
	xyp.WithAuth(xyp.OTPAuth(regnum, 123456)))
```

Also: `xyp.SSOOTPAuth`, `xyp.SignatureAuth`, `xyp.FingerprintAuth`,
`xyp.DanAppAuth`, and `xyp.WithOperator(...)` for services that need the
operator's approval too.

## Errors

```go
card, err := citizen.New(client).GetCitizenIDCardInfo(ctx, params)
switch {
case errors.Is(err, xyp.ErrNotFound):
	return nil, nil                 // resultCode 1
case errors.Is(err, xyp.ErrTimeout):
	return nil, retryLater(err)
case err != nil:
	var apiErr *xyp.APIError
	if errors.As(err, &apiErr) {
		log.Println(apiErr.ResultCode, apiErr.ResultMessage, apiErr.RequestID)
	}
	return nil, err
}
```

| Sentinel | `resultCode` |
| --- | --- |
| `xyp.ErrNotFound` | 1 |
| `xyp.ErrInternal` | 2 |
| `xyp.ErrInvalidRequest` | 3 (missing input, bad token / timestamp / signature header) |
| `xyp.ErrAuthRequired` | 200–202 |
| `xyp.ErrAccessDenied` | 203, 501 |
| `xyp.ErrFingerprint` | 301–304 |
| `xyp.ErrCitizenData` | 401–402 |
| `xyp.ErrSignature` | 601–605 |
| `xyp.ErrProvider` | 801–802 |

A code outside the table is still an `*xyp.APIError`, it just matches no
sentinel. `RequestID` is what XYP support will ask you for.

Every error carries an origin, so you (and your logs) know whose side it is on:

| `xyp.OriginOf(err)` | Meaning | Errors |
| --- | --- | --- |
| `"config"` | how the client was set up | `*xyp.ConfigError` |
| `"network"` | VPN, hosts entry, TLS, timeout | `*xyp.ConnectionError` (`errors.Is(err, xyp.ErrTimeout)` for deadlines) |
| `"xyp"` | XYP or the data provider answered with an error | `*xyp.APIError`, `*xyp.ResponseError` |
| `"sdk"` | a gap in this SDK — please report it | anything else |

`*xyp.ConnectionError` keeps its cause, so `errors.Is(err, context.Canceled)`
and the `net` package's own errors still work through `errors.As`.

### When XYP's data does not match the types

The response types are generated from XYP's public catalog, which is typed by
hand, so real data can disagree with them. That never costs you a call:

```go
card, err := citizen.New(client).GetCitizenIDCardInfo(ctx, params) // succeeds
card.Firstname          // everything that fits is typed as usual
card.BirthDate          // the zero value: this field did not fit
card.Xyp.Mismatches     // [{Path: "birthDate", Problem: "..."}] with the raw .Value()
card.Xyp.Raw            // the whole response, including undeclared fields
```

The SDK also writes one `slog` warning naming the fields and saying plainly that
the SDK's model is wrong — not XYP, not your code. It contains field names only,
never values, so citizen data stays out of your logs. Printing a `xyp.Mismatch`
with `%v`, `%+v` or `%#v` shows the path and the problem for the same reason;
`Value()` is there when you actually want the data. Point `Options.Logger` at
your own `*slog.Logger` to route or silence it.

## TLS

XYP's certificate is issued by the Mongolian national CA, which no operating
system trusts by default — the reason most integrations switch verification off.
This SDK bundles the national root and issuing CA instead and verifies against
exactly those, for XYP requests only.

```go
xyp.NewClient(xyp.Options{})                                   // default: bundled national CAs
xyp.NewClient(xyp.Options{RootCAs: pool})                      // your own CA, e.g. for a proxy
xyp.NewClient(xyp.Options{InsecureSkipVerify: true})           // off — read docs/tls.md first
```

`xyp.BundledCAs()` gives you a fresh pool holding those two certificates; add
your proxy's CA to it when you need both. Going through your own proxy? Set
`BaseURL` too. Details and the reasoning:
[docs/tls.md](https://github.com/baljinnyamday/xyp-sdk/blob/main/docs/tls.md).

## Types

| XYP | Go | Why |
| --- | --- | --- |
| `String` | `string` | XML cannot tell an empty string from a missing element, so a pointer would promise a distinction that does not exist |
| `int`, `long` | `*int64` | `nil` is absent; `0` is a real answer |
| `double`, `float` | `*float64` | same |
| `boolean` | `*bool` | same; `false` is a real answer |
| `BigDecimal` | `xyp.Decimal` (a `string`) | no precision is lost, whatever you parse it with |
| `Date` | `xyp.Date` | `Raw` is always the text XYP sent; `Time` is set only when it is ISO 8601 (a value without a zone is read as UTC). Formats are undocumented, so nothing is rejected |
| `byte[]` | `[]byte` | photos and PDFs, base64 on the wire; text where bytes were expected becomes a mismatch instead of garbage |
| an object | `*Struct`, or `Struct` inside a list | `nil` is absent; a list has no absent items |
| a list | `[]T` | a single element from XYP still becomes a one-item slice |
| undocumented | `any` | the parsed subtree, as `map[string]any`, `[]any`, `string` or `nil` |

Request parameters use the same types; a zero value is left out of the request.
`xyp.Ptr` builds the pointers: `xyp.Ptr(int64(2024))`.

## Development

```bash
go test -race ./...
go vet ./...
gofmt -l .
golangci-lint run ./...
```

`xyp/registry.go` and every `xyp/<group>/` package are generated — change
`generator/` or `spec/` and run `uv run xyp-generate` from `generator/` instead
of editing them. `xyp/testdata/envelopes.json` comes from
`packages/typescript/scripts/make_fixtures.py`.

`go run ./examples/smoke` is the first-contact check against the real XYP, from
a machine on the VPN.

## Versioning

Semantic versioning, with tags `packages/go/vX.Y.Z` — Go requires the
subdirectory prefix for a module that is not at the repository root.

- Pre-releases (`v0.1.0-alpha.1`) need an explicit version in `go get`; `@latest`
  never selects one.
- Published versions are immutable: the checksum database has already recorded
  them, so moving a tag breaks every build that saw the old one. A bad release is
  fixed by publishing a new version and adding a `retract` directive for the old
  one, never by re-tagging.
- `v2` and above would change the import path to `…/packages/go/v2`.

### Releasing

Push a tag `packages/go/v<version>`: the release workflow runs the tests,
creates the GitHub release, warms the module proxy and then installs the
published version into a throwaway project to prove it is usable.

## License

MIT
