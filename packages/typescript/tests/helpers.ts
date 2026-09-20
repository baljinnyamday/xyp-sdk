import { generateKeyPairSync } from "node:crypto";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";

export const TEST_TOKEN = "test-access-token";

/** A throwaway key generated for the test run. Real keys never belong in tests. */
export const TEST_KEYS = generateKeyPairSync("rsa", { modulusLength: 2048 });
export const TEST_KEY_PEM = TEST_KEYS.privateKey
  .export({ type: "pkcs8", format: "pem" })
  .toString();

const XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"';

/** A response shaped like the sample on developer.xyp.gov.mn/docs/result-code. */
export function soapResponse(inner: string, code = 0, message = "амжилттай"): string {
  return `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2="http://citizen.xyp.gov.mn/">
      <return>
        <request ${XSI} xsi:type="ns2:citizenRequestData"/>
        <requestId>4fd9aa5f-1984-4b61-b379-13c1bcbd29c7</requestId>
        <response ${XSI} xsi:type="ns2:citizenData">${inner}</response>
        <resultCode>${code}</resultCode>
        <resultMessage>${message}</resultMessage>
      </return>
    </ns2:WS100101_getCitizenIDCardInfoResponse>
  </soap:Body>
</soap:Envelope>`;
}

export interface Recorded {
  readonly method: string;
  readonly url: string;
  readonly headers: IncomingMessage["headers"];
  readonly body: string;
}

export interface FakeXyp {
  readonly baseUrl: string;
  readonly requests: Recorded[];
  close(): Promise<void>;
}

/** A local stand-in for xyp.gov.mn. `reply` decides what each request gets back. */
export async function fakeXyp(
  reply: (request: Recorded) => { status?: number; body: string; delayMs?: number },
): Promise<FakeXyp> {
  const requests: Recorded[] = [];
  const server: Server = createServer((incoming, outgoing) => {
    const chunks: Buffer[] = [];
    incoming.on("data", (chunk: Buffer) => chunks.push(chunk));
    incoming.on("end", () => {
      const recorded: Recorded = {
        method: incoming.method ?? "",
        url: incoming.url ?? "",
        headers: incoming.headers,
        body: Buffer.concat(chunks).toString("utf8"),
      };
      requests.push(recorded);
      const { status = 200, body, delayMs = 0 } = reply(recorded);
      setTimeout(() => {
        outgoing.writeHead(status, { "Content-Type": "text/xml; charset=utf-8" });
        outgoing.end(body);
      }, delayMs);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}
