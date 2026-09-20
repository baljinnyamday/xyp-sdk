# xyp-sdk

SDKs for **XYP (ХУР)**, Mongolia's government data exchange system, generated
from one description of the API so every language exposes the same services.

| Language | Package | Status |
| --- | --- | --- |
| Python | [`packages/python`](packages/python) — `pip install xyp` | alpha |
| TypeScript | `packages/typescript` | planned |
| Go | `packages/go` | planned |
| Java | `packages/java` | planned |
| PHP | `packages/php` | planned |

Unofficial: this project is not affiliated with the National Data Center or
E-Mongolia Academy.

## How it fits together

```
spec/services.json   the service catalog, fetched from developer.xyp.gov.mn
spec/wsdl/*.wsdl     WSDLs we have copies of (authoritative for request types)
        │
generator/           spec -> clean, language-neutral model -> code per language
        │
packages/<language>  small hand-written core + generated models and services
```

- **Update the catalog:** `uv run spec/fetch_spec.py`
- **Regenerate:** `cd generator && uv run xyp-generate`
- **Never edit generated files** (`operations/`, `services/`, `models/`,
  `_groups.py`, `_operations.py`); change the generator or the spec.

XYP publishes SOAP WSDLs only inside its VPN, and its responses are typed
`xs:anyType`, so the public portal catalog is the only description of what
services return. That catalog is typed by hand and the generator's
`normalize.py` documents every irregularity it has to clean up.

See [docs/tls.md](docs/tls.md) for why the SDKs verify TLS against bundled
national CA certificates instead of turning verification off.

## License

MIT
