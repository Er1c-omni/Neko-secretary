import type { ProspectiveKind, MemoryCategory } from "@neko/protocol";
import type { HubConfig } from "../config.ts";
import type { PersonaIntent } from "../persona/renderer.ts";

export type ModelTier = "fast" | "reasoning" | "vision" | "local";
export type ModelResponseFormat = "text" | "json";
export type FetchLike = typeof fetch;

export interface ModelCompletionRequest {
  tier: ModelTier;
  systemPrompt: string;
  userPrompt: string;
  imageBase64?: string;
  imageMimeType?: string;
  responseFormat?: ModelResponseFormat;
  temperature?: number;
  maxTokens?: number;
  signal?: AbortSignal;
}

export interface ModelStreamChunk {
  provider: string;
  model: string;
  delta: string;
  done: boolean;
}

export interface StructuredAssistantReply {
  reply: string;
  intent: PersonaIntent;
  actionHint?: string | null;
  memoryCandidate?: {
    category: MemoryCategory;
    title?: string | null;
    content: string;
    tags?: string[];
  } | null;
  prospectiveCandidate?: {
    kind: ProspectiveKind;
    title: string;
    dueAt?: number | null;
  } | null;
}

export interface ModelCompletionResponse {
  provider: string;
  model: string;
  text: string;
  stubbed: boolean;
  structured?: StructuredAssistantReply | null;
}

export interface ModelProvider {
  readonly name: string;
  supports(tier: ModelTier): boolean;
  complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse>;
  stream?(req: ModelCompletionRequest): AsyncIterable<ModelStreamChunk>;
}

const VALID_INTENTS = new Set<PersonaIntent>([
  "water_reminder",
  "sedentary_alert",
  "deadline_alert",
  "worker_approval",
  "morning_greeting",
  "generic_reply",
]);

function stripCodeFences(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("```")) {
    return trimmed;
  }
  return trimmed
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();
}

export function parseStructuredAssistantReply(rawText: string): StructuredAssistantReply {
  const cleaned = stripCodeFences(rawText);
  try {
    const parsed = JSON.parse(cleaned) as Record<string, unknown>;
    const reply =
      typeof parsed.reply === "string" && parsed.reply.trim().length > 0
        ? parsed.reply.trim()
        : rawText.trim();
    const rawIntent = typeof parsed.intent === "string" ? parsed.intent : "generic_reply";
    const intent = VALID_INTENTS.has(rawIntent as PersonaIntent)
      ? (rawIntent as PersonaIntent)
      : "generic_reply";

    const result: StructuredAssistantReply = {
      reply: reply.length > 0 ? reply : "收到，正在处理中。",
      intent,
      actionHint: typeof parsed.actionHint === "string" ? parsed.actionHint : null,
      memoryCandidate: null,
      prospectiveCandidate: null,
    };

    if (parsed.memoryCandidate && typeof parsed.memoryCandidate === "object") {
      const mem = parsed.memoryCandidate as Record<string, unknown>;
      if (typeof mem.content === "string" && mem.content.trim().length > 0) {
        const cat = mem.category;
        const category: MemoryCategory =
          cat === "profile" || cat === "episodic" || cat === "semantic" || cat === "procedural"
            ? cat
            : "episodic";
        result.memoryCandidate = {
          category,
          title: typeof mem.title === "string" ? mem.title : null,
          content: mem.content.trim(),
          tags: Array.isArray(mem.tags)
            ? mem.tags.filter((t): t is string => typeof t === "string")
            : [],
        };
      }
    }

    if (parsed.prospectiveCandidate && typeof parsed.prospectiveCandidate === "object") {
      const item = parsed.prospectiveCandidate as Record<string, unknown>;
      if (typeof item.title === "string" && item.title.trim().length > 0) {
        const k = item.kind;
        const kind: ProspectiveKind =
          k === "goal" ||
          k === "deadline" ||
          k === "commitment" ||
          k === "subscription" ||
          k === "inventory" ||
          k === "habit"
            ? k
            : "goal";
        result.prospectiveCandidate = {
          kind,
          title: item.title.trim(),
          dueAt: typeof item.dueAt === "number" && Number.isFinite(item.dueAt) ? item.dueAt : null,
        };
      }
    }

    return result;
  } catch {
    return {
      reply: rawText.trim() || "收到，正在处理中。",
      intent: "generic_reply",
      actionHint: null,
      memoryCandidate: null,
      prospectiveCandidate: null,
    };
  }
}

