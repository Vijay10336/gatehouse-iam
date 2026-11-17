import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:18100",
      "/login": "http://localhost:18100",
      "/logout": "http://localhost:18100",
      "/oauth2": "http://localhost:18100",
      "/.well-known": "http://localhost:18100",
    },
  },
});
