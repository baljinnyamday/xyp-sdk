# xyp

Typed Python SDK for **XYP (ХУР)**, Mongolia's government data exchange system.
All 499 services, sync and async, fully type-hinted.

```bash
pip install --pre xyp   # pre-release until it has been confirmed against production
```

```python
from xyp import Xyp

xyp = Xyp(access_token="...", private_key="private.key")

card = xyp.citizen.get_citizen_id_card_info(regnum="РД00000000")
print(card.firstname, card.lastname)
```

That is the whole integration. The SDK signs every request, builds the SOAP
envelope, verifies XYP's certificate and turns the XML into typed objects.

> **Status: alpha.** The production system is only reachable from the National
> Data Center VPN, so the SDK is verified differentially instead: for every
> operation in the WSDLs we hold, the test suite checks that the XML this SDK
> sends is identical to what [zeep](https://docs.python-zeep.org) — the SOAP
> library behind the official samples and known-working integrations — builds
> from the WSDL, that responses are decoded to the same values, and that the
> request signature is byte-identical to the official `XypSign.py`. Endpoints
> whose WSDL is not public are covered by the same code paths but not by that
> comparison. Please open an issue with anything that differs in practice.

## Requirements

- Python 3.10+
- An access token and RSA key issued by the National Data Center, and a working
  connection to their VPN with `xyp.gov.mn` in your hosts file
  ([connection guide](https://developer.xyp.gov.mn/docs/guide)). The SDK cannot
  replace that step.

## Credentials

```python
Xyp(access_token="...", private_key="private.key")  # file path
Xyp(access_token="...", private_key=pem_text_or_bytes)  # from your secret manager
Xyp()  # XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
```

Keep the token and key out of your source code; environment variables or a
secret manager are the intended way. Neither ever appears in `repr()`, logs or
exception messages.

## Finding a service

Services are grouped by XYP endpoint, and named after the original operation in
snake_case. Your editor autocompletes all of them:

| Group | Services | | Group | Services |
| --- | --- | --- | --- | --- |
| `xyp.citizen` | 83 | | `xyp.property` | 30 |
| `xyp.health` | 76 | | `xyp.statistic` | 24 |
| `xyp.insurance` | 67 | | `xyp.labor_welfare` | 19 |
| `xyp.government_service` | 54 | | `xyp.legal_entity` | 19 |
| `xyp.transport` | 48 | | `xyp.education` | 18 |
| `xyp.payment` | 33 | | `xyp.tax`, `xyp.meta`, `xyp.foreign_service`, `xyp.private_service`, `xyp.pki` | 28 |

`WS100101_getCitizenIDCardInfo` becomes `xyp.citizen.get_citizen_id_card_info`.
Every method's docstring starts with the original name, so searching the
package for `WS100101` finds it. Inputs are keyword arguments; the result is a
[Pydantic](https://docs.pydantic.dev) model (`.model_dump()`, `.model_dump_json()`).
Need the type for an annotation? `from xyp.models.citizen import GetCitizenIdCardInfoResponse`.

Prefer the original names? Call any service by name and get the raw data:

```python
xyp.call("WS100101_getCitizenIDCardInfo", {"regnum": "РД00000000"})  # -> dict
```

This also works for services XYP adds before the SDK is updated — pass
`endpoint="citizen-1.5.0"` for those.

## Async

```python
from xyp import AsyncXyp

async with AsyncXyp(access_token="...", private_key="private.key") as xyp:
    card = await xyp.citizen.get_citizen_id_card_info(regnum="РД00000000")
```

Same options, same services. Create one client and reuse it — it keeps a
connection pool. Use it as a context manager or call `close()`.

## Citizen approval (OTP, fingerprint, digital signature, ДАН)

Many services only answer when the citizen has approved the request:

```python
from xyp import CitizenAuth

# 1. ask XYP to text the citizen a code for the services you are going to call
xyp.meta.register_otp_request(
    regnum=regnum,
    json_ws_list='[{"ws": "WS100101_getCitizenIDCardInfo"}]',
    is_sms=1,
    auth=CitizenAuth(regnum=regnum),
)

# 2. call the service with the code they read out to you
card = xyp.citizen.get_citizen_id_card_info(
    regnum=regnum,
    auth=CitizenAuth.with_otp(regnum=regnum, otp=123456),
)
```

Also available: `CitizenAuth.with_sso_otp`, `.with_signature`,
`.with_fingerprint`, `.with_dan_app`, and `OperatorAuth` with the same
constructors for services that need the operator too (`operator=`).

## Errors

```python
from xyp import NotFoundError, AccessDeniedError, XypApiError, XypConnectionError

try:
    card = xyp.citizen.get_citizen_id_card_info(regnum=regnum)
except NotFoundError:
    card = None  # resultCode 1: provider has no record
except AccessDeniedError:
    ...  # your token may not call this service
except XypApiError as error:
    log(error.result_code, error.message, error.request_id)
except XypConnectionError:
    ...  # VPN down, hosts entry missing, TLS, timeout
```

| Exception | `resultCode` |
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

All of them are `XypApiError`; everything the SDK raises is an `XypError`.
`request_id` is what XYP support will ask you for.

Every error has an `origin`, so you (and your logs) know whose side it is on:

| `error.origin` | Meaning | Exceptions |
| --- | --- | --- |
| `"config"` | how the client was set up | `XypConfigError` |
| `"network"` | VPN, hosts entry, TLS, timeout | `XypConnectionError`, `XypTimeoutError` |
| `"xyp"` | XYP or the data provider answered with an error | `XypApiError` and subclasses, `XypResponseError` |
| `"sdk"` | a gap in this SDK — please report it | `XypValidationError` |

### When XYP's data does not match the model

The response models are generated from XYP's public catalog, which is typed by
hand, so real data can disagree with them. That never costs you a call:

```python
card = xyp.citizen.get_citizen_id_card_info(regnum=regnum)  # succeeds
card.firstname  # everything that fits is typed as usual
card.birth_date  # None: this field did not fit
card.xyp_mismatches  # (Mismatch(path="birthDate", problem="..."),) with the raw .value
```

You also get an `XypModelMismatchWarning` pointing at your line, naming the
field and saying plainly that the SDK's model is wrong — not XYP, not your
code. It contains field names only, never values, so citizen data stays out of
your logs. Silence it with `warnings.simplefilter("ignore", XypModelMismatchWarning)`
or turn it into an error in your tests. `XypValidationError` is reserved for
the rare response that cannot be fitted at all; its `.data` still holds the
parsed response.

## TLS

XYP's certificate is issued by the Mongolian national CA, which no operating
system trusts by default — the reason most integrations (including the official
samples) switch verification off. This SDK bundles the national root and
issuing CA instead and verifies against exactly those.

```python
Xyp(..., verify=True)  # default: bundled national CAs
Xyp(..., verify="/path/to/ca.pem")  # your own CA bundle
Xyp(..., verify=ssl_context)  # full control
Xyp(..., verify=False)  # off — read docs/tls.md first
```

Going through your own HTTPS proxy? Set `base_url="https://proxy.example"` and
a `verify` that matches the proxy's certificate. Details and the reasoning:
[docs/tls.md](https://github.com/baljinnyamday/xyp-sdk/blob/main/docs/tls.md).

## Types

- Every response field is optional: providers leave fields out freely.
- Unknown fields are kept (`model.model_extra`) instead of raising.
- `Date` fields are `datetime` when XYP sends ISO 8601 and the original `str`
  otherwise; formats are not documented, so nothing is rejected.
- `byte[]` fields (photos, PDFs) arrive as `bytes`.

## Development

```bash
uv sync
uv run pytest
uv run pyright
uv run ruff check .
```

`src/xyp/operations` (one module per service), `src/xyp/services`,
`src/xyp/models`, `_groups.py` and `_operations.py` are generated — change `generator/` or `spec/` and run `uv run xyp-generate` from
`generator/` instead of editing them.

## License

MIT