async function* iterateSsePayloads(
  body: ReadableStream<Uint8Array>,
): AsyncIterable<string> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let dataLines: string[] = [];

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });

      let newlineIdx = buffer.indexOf("\n");
      while (newlineIdx !== -1) {
        const line = buffer.slice(0, newlineIdx).replace(/\r$/, "");
        buffer = buffer.slice(newlineIdx + 1);

        if (line === "") {
          if (dataLines.length > 0) {
            const payload = dataLines.join("\n").trim();
            dataLines = [];
            if (payload === "[DONE]") {
              return;
            }
            if (payload.length > 0) {
              yield payload;
            }
          }
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }

        newlineIdx = buffer.indexOf("\n");
      }
    }

    buffer += decoder.decode();
    if (buffer.trim().length > 0) {
      for (const tailLine of buffer.split(/\r?\n/)) {
        if (tailLine.startsWith("data:")) {
          dataLines.push(tailLine.slice(5).trimStart());
        }
      }
    }
    if (dataLines.length > 0) {
      const payload = dataLines.join("\n").trim();
      if (payload !== "[DONE]" && payload.length > 0) {
        yield payload;
      }
    }
  } finally {
    reader.releaseLock();
  }
}

function buildAbortSignal(timeoutMs: number, externalSignal?: AbortSignal): {
  signal: AbortSignal;
  cleanup: () => void;
} {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error(`Model HTTP timeout after ${timeoutMs}ms`)), timeoutMs);

  const onAbort = () => controller.abort(externalSignal?.reason);
  if (externalSignal) {
    if (externalSignal.aborted) {
      controller.abort(externalSignal.reason);
    } else {
      externalSignal.addEventListener("abort", onAbort, { once: true });
    }
  }

  return {
    signal: controller.signal,
    cleanup: () => {
      clearTimeout(timer);
      externalSignal?.removeEventListener("abort", onAbort);
    },
  };
}

export interface OpenAICompatibleProviderOptions {
  name: string;
  baseUrl: string;
  apiKey?: string | null;
  models: Partial<Record<ModelTier, string>>;
  timeoutMs?: number;
  fetchImpl?: FetchLike;
}

export class OpenAICompatibleProvider implements ModelProvider {
  readonly name: string;
  private readonly baseUrl: string;
  private readonly apiKey: string | null;
  private readonly models: Partial<Record<ModelTier, string>>;
  private readonly timeoutMs: number;
  private readonly fetchImpl: FetchLike;

  constructor(options: OpenAICompatibleProviderOptions) {
    this.name = options.name;
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.apiKey = options.apiKey ?? null;
    this.models = options.models;
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  supports(tier: ModelTier): boolean {
    return typeof this.models[tier] === "string" && this.models[tier]!.length > 0;
  }

  async complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse> {
    const model = this.resolveModel(req.tier);
    let text = "";
    for await (const chunk of this.stream(req)) {
      text += chunk.delta;
    }
    return {
      provider: this.name,
      model,
      text,
      stubbed: false,
      structured: req.responseFormat === "json" ? parseStructuredAssistantReply(text) : null,
    };
  }

  async *stream(req: ModelCompletionRequest): AsyncIterable<ModelStreamChunk> {
    const model = this.resolveModel(req.tier);
    const endpoint = this.baseUrl.endsWith("/chat/completions")
      ? this.baseUrl
      : `${this.baseUrl}/chat/completions`;

    const userContent: unknown = req.imageBase64
      ? [
          { type: "text", text: req.userPrompt },
          {
            type: "image_url",
            image_url: {
              url: `data:${req.imageMimeType ?? "image/png"};base64,${req.imageBase64}`,
            },
          },
        ]
      : req.userPrompt;

    const body: Record<string, unknown> = {
      model,
      stream: true,
      messages: [
        ...(req.systemPrompt ? [{ role: "system", content: req.systemPrompt }] : []),
        { role: "user", content: userContent },
      ],
    };

    if (req.responseFormat === "json") {
      body.response_format = { type: "json_object" };
    }
    if (req.temperature !== undefined) {
      body.temperature = req.temperature;
    }
    if (req.maxTokens !== undefined) {
      body.max_tokens = req.maxTokens;
    }

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (this.apiKey) {
      headers.Authorization = `Bearer ${this.apiKey}`;
    }

    const { signal, cleanup } = buildAbortSignal(this.timeoutMs, req.signal);
    try {
      const response = await this.fetchImpl(endpoint, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        signal,
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`[${this.name}] HTTP ${response.status}: ${errText.slice(0, 300)}`);
      }

      const contentType = response.headers.get("content-type") ?? "";
      if (!response.body || contentType.includes("application/json")) {
        const json = (await response.json()) as {
          choices?: Array<{ message?: { content?: string } }>;
        };
        const content = json.choices?.[0]?.message?.content ?? "";
        if (content.length > 0) {
          yield { provider: this.name, model, delta: content, done: false };
        }
        yield { provider: this.name, model, delta: "", done: true };
        return;
      }

      for await (const payload of iterateSsePayloads(response.body)) {
        const parsed = JSON.parse(payload) as {
          choices?: Array<{ delta?: { content?: string } }>;
        };
        const delta = parsed.choices?.[0]?.delta?.content;
        if (typeof delta === "string" && delta.length > 0) {
          yield { provider: this.name, model, delta, done: false };
        }
      }
      yield { provider: this.name, model, delta: "", done: true };
    } finally {
      cleanup();
    }
  }

