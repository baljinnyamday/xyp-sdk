# xyp-sdk for Java

[![Maven Central](https://img.shields.io/maven-central/v/io.github.baljinnyamday/xyp-sdk?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.baljinnyamday/xyp-sdk)
[![javadoc](https://javadoc.io/badge2/io.github.baljinnyamday/xyp-sdk/javadoc.svg)](https://javadoc.io/doc/io.github.baljinnyamday/xyp-sdk)
[![java](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/java.yml/badge.svg)](https://github.com/baljinnyamday/xyp-sdk/actions/workflows/java.yml)

Typed Java SDK for **XYP (ХУР)**, Mongolia's government data exchange system.
All 499 services, Java 17+, no third-party dependencies, zero configuration for
TLS and signing.

Maven:

```xml
<dependency>
  <groupId>io.github.baljinnyamday</groupId>
  <artifactId>xyp-sdk</artifactId>
  <version>0.1.0-alpha.1</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("io.github.baljinnyamday:xyp-sdk:0.1.0-alpha.1")
```

Gradle (Groovy DSL):

```groovy
implementation 'io.github.baljinnyamday:xyp-sdk:0.1.0-alpha.1'
```

Maven and Gradle resolve exactly the version you name, so a pre-release is never
picked up by accident; name it explicitly as above.

```java
import io.github.baljinnyamday.xyp.XypClient;
import io.github.baljinnyamday.xyp.XypKeys;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoParams;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoResponse;

try (XypClient xyp = XypClient.builder()
    .accessToken(token)
    .privateKey(XypKeys.loadPrivateKey(Path.of("private.key")))
    .build()) {
  GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(
      GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build());
  System.out.println(card.firstname() + " " + card.lastname());
}
```

That is the whole integration. The SDK signs every request, builds the SOAP
envelope, verifies XYP's certificate and turns the XML into records. In an
application, build one `XypClient` at startup and close it at shutdown: it is
immutable, safe for concurrent use and keeps connections alive.

Standard library only (`java.net.http`, `java.xml`) — nothing enters your
dependency tree but this jar.

> **Status: alpha.** The production system is only reachable from the National
> Data Center VPN, so the SDK is verified differentially instead: for every
> operation in the WSDLs we hold, the request XML is byte-identical to what
> [zeep](https://docs.python-zeep.org) — the SOAP library behind the official
> samples and known-working integrations — builds from the WSDL, and the test
> suite sends those same envelopes through the generated Java methods. Endpoints
> whose WSDL is not public are covered by the same code paths but not by that
> comparison. Please open an issue with anything that differs in practice.

## Requirements

- Java 17 or later (tested on 17, 21 and 25).
- An access token and RSA key issued by the National Data Center, and a working
  connection to their VPN with `xyp.gov.mn` in your hosts file
  ([connection guide](https://developer.xyp.gov.mn/docs/guide)). The SDK cannot
  replace that step.

## Credentials

```java
PrivateKey fromFile = XypKeys.loadPrivateKey(Path.of("private.key")); // PEM or DER file
PrivateKey fromSecret = XypKeys.parsePrivateKey(pemOrDerBytes); // from your secret manager
XypClient fromEnvironment = XypClient.builder().build(); // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
```

Both PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`) keys are
read. `privateKey(...)` takes any RSA `java.security.PrivateKey`, so a key that
never leaves a `KeyStore` or an HSM (through the JDK's PKCS#11 provider) works
as well as one on disk:

```java
KeyStore store = KeyStore.getInstance("PKCS12");
try (InputStream in = Files.newInputStream(Path.of("xyp.p12"))) {
  store.load(in, password);
}
PrivateKey key = (PrivateKey) store.getKey("xyp", password);
XypClient xyp = XypClient.builder().accessToken(token).privateKey(key).build();
```

Encrypted key files are not supported: decrypt once with
`openssl pkey -in encrypted.key -out private.key`, or load the key yourself and
pass the `PrivateKey`.

Keep the token and key out of your source code. Neither appears in the client's
`toString()`, in log lines or in exception messages.

## Finding a service

Services are grouped by XYP endpoint, one accessor and one package each, and
keep XYP's own names minus the code: `WS100101_getCitizenIDCardInfo` is
`xyp.citizen().getCitizenIDCardInfo(...)`, with its request in
`GetCitizenIDCardInfoParams` and its answer in `GetCitizenIDCardInfoResponse`.
A leading run of capitals is lowered the Java way (`PS100111_IDCardInfoEm` is
`idCardInfoEm`). Every method's Javadoc starts with the original name and
description, so searching for `WS100101` finds it.

| Accessor | Package `io.github.baljinnyamday.xyp.…` | Services |
| --- | --- | ---: |
| `xyp.citizen()` | `citizen` | 83 |
| `xyp.health()` | `health` | 76 |
| `xyp.insurance()` | `insurance` | 67 |
| `xyp.governmentService()` | `governmentservice` | 54 |
| `xyp.transport()` | `transport` | 48 |
| `xyp.payment()` | `payment` | 33 |
| `xyp.property()` | `property` | 30 |
| `xyp.statistic()` | `statistic` | 24 |
| `xyp.laborWelfare()` | `laborwelfare` | 19 |
| `xyp.legalEntity()` | `legalentity` | 19 |
| `xyp.education()` | `education` | 18 |
| `xyp.tax()` | `tax` | 9 |
| `xyp.meta()` | `meta` | 6 |
| `xyp.foreignService()` | `foreignservice` | 6 |
| `xyp.privateService()` | `privateservice` | 6 |
| `xyp.pki()` | `pki` | 1 |

Every method has two overloads: one without and one with `CallOptions`, which
carries the approvals (below) and an endpoint override. A service without inputs
takes no params; the one service without documented outputs
(`governmentService().treeRegister`) returns the raw response tree.

Prefer the original names, or need a service newer than this SDK version?

```java
Object data = xyp.call("WS100101_getCitizenIDCardInfo",
    Params.of("regnum", "РД00000000")); // the response tree

Object fresh = xyp.call("WS109999_brandNew", Params.of("regnum", "РД00000000"),
    CallOptions.builder().endpoint("citizen-1.5.0").build());
```

The tree is nested unmodifiable `Map<String, Object>` (in document order),
`List<Object>`, `String` and `null`. `invoke` does the same call but decodes
into a type of your own:

```java
record IdCard(String firstname, XypDate birthDate, Extras extras) {
  static IdCard decode(ResponseReader r) {
    return new IdCard(
        r.get("firstname", Decoders.STRING), r.get("birthDate", Decoders.DATE), r.extras());
  }
}

IdCard card = xyp.invoke(
    "WS100101_getCitizenIDCardInfo", Params.of("regnum", regnum), CallOptions.none(),
    IdCard::decode);
```

The generated records decode the same way: `GetCitizenIDCardInfoResponse::decode`
can be passed to `invoke`, for example with an endpoint override.

Request parameters for `call` and `invoke` may be `null`, a generated
`*Params` (sent in schema order), `Params` (sent in your order), any
`RequestParams` of your own, or a `Map<String, ?>`. A `LinkedHashMap` or a
`SortedMap` is sent in its iteration order; any other map (`HashMap`,
`Map.of`) has its keys sorted, because its order is not defined — use `Params`
when the schema order matters.

## Citizen approval (OTP, fingerprint, digital signature, ДАН)

Many services only answer when the citizen has approved the request:

```java
// 1. ask XYP to text the citizen a code for the services you are going to call
xyp.meta().registerOTPRequest(
    RegisterOTPRequestParams.builder()
        .regnum(regnum)
        .jsonWSList("[{\"ws\": \"WS100101_getCitizenIDCardInfo\"}]")
        .isSms(1L)
        .build(),
    CallOptions.builder().citizen(Auth.builder().regnum(regnum).build()).build());

// 2. call the service with the code they read out to you
GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(
    GetCitizenIDCardInfoParams.builder().regnum(regnum).build(),
    CallOptions.builder().citizen(Auth.otp(regnum, 123456)).build());
```

The other approvals, and the operator's for services that need it too:

```java
Auth sso = Auth.ssoOtp(regnum, 123456);
Auth signed = Auth.signature(regnum, signature, certFingerprint);
Auth scanned = Auth.fingerprint(regnum, scan); // the scanner's image bytes
Auth dan = Auth.danApp(regnum);

CallOptions options = CallOptions.builder()
    .citizen(Auth.otp(regnum, 123456))
    .operator(Auth.fingerprint(operatorRegnum, scan))
    .build();
```

`Auth.builder()` covers any other combination (`civilId`, `appAuthToken`, …).
An `Auth` prints as `Auth[authType=SMS_OTP]`: the registration number, the code
and the fingerprint never reach a log.

## Errors

Every exception is unchecked and extends `XypException`.

```java
try {
  return xyp.citizen().getCitizenIDCardInfo(
      GetCitizenIDCardInfoParams.builder().regnum(regnum).build());
} catch (XypApiException e) {
  switch (e.reason()) {
    case NOT_FOUND -> {
      return null; // resultCode 1: the provider has no record
    }
    case ACCESS_DENIED -> throw new IllegalStateException("ask the NDC for access", e);
    default -> {
      log.warn("XYP said {} (request {})", e.getMessage(), e.requestId());
      throw e;
    }
  }
} catch (XypConnectionException e) {
  if (e.isTimeout()) {
    throw new IllegalStateException("XYP is slow, try again later", e);
  }
  throw e; // VPN down, hosts entry missing, TLS
}
```

| `XypApiException.Reason` | `resultCode` |
| --- | --- |
| `NOT_FOUND` | 1 |
| `INTERNAL` | 2 |
| `INVALID_REQUEST` | 3 (missing input, bad token / timestamp / signature header) |
| `AUTH_REQUIRED` | 200–202 |
| `ACCESS_DENIED` | 203, 501 |
| `FINGERPRINT` | 301–304 |
| `CITIZEN_DATA` | 401–402 |
| `SIGNATURE` | 601–605 |
| `PROVIDER` | 801–802 |
| `OTHER` | anything else |

`resultCode()`, `resultMessage()` and `requestId()` are XYP's own values;
`requestId()` is what XYP support will ask you for.

Every exception carries an origin, so you (and your logs) know whose side it is
on. `XypException.originOf(throwable)` works on any throwable and looks through
the causes, so an SDK exception you wrapped keeps its origin:

| `e.origin()` | Meaning | Exceptions |
| --- | --- | --- |
| `CONFIG` | how the client or the call was set up | `XypConfigException` |
| `NETWORK` | VPN, hosts entry, TLS, timeout | `XypConnectionException` (`isTimeout()` for deadlines; `getCause()` keeps the JDK's exception) |
| `XYP` | XYP or the data provider answered with an error | `XypApiException`, `XypResponseException` (`statusCode()` for a gateway page or a SOAP fault) |
| `SDK` | a gap in this SDK — please report it | anything else |

```java
Origin origin = XypException.originOf(failure); // CONFIG, NETWORK, XYP or SDK
```

The timeout (30 seconds unless you set `timeout(Duration)`) covers the whole
exchange, from connecting to reading the last byte. An interrupted call throws
`XypConnectionException` and keeps the thread's interrupt flag set.

### When XYP's data does not match the types

The response types are generated from XYP's public catalog, which is typed by
hand, so real data can disagree with them. That never costs you a call:

```java
GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(params); // succeeds
String firstname = card.firstname(); // everything that fits is typed as usual
byte[] photo = card.image(); // null: this field did not fit
for (Mismatch mismatch : card.extras().mismatches()) {
  System.out.println(mismatch); // "image (not a valid bytes)", never the value
  Object sent = mismatch.value(); // the raw value, when you do want it
}
Object raw = card.extras().raw(); // the whole response, undeclared fields included
```

A field that does not fit is `null` (an empty list for a list) and listed in
`extras().mismatches()`. The SDK also writes one `System.Logger` warning per
call, naming the operation and the fields and saying plainly that the SDK's
model is wrong — not XYP, not your code. It contains field names only, never
values, so citizen data stays out of your logs; the same goes for `Mismatch`'s
and `Extras`' `toString()`. `System.Logger` goes to `java.util.logging` unless
you install a bridge (SLF4J's `slf4j-jdk-platform-logging`, Log4j's
`log4j-jpl`); pass `logger(...)` to the builder to route or silence it.

The SDK's own types (`XypClient`, `Auth`, `Mismatch`, `Extras`, the
exceptions) never print a token, a key, a one-time code or a response value.
Response records (and values such as `XypDate`) are different: they are plain
data carriers, and their `toString()` is the one a record always has, printing
every field it holds —
citizen data included (`listAccess` even echoes your access token). Log the
fields you need, never a whole response.

## TLS

XYP's certificate is issued by the Mongolian national CA, which no operating
system or JDK trusts by default — the reason most integrations switch
verification off, and why the official Java sample imports the CA into the
JDK's `cacerts` instead, which changes trust for every program on that JDK and
has to be redone on every JDK upgrade and container image. This SDK bundles the
national root and issuing CA and verifies against exactly those, for its own
requests only. It never touches `cacerts`, the default `SSLContext` or a
`javax.net.ssl.*` system property.

```java
XypClient xyp = XypClient.builder()
    .baseUrl("https://xyp-proxy.example.internal") // only if you go through a proxy
    .trustedCertificates(trusted) // instead of the bundled national CAs
    // .sslContext(context) // or: full control
    // .insecureSkipVerify(true) // or: off — read docs/tls.md first
    .build();
```

`XypTls.bundledCertificates()` returns a fresh, modifiable list holding the two
certificates; add your proxy's CA to it when you need both:

```java
X509Certificate proxyCa;
try (InputStream in = Files.newInputStream(Path.of("proxy-ca.pem"))) {
  proxyCa = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
}
List<X509Certificate> trusted = XypTls.bundledCertificates();
trusted.add(proxyCa);
```

`sslContext(...)` takes precedence over `trustedCertificates(...)`;
`XypTls.sslContext(certificates)` builds one that trusts exactly the
certificates you give it. `insecureSkipVerify(true)` turns off certificate and
host name checks for this client only, and cannot be combined with the other
two. Only TLS 1.2 and 1.3 are offered. Details and the reasoning:
[docs/tls.md](https://github.com/baljinnyamday/xyp-sdk/blob/main/docs/tls.md).

## Types

| XYP | Request (`*Params`) | Response (record) | Why |
| --- | --- | --- | --- |
| `String` | `String` | `String` | XML cannot tell an empty string from a missing element, so both are `null` |
| `int`, `long` | `Long` | `Long` | `null` is absent; `0` is a real answer |
| `double`, `float` | `Double` | `Double` | same |
| `boolean` | `Boolean` (sent as `1`/`0`) | `Boolean` | same; `false` is a real answer |
| `BigDecimal` | `BigDecimal` | `BigDecimal` | exact: every digit and the scale are kept |
| `Date` | `XypDate` | `XypDate` | `raw()` is always the text XYP sent; `time()` is set only when it is ISO 8601 (a value without a zone is read as UTC). Formats are undocumented, so nothing is rejected. `XypDate.of(Instant)` is sent in UTC |
| `byte[]` | `byte[]` (copied) | `byte[]` | photos and PDFs, base64 on the wire; text where bytes were expected becomes a mismatch instead of garbage |
| an object | `Map<String, Object>` | a nested record, e.g. `GetCitizenIDCardInfoResponse.ListAddress` | `null` is absent |
| a list | `List<T>` (copied) | `List<T>`, unmodifiable | never `null`; a single element from XYP is still a one-item list |
| undocumented | `Object` | `Object` | the raw subtree: `Map`, `List`, `String` or `null` |

A request field left `null` (or an empty string) is not sent. The generated
`*Params` classes are immutable; their builders copy lists and arrays, and can
be reused.

A `byte[]` component of a response record, such as `image()`, is the record's
own array, not a copy: don't modify it if the record is shared. And as with any
record, `equals` and `hashCode` compare arrays by identity, so two records with
the same photo are equal only if they hold the same array
(`Arrays.equals(a.image(), b.image())` compares the contents).

## Threads, virtual threads and closing

One `XypClient` is meant to be shared: it is immutable after `build()`, safe
for concurrent use, and every group client it hands out (`xyp.citizen()`
returns the same instance every time) is stateless. A call blocks its thread
until XYP answers or the timeout passes. On Java 21 and later that is a good fit
for virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`): the SDK
holds no lock while it waits on the network.

`close()` releases the kept-alive connections. On Java 21 and later it waits for
calls in flight; on Java 17 the JDK's HTTP client cannot be closed and is
released when the client becomes unreachable. Calls after `close()` throw
`XypConfigException`.

## Testing your code

To test the whole path, point `baseUrl("http://127.0.0.1:" + port)` at a local
fake server that answers with a recorded SOAP response. To build response
records for fixtures without any HTTP, decode a tree like the one `call`
returns:

```java
Map<String, Object> tree = Map.of("regnum", "РД00000000", "firstname", "Бат", "lastname", "Дорж");
GetCitizenIDCardInfoResponse card =
    ResponseReader.decode(tree, GetCitizenIDCardInfoResponse::decode);
```

`XypClient` and the group clients are `final`; to mock them anyway, use
Mockito's inline mock maker (the default since Mockito 5), or put your own
interface in front of the calls you make.

## Modules and the class path

The jar is a named module, `io.github.baljinnyamday.xyp`, and works equally well
on the class path. From a modular application:

```java
module com.example.app {
  requires io.github.baljinnyamday.xyp;
}
```

It exports the core package and every group package, and requires only
`java.net.http` and `java.xml` — include those two if you build a runtime with
`jlink`.

## Smoke test against the real XYP

`examples/Smoke.java` is the first-contact check, from a machine on the VPN. It
calls `listAccess` (no citizen data involved) and so proves TLS against the
bundled CAs, the signature, the envelope and response parsing at once. It prints
the organisation, whether it is registered and how many services are approved —
never the whole response, which echoes your access token.

```bash
export XYP_ACCESS_TOKEN=...                  # never commit these
export XYP_PRIVATE_KEY=/path/to/private.key
./mvnw -q package -DskipTests                # or download the jar from Maven Central
java -cp target/xyp-sdk-0.1.0-alpha.1.jar examples/Smoke.java
```

It prints `OK: organisation=… registered=true approved_services=… model_mismatches=0`,
or `FAILED (network): …` with the origin of the problem.

## Development

```bash
./mvnw verify            # compile (-Xlint:all -Werror), tests, javadoc, formatting check
./mvnw spotless:apply    # format with google-java-format
```

The library is compiled for and tested on Java 17; the formatting check and
`spotless:apply` need JDK 21 or later to run, so `verify` on JDK 17 skips it.

`src/main/java/module-info.java`, `Registry.java`, `XypGroups.java` and every
`io/github/baljinnyamday/xyp/<group>/` package are generated — change
`generator/` or `spec/` and run `uv run xyp-generate` from `generator/` (it
formats the Java output with `./mvnw spotless:apply`) instead of editing them.
`src/test/resources/envelopes.json` comes from
`packages/typescript/scripts/make_fixtures.py`. `ReadmeTest` checks that every
Java snippet in this README is compiled as part of the tests.

## Versioning

Semantic versioning. While the version is `0.x`, the API may still change
between minor versions; the changes are listed in the GitHub release notes.

- Pre-releases carry a Maven qualifier: `0.1.0-alpha.1` < `0.1.0-beta.1` <
  `0.1.0-rc.1` < `0.1.0` in Maven's and Gradle's ordering.
- Published versions are immutable: Maven Central never replaces or deletes a
  release. A bad release is fixed by publishing a new version.

### Releasing

Set the version, commit, and push a tag `java-v<version>`:

```bash
./mvnw versions:set -DnewVersion=0.1.0-alpha.2 -DgenerateBackupPoms=false
git commit -am "Java SDK 0.1.0-alpha.2"
git tag java-v0.1.0-alpha.2 && git push origin java-v0.1.0-alpha.2
```

The tag must match `<version>` in `pom.xml`. The `java-release` workflow runs
the tests and the formatting check on the tag, compiles and runs an outside
project against the jar on both the class path and the module path, and only
then signs the artifacts and publishes them to Maven Central through the Central
Portal, waiting until Central has published them. It then creates the GitHub
release, and installs the published version from Maven Central into an empty
project on Java 17, so a release nobody can resolve fails loudly.

Publishing runs in the `maven-central` environment, whose secrets are
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user
token) and `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the signing key).

## License

MIT
