export interface HubConfig {
  host: string;
  port: number;
  dbPath: string;
  hubToken: string | null;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HubConfig {
  const parsedPort = Number(env.PORT ?? "8787");
  return {
    host: env.HOST ?? "127.0.0.1",
    port: Number.isFinite(parsedPort) && parsedPort >= 0 ? parsedPort : 8787,
    dbPath: env.DB_PATH ?? "./data/neko.db",
    hubToken: env.HUB_TOKEN && env.HUB_TOKEN.trim().length > 0 ? env.HUB_TOKEN.trim() : null,
  };
}
