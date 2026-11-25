import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

export interface DeepSeekProviderConfig {
  apiKey: string | null;
  baseUrl: string;
  fastModel: string;
  reasoningModel: string;
}

export interface GeminiProviderConfig {
  apiKey: string | null;
  baseUrl: string;
  fastModel: string;
  reasoningModel: string;
  visionModel: string;
}

export interface OpenAIProviderConfig {
  apiKey: string | null;
  baseUrl: string;
  fastModel: string;
  reasoningModel: string;
  visionModel: string;
}

export interface ArkProviderConfig {
  apiKey: string | null;
  baseUrl: string;
  fastModel: string | null;
  visionModel: string | null;
}

export interface OllamaProviderConfig {
  baseUrl: string | null;
  model: string;
}

export interface ModelGatewayConfig {
  requestTimeoutMs: number;
  deepseek: DeepSeekProviderConfig;
  gemini: GeminiProviderConfig;
  openai: OpenAIProviderConfig;
  ark: ArkProviderConfig;
  ollama: OllamaProviderConfig;
}

export interface HubConfig {
  host: string;
  port: number;
  dbPath: string;
  hubToken: string | null;
  providers: ModelGatewayConfig;
}

function cleanEnvValue(value: string | undefined): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Loads key=value pairs from a local .env file without overriding variables
 * already present in targetEnv.
 */
export function loadDotEnv(
  envPath: string = resolve(process.cwd(), ".env"),
  targetEnv: NodeJS.ProcessEnv = process.env,
): string[] {
  if (!existsSync(envPath)) {
    return [];
  }
  const content = readFileSync(envPath, "utf8");
  const loadedKeys: string[] = [];

  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const normalized = line.startsWith("export ") ? line.slice(7).trim() : line;
    const eqIdx = normalized.indexOf("=");
    if (eqIdx <= 0) {
      continue;
    }
    const key = normalized.slice(0, eqIdx).trim();
    let val = normalized.slice(eqIdx + 1).trim();
    if (
      (val.startsWith("\"") && val.endsWith("\"") && val.length >= 2) ||
      (val.startsWith("'") && val.endsWith("'") && val.length >= 2)
    ) {
      val = val.slice(1, -1);
    }
    if (targetEnv[key] === undefined) {
      targetEnv[key] = val;
      loadedKeys.push(key);
    }
  }

  return loadedKeys;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HubConfig {
  const parsedPort = Number(env.PORT ?? "8787");
  const parsedTimeout = Number(env.MODEL_REQUEST_TIMEOUT_MS ?? "30000");

  return {
    host: env.HOST ?? "127.0.0.1",
    port: Number.isFinite(parsedPort) && parsedPort >= 0 ? parsedPort : 8787,
    dbPath: env.DB_PATH ?? "./data/neko.db",
    hubToken: cleanEnvValue(env.HUB_TOKEN),
    providers: {
      requestTimeoutMs:
        Number.isFinite(parsedTimeout) && parsedTimeout > 0 ? parsedTimeout : 30_000,
      deepseek: {
        apiKey: cleanEnvValue(env.DEEPSEEK_API_KEY),
        baseUrl: cleanEnvValue(env.DEEPSEEK_BASE_URL) ?? "https://api.deepseek.com/v1",
        fastModel: cleanEnvValue(env.DEEPSEEK_MODEL) ?? "deepseek-chat",
        reasoningModel: cleanEnvValue(env.DEEPSEEK_REASONING_MODEL) ?? "deepseek-reasoner",
      },
      gemini: {
        apiKey: cleanEnvValue(env.GEMINI_API_KEY),
        baseUrl:
          cleanEnvValue(env.GOOGLE_GEMINI_BASE_URL) ??
          "https://generativelanguage.googleapis.com",
        fastModel: cleanEnvValue(env.GEMINI_MODEL) ?? "gemini-2.5-flash",
        reasoningModel: cleanEnvValue(env.GEMINI_REASONING_MODEL) ?? "gemini-2.5-pro",
        visionModel: cleanEnvValue(env.GEMINI_VISION_MODEL) ?? cleanEnvValue(env.GEMINI_MODEL) ?? "gemini-2.5-flash",
      },
      openai: {
        apiKey: cleanEnvValue(env.OPENAI_API_KEY),
        baseUrl: cleanEnvValue(env.OPENAI_BASE_URL) ?? "https://api.openai.com/v1",
        fastModel: cleanEnvValue(env.OPENAI_MODEL) ?? "gpt-4.1-mini",
        reasoningModel: cleanEnvValue(env.OPENAI_REASONING_MODEL) ?? "o4-mini",
        visionModel: cleanEnvValue(env.OPENAI_VISION_MODEL) ?? cleanEnvValue(env.OPENAI_MODEL) ?? "gpt-4.1-mini",
      },
      ark: {
        apiKey: cleanEnvValue(env.VOLCENGINE_ARK_API_KEY),
        baseUrl:
          cleanEnvValue(env.VOLCENGINE_ARK_BASE_URL) ??
          "https://ark.cn-beijing.volces.com/api/v3",
        fastModel: cleanEnvValue(env.VOLCENGINE_ARK_MODEL),
        visionModel: cleanEnvValue(env.VOLCENGINE_ARK_VISION_MODEL) ?? cleanEnvValue(env.VOLCENGINE_ARK_MODEL),
      },
      ollama: {
        baseUrl: cleanEnvValue(env.OLLAMA_BASE_URL),
        model: cleanEnvValue(env.OLLAMA_MODEL) ?? "qwen2.5:7b",
      },
    },
  };
}
