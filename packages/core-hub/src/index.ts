import { loadConfig, loadDotEnv } from "./config.ts";
import { createHubRuntime } from "./server.ts";

loadDotEnv();
const config = loadConfig();
const runtime = createHubRuntime(config);

const bound = await runtime.start();
console.log(
  `[neko-core-hub] listening on http://${bound.host}:${bound.port} (ws path /ws, db=${config.dbPath}, providers=${runtime.modelGateway.listProviders().join(",") || "stub"})`,
);

const shutdown = async () => {
  await runtime.stop();
  process.exit(0);
};

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
