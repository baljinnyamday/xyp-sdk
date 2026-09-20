import { readFileSync } from "node:fs";
import { afterEach, describe, expect, expectTypeOf, it } from "vitest";
import { BUNDLED_CA } from "../src/certs.js";
import { apiError } from "../src/errors.js";
import {
  AccessDeniedError,
  auth,
  NotFoundError,
  Xyp,
  XypApiError,
  XypConfigError,
  XypConnectionError,
  XypResponseError,
  XypTimeoutError,
} from "../src/index.js";
import { OPERATIONS } from "../src/registry.js";
import { tlsOptions } from "../src/transport.js";
import { type FakeXyp, fakeXyp, soapResponse, TEST_KEY_PEM, TEST_TOKEN } from "./helpers.js";

const ID_CARD = "<firstname>Бат</firstname><regnum>РД00000000</regnum>";
const REGNUM = "РД00000000";

let server: FakeXyp | undefined;
let client: Xyp | undefined;
afterEach(async () => {
  client?.close();
  await server?.close();
});

function connect(fake: FakeXyp, timeoutMs = 5_000): Xyp {
  server = fake;
  client = new Xyp({
    accessToken: TEST_TOKEN,
    privateKey: TEST_KEY_PEM,
    baseUrl: fake.baseUrl,
    timeoutMs,
  });
  return client;
}

