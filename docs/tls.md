# TLS verification

Short version: the SDK verifies the XYP server certificate by default, using
the Mongolian national CA certificates it ships with. You can turn verification
off with one option, but you probably don't need to.

## Why XYP calls fail with a normal HTTPS client

`xyp.gov.mn` presents a certificate issued by the Mongolian national PKI:

```
MNRCA-2021 (root)  →  MNICA-2022 (issuing)  →  xyp.gov.mn
```

These CAs are not in the trust stores that ship with operating systems,
browsers, Python, Node.js, Go, Java or PHP. So a default HTTPS client rejects
the connection with an error like `CERTIFICATE_VERIFY_FAILED`,
`unable to get local issuer certificate` or `x509: certificate signed by
unknown authority`.

Nothing is wrong with the certificate. Your machine just has not been told to
trust the CA that signed it.

## What everyone does today

Almost every XYP integration turns verification off, because it is the first
thing that makes the error go away:

| Language | Typical workaround |
| --- | --- |
| Node.js | `process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'` |
| Python | `session.verify = False` |
| Go | `InsecureSkipVerify: true` |
| Java | trust-all `TrustManager` |
| PHP | `verify_peer => false` |

The official example code on developer.xyp.gov.mn does this too (the Node.js
sample sets `NODE_TLS_REJECT_UNAUTHORIZED = '0'`). The same portal's
[connection guide](https://developer.xyp.gov.mn/docs/guide) describes the
correct fix — install MNRCA-2021 as a trusted root and MNICA-2021/2022 as
intermediates — but that is a manual, per-machine step, so it gets skipped.

Note that the Node.js workaround is process-wide: it disables verification for
every HTTPS request your application makes, not only the ones to XYP.

## What this SDK does instead

The SDK bundles MNRCA-2021 and MNICA-2022 (both valid until 2031; MNICA-2021
expired in February 2026) and builds a trust store that contains only them,
used only for XYP requests. The bundled files are byte-for-byte the ones
published at https://esign.gov.mn/MNRCA.zip and https://esign.gov.mn/MNICA.zip:

| Certificate | SHA-256 fingerprint |
| --- | --- |
| MNRCA-2021 | `CB:E7:F3:FE:1F:04:80:37:C2:15:DA:32:1E:58:CA:A4:F3:63:DE:9E:54:BB:C4:42:A3:BF:D6:2F:AD:83:44:82` |
| MNICA-2022 | `94:23:64:0D:D7:45:61:D1:AF:1E:A8:A0:93:86:0B:DA:7D:F5:B5:62:0B:B6:17:92:13:95:DC:0D:1A:1F:98:0D` |

That gives you:

- verification that works out of the box, with no OS or JVM setup
- no effect on any other HTTPS traffic in your application
- a connection that fails loudly if something other than XYP answers

## Is turning it off actually dangerous?

Less than on the public internet, but not zero.

XYP is only reachable through the National Data Center OpenVPN tunnel, so an
attacker on your office Wi-Fi or your cloud provider's network cannot get
between you and the server. That is the argument for "it's fine".

The argument against:

- `xyp.gov.mn` has no public DNS record. You reach it through a line in your
  `hosts` file, so DNS authenticates nothing. The certificate is the only
  thing that proves the machine answering is XYP.
- The VPN is shared with other connected organisations. It narrows who can
  attack you; it does not remove them.
- A wrong `hosts` entry, a proxy in the path, or a compromised host on your
  side would all go unnoticed with verification off.
- The payload is citizen data — ID card details, addresses, insurance and
  health records — and your access token travels in a header on every
  request.

With verification on, all of those fail closed instead of silently.

## Turning it off

If you have to — a proxy that re-terminates TLS, a test environment, a
certificate rotation the SDK has not caught up with yet — every SDK exposes a
single explicit option for it, scoped to the XYP client only. It is never the
default, and it never touches global process settings. In Python:

```python
Xyp(..., verify="/path/to/ca.pem")   # trust a different CA (proxy, new national CA)
Xyp(..., verify=ssl_context)         # full control
Xyp(..., verify=False)               # no verification
```

If verification starts failing after XYP renews its certificate under a new
CA, update the SDK, or pass the new CA certificate through `verify=`.

## Status

The CA chain above comes from the official connection guide, and the two
bundled certificates validate as a chain under strict X.509 checks. What has
not been confirmed yet is the live handshake with `xyp.gov.mn`, which is only
possible from inside the VPN: `packages/python/examples/smoke.py` does exactly
that. If it fails for you with a certificate error, please open an issue with
the error text — and use `verify=` as above in the meantime.