  private resolveModel(tier: ModelTier): string {
    const model = this.models[tier];
    if (!model) {
      throw new Error(`[${this.name}] Unsupported tier: ${tier}`);
    }
    return model;
  }
}

export interface GeminiProviderOptions {
  apiKey: string;
  baseUrl?: string;
  models: Partial<Record<ModelTier, string>>;
  timeoutMs?: number;
  fetchImpl?: FetchLike;
}

export class GeminiProvider implements ModelProvider {
  readonly name = "gemini";
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly models: Partial<Record<ModelTier, string>>;
  private readonly timeoutMs: number;
  private readonly fetchImpl: FetchLike;

  constructor(options: GeminiProviderOptions) {
    this.apiKey = options.apiKey;
    this.baseUrl = (options.baseUrl ?? "https://generativelanguage.googleapis.com").replace(/\/+$/, "");
    this.models = options.models;
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  supports(tier: ModelTier): boolean {
    return typeof this.models[tier] === "string" && this.models[tier]!.length > 0;
  }

  async complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse> {
    const model = this.resolveModel(req.tier);
    let text = "";
    for await (const chunk of this.stream(req)) {
      text += chunk.delta;
    }
    return {
      provider: this.name,
      model,
      text,
      stubbed: false,
      structured: req.responseFormat === "json" ? parseStructuredAssistantReply(text) : null,
    };
  }

  async *stream(req: ModelCompletionRequest): AsyncIterable<ModelStreamChunk> {
    const model = this.resolveModel(req.tier);
    const versionPrefix = this.baseUrl.endsWith("/v1beta") ? "" : "/v1beta";
    const endpoint = `${this.baseUrl}${versionPrefix}/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`;

    const parts: Array<Record<string, unknown>> = [{ text: req.userPrompt }];
    if (req.imageBase64) {
      parts.push({
        inlineData: {
          mimeType: req.imageMimeType ?? "image/png",
          data: req.imageBase64,
        },
      });
    }

    const generationConfig: Record<string, unknown> = {};
    if (req.responseFormat === "json") {
      generationConfig.responseMimeType = "application/json";
    }
    if (req.temperature !== undefined) {
      generationConfig.temperature = req.temperature;
    }
    if (req.maxTokens !== undefined) {
      generationConfig.maxOutputTokens = req.maxTokens;
    }

    const body: Record<string, unknown> = {
      contents: [{ role: "user", parts }],
    };
    if (req.systemPrompt) {
      body.systemInstruction = { parts: [{ text: req.systemPrompt }] };
    }
    if (Object.keys(generationConfig).length > 0) {
      body.generationConfig = generationConfig;
    }

    const { signal, cleanup } = buildAbortSignal(this.timeoutMs, req.signal);
    try {
      const response = await this.fetchImpl(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": this.apiKey,
        },
        body: JSON.stringify(body),
        signal,
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`[gemini] HTTP ${response.status}: ${errText.slice(0, 300)}`);
      }

      const contentType = response.headers.get("content-type") ?? "";
      if (!response.body || contentType.includes("application/json")) {
        const json = (await response.json()) as unknown;
        const items = Array.isArray(json) ? json : [json];
        for (const item of items) {
          const text = extractGeminiCandidateText(item);
          if (text.length > 0) {
            yield { provider: this.name, model, delta: text, done: false };
          }
        }
        yield { provider: this.name, model, delta: "", done: true };
        return;
      }

      for await (const payload of iterateSsePayloads(response.body)) {
        const parsed = JSON.parse(payload) as unknown;
        const text = extractGeminiCandidateText(parsed);
        if (text.length > 0) {
          yield { provider: this.name, model, delta: text, done: false };
        }
      }
      yield { provider: this.name, model, delta: "", done: true };
    } finally {
      cleanup();
    }
  }

  private resolveModel(tier: ModelTier): string {
    const model = this.models[tier];
    if (!model) {
      throw new Error(`[gemini] Unsupported tier: ${tier}`);
    }
    return model;
  }
}

function extractGeminiCandidateText(payload: unknown): string {
  if (!payload || typeof payload !== "object") {
    return "";
  }
  const candidates = (payload as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> }).candidates;
  const parts = candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) {
    return "";
  }
  return parts
    .map((part) => (typeof part.text === "string" ? part.text : ""))
    .join("");
}

