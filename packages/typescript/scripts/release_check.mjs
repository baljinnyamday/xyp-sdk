// Functional check of an INSTALLED xyp-sdk package against a local fake XYP server.
// Run it from a project that has the packed tarball installed (see typescript.yml).
import { createServer } from "node:http";
import { generateKeyPairSync } from "node:crypto";
import { inspect } from "node:util";
import { readFileSync } from "node:fs";
import { Xyp, auth, NotFoundError, XypConnectionError } from "xyp-sdk";

const XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"';
const reply = (inner, code = 0, msg = "ok") =>
  `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:R xmlns:ns2="http://citizen.xyp.gov.mn/"><return><requestId>req-1</requestId><response ${XSI} xsi:type="ns2:citizenData">${inner}</response><resultCode>${code}</resultCode><resultMessage>${msg}</resultMessage></return></ns2:R></soap:Body></soap:Envelope>`;
const seen = [];
const server = createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString();
    if (req.method === "POST") seen.push({ path: req.url, headers: req.headers, body });
    let out = reply("<firstname>Бат</firstname><regnum>РД00000000</regnum><image>AAE=</image>");
    if (req.method === "GET") out = '<wsdl:definitions targetNamespace="http://insurance.xyp.gov.mn/">';
    else if (body.includes("NOTFOUND")) out = reply("", 1, "олдсонгүй");
    else if (body.includes("MISMATCH")) out = reply("<firstname>Бат</firstname><birthDate><x>1</x></birthDate>");
    else if (body.includes("getCitizenPensionInquiry")) out = reply("<isPensioner>true</isPensioner>");
    res.writeHead(200, { "Content-Type": "text/xml; charset=utf-8" }).end(out);
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const baseUrl = `http://127.0.0.1:${server.address().port}`;
const privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey.export({ type: "pkcs8", format: "pem" });
const TOKEN = "SECRET-TOKEN-123";
const results = [];
const check = (name, ok) => { results.push(ok); console.log(ok ? "PASS" : "FAIL", name); };
const warnings = [];
process.on("warning", (w) => warnings.push(w));

const version = JSON.parse(readFileSync("node_modules/xyp-sdk/package.json", "utf8")).version;
console.log(`xyp-sdk ${version} on Node ${process.version}`);
const xyp = new Xyp({ accessToken: TOKEN, privateKey, baseUrl });
const card = await xyp.citizen.getCitizenIDCardInfo({ regnum: "РД00000000" }, { auth: auth.otp({ regnum: "РД00000000", otp: 1234 }) });
const last = () => seen.at(-1);
check("typed call returns decoded data", card.firstname === "Бат" && card.image instanceof Uint8Array && card.image[1] === 1);
check("posts to the right endpoint", last().path === "/citizen-1.5.0/ws");
check("signed headers present", ["accesstoken", "timestamp", "signature"].every((h) => h in last().headers));
check("auth block first, otp sent", last().body.includes("<request><auth><citizen>") && last().body.includes("<otp>1234</otp>"));
check("raw call by original name", (await xyp.call("WS100101_getCitizenIDCardInfo", { regnum: "x" })).firstname === "Бат");
const pension = await xyp.insurance.getCitizenPensionInquiry({ regnum: "x", startYear: 2020 });
check("namespace learned from WSDL for non-citizen endpoint", pension.isPensioner === true && last().body.includes('xmlns:tns="http://insurance.xyp.gov.mn/"'));
const notFound = await xyp.citizen.getCitizenIDCardInfo({ regnum: "NOTFOUND" }).catch((e) => e);
check("resultCode 1 rejects with NotFoundError + request id", notFound instanceof NotFoundError && notFound.resultCode === 1 && notFound.requestId === "req-1" && notFound.origin === "xyp");
const soft = await xyp.citizen.getCitizenIDCardInfo({ regnum: "MISMATCH" });
await new Promise((r) => setImmediate(r));
check("soft validation: call succeeds, bad field null, raw kept, warning emitted", soft.firstname === "Бат" && soft.birthDate === null && soft.xypMismatches[0].path === "birthDate" && warnings.some((w) => w.name === "XypModelMismatchWarning"));
check("mismatches stay out of JSON", !JSON.stringify(soft).includes("xypMismatches"));
const down = await new Xyp({ accessToken: TOKEN, privateKey, baseUrl: "http://127.0.0.1:1" }).citizen.getCitizenIDCardInfo({}).catch((e) => e);
check("unreachable host rejects with XypConnectionError", down instanceof XypConnectionError && down.origin === "network");
check("secrets hidden when the client is printed", !inspect(xyp, { depth: 8 }).includes(TOKEN) && !JSON.stringify(xyp).includes(TOKEN));
xyp.close(); server.closeAllConnections(); server.close();
console.log(`${results.filter(Boolean).length}/${results.length} passed`);
process.exit(results.every(Boolean) ? 0 : 1);
