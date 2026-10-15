import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "SCHEDULA_");
  const target = env.SCHEDULA_API_URL || "http://localhost:8080";
  return {
    plugins: [react()],
    server: { proxy: { "/api": target, "/webhooks": target } },
  };
});
