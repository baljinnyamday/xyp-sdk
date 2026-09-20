import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { type AuthEntity, auth } from "../src/auth.js";
import { XypResponseError } from "../src/errors.js";
import { buildEnvelope, parseResponse } from "../src/soap.js";
import { soapResponse } from "./helpers.js";

interface Fixture {
  operation: string;
  namespace: string;
  params: Record<string, unknown>;
  dateFields: string[];
  auth: null | {
    citizen: { regnum: string; otp: number };
    operator: { regnum: string; fingerprintBase64: string };
  };
  envelope: string;
}

const fixtures: Fixture[] = JSON.parse(
  readFileSync(new URL("./fixtures/envelopes.json", import.meta.url), "utf8"),
);
const NAMESPACE = "http://citizen.xyp.gov.mn/";
const OPERATION = "WS100101_getCitizenIDCardInfo";

describe("buildEnvelope", () => {
  // The fixtures are the Python SDK's output, which is verified against zeep (the SOAP
  // library the known-working XYP clients use). Same input, same bytes.
  it.each(fixtures.map((fixture) => [fixture.operation, fixture] as const))(
    "%s is byte-identical to the zeep-verified envelope",
    (_name, fixture) => {
      const params = Object.fromEntries(
        Object.entries(fixture.params).map(([key, value]) => [
          key,
          fixture.dateFields.includes(key) ? new Date(value as string) : value,
        ]),
      );
      let citizen: AuthEntity | undefined;
      let operator: AuthEntity | undefined;
      if (fixture.auth) {
        citizen = auth.otp(fixture.auth.citizen);
        operator = auth.fingerprint({
          regnum: fixture.auth.operator.regnum,
          fingerprint: Buffer.from(fixture.auth.operator.fingerprintBase64, "base64"),
        });
      }
      expect(buildEnvelope(fixture.operation, fixture.namespace, params, citizen, operator)).toBe(
        fixture.envelope,
      );
    },
  );

  it("covers every operation in the WSDLs", () => {
    expect(fixtures.length).toBeGreaterThan(160);
  });

  it("omits null/undefined, repeats arrays, nests objects", () => {
    const xml = buildEnvelope(OPERATION, NAMESPACE, {
      skipped: null,
      missing: undefined,
      flag: false,
      ids: [1, 2],
      nested: { code: "A", empty: null },
      photo: new Uint8Array([0, 1]),
    });
    expect(xml).toContain(
      "<request><flag>0</flag><ids>1</ids><ids>2</ids><nested><code>A</code></nested><photo>AAE=</photo></request>",
    );
  });

  it("cannot be used to inject XML", () => {
    const xml = buildEnvelope(OPERATION, NAMESPACE, { regnum: "</regnum><admin>true</admin>" });
    expect(xml).toContain("<regnum>&lt;/regnum&gt;&lt;admin&gt;true&lt;/admin&gt;</regnum>");
    expect(xml).not.toContain("<admin>");
  });
});

describe("parseResponse", () => {
  it("reads the documented response shape", () => {
    const result = parseResponse(
      soapResponse(
        '<firstname>Бат &amp; &#1041;</firstname><empty/><gone xsi:nil="true"/>' +
          "<listData><year>2020</year></listData><listData><year>2021</year></listData>",
      ),
    );
    expect(result).toEqual({
      requestId: "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7",
      resultCode: 0,
      message: "амжилттай",
      data: {
        firstname: "Бат & Б",
        empty: null,
        gone: null,
        listData: [{ year: "2020" }, { year: "2021" }],
      },
    });
  });

  it("keeps numeric-looking text as text", () => {
    const { data } = parseResponse(soapResponse("<regnum>0012</regnum><phone>+97699</phone>"));
    expect(data).toEqual({ regnum: "0012", phone: "+97699" });
  });

  it("gives null data for an empty response element", () => {
    expect(parseResponse(soapResponse("", 1, "олдсонгүй")).data).toBeNull();
  });

  it("turns faults, garbage and DTDs into response errors", () => {
    const fault =
      '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>' +
      "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error" +
      "</faultstring></soap:Fault></soap:Body></soap:Envelope>";
    expect(() => parseResponse(fault)).toThrow(/Unmarshalling Error/);
    expect(() => parseResponse("<html>gateway timeout")).toThrow(XypResponseError);
    expect(() => parseResponse("<a/>")).toThrow(/<return>/);
    const bomb = '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>';
    expect(() => parseResponse(bomb)).toThrow(/not valid XML/);
  });
});
