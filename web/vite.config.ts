import { defineConfig } from "vitest/config";

// The page is served by the sim host at /room/ (relative assets), or by `make web-dev` with the
// stream proxied to a running host.
export default defineConfig({
  base: "./",
  build: { target: "es2022", chunkSizeWarningLimit: 1500 },
  server: {
    fs: { allow: [".."] },
    proxy: { "/v1": { target: process.env.LILGUY_HOST ?? "http://127.0.0.1:8790", ws: true } },
  },
  test: { include: ["src/**/*.test.ts"] },
});
