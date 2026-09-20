import { defineConfig } from "tsup";

// Sources are one small module per XYP operation; the published build is a single
// file per format so importing the SDK does not mean resolving 500+ modules.
export default defineConfig({
  entry: ["src/index.ts"],
  format: ["esm", "cjs"],
  dts: true,
  clean: true,
  target: "node18",
});
