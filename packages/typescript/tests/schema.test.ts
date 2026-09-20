import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { decodeResponse, MISMATCH_WARNING, type Schema } from "../src/schema.js";

const SCHEMA: Schema = {
  firstName: "string",
  age: "int",
  active: "bool",
  born: "date",
  photo: "bytes",
  amount: "decimal",
  ratio: "float",
  listData: { list: { object: { year: "int" } } },
  address: { object: { city: "string" } },
};

interface Sample {
  firstName: string | null;
  age: number | null;
  active: boolean | null;
  born: Date | string | null;
  photo: Uint8Array | null;
  amount: string | null;
  ratio: number | null;
  listData: readonly { year: number | null }[] | null;
  address: { city: string | null } | null;
}

const warnings: Error[] = [];
const onWarning = (warning: Error) => warnings.push(warning);
beforeEach(() => {
  warnings.length = 0;
  process.on("warning", onWarning);
});
afterEach(() => {
  process.off("warning", onWarning);
});
const nextTick = () => new Promise((resolve) => setImmediate(resolve));

describe("decodeResponse", () => {
  it("coerces text by field type and fills missing fields with null", () => {
    const sample = decodeResponse<Sample>("Sample", SCHEMA, {
      firstName: "Бат",
      age: "34",
      active: "true",
      born: "1990-05-01T00:00:00+08:00",
      photo: "AAE=",
      amount: "1234567890123456789.50",
      ratio: "0.5",
    });

    expect(sample.firstName).toBe("Бат");
    expect(sample.age).toBe(34);
    expect(sample.active).toBe(true);
    expect(sample.born).toEqual(new Date("1990-05-01T00:00:00+08:00"));
    expect(sample.photo).toEqual(new Uint8Array([0, 1]));
    expect(sample.amount).toBe("1234567890123456789.50");
    expect(sample.ratio).toBe(0.5);
    expect(sample.listData).toBeNull();
    expect(sample.xypMismatches).toEqual([]);
  });

  it.each([
    ["2024-01-31", new Date("2024-01-31")],
    ["2020", "2020"], // a year, not a timestamp
    ["31.01.2024", "31.01.2024"], // undocumented formats stay text instead of failing
  ])("reads the date %s leniently", (text, expected) => {
    expect(decodeResponse<Sample>("Sample", SCHEMA, { born: text }).born).toEqual(expected);
  });

  it("treats a single item as a one-item list", () => {
    const single = decodeResponse<Sample>("Sample", SCHEMA, { listData: { year: "2020" } });
    expect(single.listData).toEqual([{ year: 2020 }]);
  });

  it("keeps unknown fields and a missing response", () => {
    const extra = decodeResponse<Sample & { brandNew?: string }>("Sample", SCHEMA, {
      brandNew: "x",
    });
    expect(extra.brandNew).toBe("x");
    expect(decodeResponse<Sample>("Sample", SCHEMA, null).firstName).toBeNull();
  });

  it("never fails the call when a field does not fit, and says whose fault it is", async () => {
    const sample = decodeResponse<Sample>("Sample", SCHEMA, {
      firstName: "Бат",
      age: "not-a-number-РД00000000",
      listData: [{ year: "2020" }, { year: "MMXXI" }],
      address: "just text",
    });
    await nextTick();

    expect(sample.firstName).toBe("Бат");
    expect(sample.age).toBeNull();
    expect(sample.listData).toEqual([{ year: 2020 }, { year: null }]);
    expect(sample.address).toBeNull();
    expect(sample.xypMismatches.map((m) => [m.path, m.value])).toEqual([
      ["age", "not-a-number-РД00000000"],
      ["listData[1].year", "MMXXI"],
      ["address", "just text"],
    ]);

    expect(warnings).toHaveLength(1);
    expect(warnings[0]?.name).toBe(MISMATCH_WARNING);
    expect(warnings[0]?.message).toMatch(/age .*listData\[1\]\.year.*not an error from XYP/s);
    // citizen data must not reach logs: not in the warning, not in JSON, not when printed
    expect(warnings[0]?.message).not.toContain("РД00000000");
    expect(JSON.stringify(sample)).not.toContain("xypMismatches");
    expect(JSON.stringify(sample.xypMismatches)).not.toContain("РД00000000");
  });

  it("reports a response that is not an object", () => {
    const sample = decodeResponse<Sample>("Sample", SCHEMA, "just text");
    expect(sample.firstName).toBeNull();
    expect(sample.xypMismatches[0]).toMatchObject({ path: "", problem: "expected an object" });
  });

  it("rejects integers JavaScript cannot represent exactly", () => {
    const sample = decodeResponse<Sample>("Sample", SCHEMA, { age: "9007199254740993" });
    expect(sample.age).toBeNull();
    expect(sample.xypMismatches[0]?.value).toBe("9007199254740993");
  });
});