describe("Xyp", () => {
  it("calls a typed service end to end", async () => {
    const xyp = connect(await fakeXyp(() => ({ body: soapResponse(ID_CARD) })));

    const card = await xyp.citizen.getCitizenIDCardInfo(
      { regnum: REGNUM },
      { auth: auth.otp({ regnum: REGNUM, otp: 1234 }) },
    );

    expect(card.firstname).toBe("Бат");
    expectTypeOf(card.firstname).toEqualTypeOf<string | null>();
    expectTypeOf(card.birthDate).toEqualTypeOf<Date | string | null>();
    const request = server?.requests[0];
    expect(request?.method).toBe("POST");
    expect(request?.url).toBe("/citizen-1.5.0/ws");
    expect(request?.headers.accesstoken).toBe(TEST_TOKEN);
    expect(request?.headers.timestamp).toMatch(/^\d+$/);
    expect(request?.headers.signature).toBeTruthy();
    expect(request?.headers["content-type"]).toBe("text/xml; charset=utf-8");
    expect(request?.body).toContain('xmlns:tns="http://citizen.xyp.gov.mn/"');
    expect(request?.body).toContain("<tns:WS100101_getCitizenIDCardInfo><request><auth><citizen>");
    expect(request?.body).toContain(`<otp>1234</otp><regnum>${REGNUM}</regnum>`);
  });

  it("sends inputs in schema order, whatever order the caller used", async () => {
    const xyp = connect(await fakeXyp(() => ({ body: soapResponse(ID_CARD) })));
    await xyp.citizen.getCitizenIDCardInfo({ regnum: REGNUM, civilId: "1" });
    expect(server?.requests[0]?.body).toContain(`<civilId>1</civilId><regnum>${REGNUM}</regnum>`);
  });

  it("calls by original name and returns raw data", async () => {
    const xyp = connect(await fakeXyp(() => ({ body: soapResponse(ID_CARD) })));
    const data = await xyp.call("WS100101_getCitizenIDCardInfo", { regnum: REGNUM });
    expect(data).toEqual({ firstname: "Бат", regnum: REGNUM });
    await expect(xyp.call("WS999999_doesNotExist")).rejects.toThrow(/Unknown operation/);
  });

  it("reads an unverified namespace from the WSDL once", async () => {
    const xyp = connect(
      await fakeXyp((request) =>
        request.method === "GET"
          ? { body: '<wsdl:definitions targetNamespace="http://insurance.example/">' }
          : { body: soapResponse("<isPensioner>true</isPensioner>") },
      ),
    );

    const first = await xyp.insurance.getCitizenPensionInquiry({ regnum: REGNUM, startYear: 2020 });
    await xyp.insurance.getCitizenPensionInquiry({ regnum: REGNUM });

    expect(first.isPensioner).toBe(true);
    const gets = server?.requests.filter((request) => request.method === "GET") ?? [];
    expect(gets.map((request) => request.url)).toEqual(["/insurance-1.5.0/ws?WSDL"]);
    expect(server?.requests.at(-1)?.body).toContain('xmlns:tns="http://insurance.example/"');
  });

  it("turns result codes into errors that say whose side it is on", async () => {
    const xyp = connect(await fakeXyp(() => ({ body: soapResponse("", 1, "олдсонгүй") })));

    const error = await xyp.citizen.getCitizenIDCardInfo({ regnum: REGNUM }).catch((e) => e);

    expect(error).toBeInstanceOf(NotFoundError);
    expect(error).toMatchObject({
      name: "NotFoundError",
      resultCode: 1,
      resultMessage: "олдсонгүй",
      requestId: "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7",
      origin: "xyp",
    });
  });

  it("maps every documented code to a subclass", () => {
    const documented = [1, 2, 3, 200, 201, 202, 203, 301, 302, 303, 304, 401, 402, 501];
    for (const code of [...documented, 601, 602, 603, 604, 605, 801, 802]) {
      expect(apiError(code, "m", null).constructor, String(code)).not.toBe(XypApiError);
    }
    expect(apiError(203, "m", null)).toBeInstanceOf(AccessDeniedError);
    expect(apiError(9999, "m", null).constructor).toBe(XypApiError);
  });

  it("translates transport failures", async () => {
    const unreachable = new Xyp({
      accessToken: TEST_TOKEN,
      privateKey: TEST_KEY_PEM,
      baseUrl: "http://127.0.0.1:1",
    });
    const refused = await unreachable.citizen.getCitizenIDCardInfo({}).catch((e) => e);
    expect(refused).toBeInstanceOf(XypConnectionError);
    expect(refused).toMatchObject({ origin: "network" });
    expect(String(refused)).toMatch(/VPN/);

    const slow = connect(await fakeXyp(() => ({ body: soapResponse(ID_CARD), delayMs: 500 })), 50);
    await expect(slow.citizen.getCitizenIDCardInfo({})).rejects.toBeInstanceOf(XypTimeoutError);
  });

  it("reports a gateway error page with its status", async () => {
    const xyp = connect(await fakeXyp(() => ({ status: 502, body: "<html>Bad Gateway</html>" })));
    const error = await xyp.citizen.getCitizenIDCardInfo({}).catch((e) => e);
    expect(error).toBeInstanceOf(XypResponseError);
    expect(error).toMatchObject({ statusCode: 502, origin: "xyp" });
  });

  it("keeps the SOAP fault text when XYP answers with HTTP 500", async () => {
    const fault =
      '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>' +
      "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error: " +
      "unexpected element</faultstring></soap:Fault></soap:Body></soap:Envelope>";
    const xyp = connect(await fakeXyp(() => ({ status: 500, body: fault })));
    const error = await xyp.citizen.getCitizenIDCardInfo({}).catch((e) => e);
    expect(error).toBeInstanceOf(XypResponseError);
    expect(error).toMatchObject({ statusCode: 500, origin: "xyp" });
    expect(String(error)).toMatch(/HTTP 500.*Unmarshalling Error: unexpected element/);
  });

  it("rejects a baseUrl without a scheme as a config error", () => {
    const options = { accessToken: TEST_TOKEN, privateKey: TEST_KEY_PEM, baseUrl: "xyp.gov.mn" };
    expect(() => new Xyp(options)).toThrow(XypConfigError);
    expect(() => new Xyp(options)).toThrow(/https:\/\//);
  });

  it("reads credentials from the environment", () => {
    expect(() => new Xyp({ privateKey: TEST_KEY_PEM })).toThrow(XypConfigError);
    expect(() => new Xyp({ accessToken: TEST_TOKEN })).toThrow(/XYP_PRIVATE_KEY/);
  });

  it("does not expose credentials when printed", async () => {
    const { inspect } = await import("node:util");
    const xyp = new Xyp({ accessToken: TEST_TOKEN, privateKey: TEST_KEY_PEM });
    expect(inspect(xyp, { depth: 6 })).not.toContain(TEST_TOKEN);
    expect(JSON.stringify(xyp)).not.toContain(TEST_TOKEN);
  });
});

describe("generated code", () => {
  it("covers every service in the spec", () => {
    const spec: { operationName: string }[] = JSON.parse(
      readFileSync(new URL("../../../spec/services.json", import.meta.url), "utf8"),
    );
    expect(Object.keys(OPERATIONS).sort()).toEqual(spec.map((row) => row.operationName).sort());

    const xyp = new Xyp({ accessToken: TEST_TOKEN, privateKey: TEST_KEY_PEM });
    const methods = Object.values(xyp as unknown as Record<string, Record<string, unknown>>)
      .filter((group) => typeof group === "object")
      .flatMap((group) => Object.keys(group));
    expect(methods).toHaveLength(spec.length);
  });
});

describe("TLS", () => {
  it("defaults to the national CAs only, identical to the Python package's", () => {
    expect(tlsOptions(true)).toEqual({ ca: [...BUNDLED_CA] });
    const pem = (name: string) =>
      readFileSync(new URL(`../../python/src/xyp/certs/${name}`, import.meta.url), "utf8");
    expect([...BUNDLED_CA]).toEqual([pem("MNRCA-2021.pem"), pem("MNICA-2022.pem")]);
  });

  it("supports a custom CA and an explicit opt-out", () => {
    expect(tlsOptions({ ca: "PEM" })).toEqual({ ca: ["PEM"] });
    expect(tlsOptions(false)).toEqual({ rejectUnauthorized: false });
  });
});