export class ModelGateway {
  private readonly providers: ModelProvider[] = [];

  registerProvider(provider: ModelProvider): void {
    this.providers.push(provider);
  }

  listProviders(): string[] {
    return this.providers.map((p) => p.name);
  }

  async complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse> {
    const provider = this.providers.find((item) => item.supports(req.tier));
    if (!provider) {
      return {
        provider: "stub",
        model: `unconfigured-${req.tier}`,
        text: "[ModelGateway] No provider configured yet; add API keys in .env to enable live inference.",
        stubbed: true,
        structured:
          req.responseFormat === "json"
            ? {
                reply:
                  "[ModelGateway] No provider configured yet; add API keys in .env to enable live inference.",
                intent: "generic_reply",
                actionHint: null,
                memoryCandidate: null,
                prospectiveCandidate: null,
              }
            : null,
      };
    }
    const response = await provider.complete(req);
    if (req.responseFormat === "json" && !response.structured) {
      response.structured = parseStructuredAssistantReply(response.text);
    }
    return response;
  }

  async *stream(req: ModelCompletionRequest): AsyncIterable<ModelStreamChunk> {
    const provider = this.providers.find((item) => item.supports(req.tier));
    if (!provider) {
      yield {
        provider: "stub",
        model: `unconfigured-${req.tier}`,
        delta: "[ModelGateway] No provider configured yet; add API keys in .env to enable live inference.",
        done: false,
      };
      yield {
        provider: "stub",
        model: `unconfigured-${req.tier}`,
        delta: "",
        done: true,
      };
      return;
    }

    if (provider.stream) {
      yield* provider.stream(req);
      return;
    }

    const res = await provider.complete(req);
    if (res.text.length > 0) {
      yield { provider: res.provider, model: res.model, delta: res.text, done: false };
    }
    yield { provider: res.provider, model: res.model, delta: "", done: true };
  }

  async completeStructured(req: Omit<ModelCompletionRequest, "responseFormat">): Promise<{
    response: ModelCompletionResponse;
    structured: StructuredAssistantReply;
  }> {
    const response = await this.complete({
      ...req,
      responseFormat: "json",
    });
    const structured = response.structured ?? parseStructuredAssistantReply(response.text);
    return { response, structured };
  }
}

export function registerConfiguredProviders(
  gateway: ModelGateway,
  config: HubConfig,
  fetchImpl?: FetchLike,
): void {
  const { providers } = config;
  const timeoutMs = providers.requestTimeoutMs;

  if (providers.deepseek.apiKey) {
    gateway.registerProvider(
      new OpenAICompatibleProvider({
        name: "deepseek",
        baseUrl: providers.deepseek.baseUrl,
        apiKey: providers.deepseek.apiKey,
        models: {
          fast: providers.deepseek.fastModel,
          reasoning: providers.deepseek.reasoningModel,
        },
        timeoutMs,
        fetchImpl,
      }),
    );
  }

  if (providers.gemini.apiKey) {
    gateway.registerProvider(
      new GeminiProvider({
        apiKey: providers.gemini.apiKey,
        baseUrl: providers.gemini.baseUrl,
        models: {
          fast: providers.gemini.fastModel,
          reasoning: providers.gemini.reasoningModel,
          vision: providers.gemini.visionModel,
        },
        timeoutMs,
        fetchImpl,
      }),
    );
  }

  if (providers.openai.apiKey) {
    gateway.registerProvider(
      new OpenAICompatibleProvider({
        name: "openai",
        baseUrl: providers.openai.baseUrl,
        apiKey: providers.openai.apiKey,
        models: {
          fast: providers.openai.fastModel,
          reasoning: providers.openai.reasoningModel,
          vision: providers.openai.visionModel,
        },
        timeoutMs,
        fetchImpl,
      }),
    );
  }

  if (providers.ark.apiKey && providers.ark.fastModel) {
    gateway.registerProvider(
      new OpenAICompatibleProvider({
        name: "volcengine-ark",
        baseUrl: providers.ark.baseUrl,
        apiKey: providers.ark.apiKey,
        models: {
          fast: providers.ark.fastModel,
          vision: providers.ark.visionModel ?? undefined,
        },
        timeoutMs,
        fetchImpl,
      }),
    );
  }

  if (providers.ollama.baseUrl) {
    const base = providers.ollama.baseUrl.replace(/\/+$/, "");
    gateway.registerProvider(
      new OpenAICompatibleProvider({
        name: "ollama",
        baseUrl: base.endsWith("/v1") ? base : `${base}/v1`,
        models: {
          local: providers.ollama.model,
        },
        timeoutMs,
        fetchImpl,
      }),
    );
  }
}
