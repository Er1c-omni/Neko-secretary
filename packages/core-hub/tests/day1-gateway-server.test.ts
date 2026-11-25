import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { createEventEnvelope } from "@neko/protocol";
import { WebSocket } from "ws";
import { loadConfig, loadDotEnv } from "../src/config.ts";
import {
  GeminiProvider,
  ModelGateway,
  OpenAICompatibleProvider,
  parseStructuredAssistantReply,
  type FetchLike,
} from "../src/gateway/model-gateway.ts";
import { createHubRuntime } from "../src/server.ts";

function sseStream(events: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const evt of events) {
        controller.enqueue(encoder.encode(evt));
      }
      controller.close();
    },
  });
}

describe("Week 1 Day 1: Config, ModelGateway & Server Integration", () => {
  it("loads .env without overriding existing environment variables", () => {
    const dir = mkdtempSync(join(tmpdir(), "neko-env-"));
    try {
      const envFile = join(dir, ".env");
      writeFileSync(
        envFile,
        [
          "# comment",
          "HUB_TOKEN=from-dotenv",
          "DEEPSEEK_API_KEY=\"sk-dotenv\"",
          "GEMINI_MODEL=gemini-2.5-flash",
        ].join("\n"),
        "utf8",
      );

      const targetEnv: NodeJS.ProcessEnv = {
        HUB_TOKEN: "already-set",
      };
      const loaded = loadDotEnv(envFile, targetEnv);
      assert.deepEqual(loaded, ["DEEPSEEK_API_KEY", "GEMINI_MODEL"]);
      assert.equal(targetEnv.HUB_TOKEN, "already-set");
      assert.equal(targetEnv.DEEPSEEK_API_KEY, "sk-dotenv");

      const cfg = loadConfig(targetEnv);
      assert.equal(cfg.hubToken, "already-set");
      assert.equal(cfg.providers.deepseek.apiKey, "sk-dotenv");
      assert.equal(cfg.providers.gemini.fastModel, "gemini-2.5-flash");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("streams OpenAI-compatible (DeepSeek) SSE chunks and parses structured JSON", async () => {
    let capturedUrl = "";
    let capturedAuth = "";
    let capturedBody = "";

    const mockFetch: FetchLike = async (input, init) => {
      capturedUrl = String(input);
      const headers = (init?.headers ?? {}) as Record<string, string>;
      capturedAuth = headers.Authorization ?? "";
      capturedBody = String(init?.body ?? "");

      const chunk1 = `data: ${JSON.stringify({ choices: [{ delta: { content: '{"reply":"好呀，' } }] })}\n\n`;
      const chunk2 = `data: ${JSON.stringify({ choices: [{ delta: { content: '每小时提醒你喝水~","intent":"water_reminder"}' } }] })}\n\n`;
      const done = "data: [DONE]\n\n";
      return new Response(sseStream([chunk1, chunk2, done]), {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      });
    };

    const provider = new OpenAICompatibleProvider({
      name: "deepseek",
      baseUrl: "https://api.deepseek.com/v1",
      apiKey: "sk-test",
      models: { fast: "deepseek-chat" },
      fetchImpl: mockFetch,
    });

    const gateway = new ModelGateway();
    gateway.registerProvider(provider);

    const streamed: string[] = [];
    for await (const chunk of gateway.stream({
      tier: "fast",
      systemPrompt: "sys",
      userPrompt: "每小时提醒我喝水",
      responseFormat: "json",
    })) {
      if (chunk.delta) {
        streamed.push(chunk.delta);
      }
    }

    assert.equal(streamed.length, 2);
    assert.equal(capturedUrl, "https://api.deepseek.com/v1/chat/completions");
    assert.equal(capturedAuth, "Bearer sk-test");
    assert.equal(JSON.parse(capturedBody).response_format.type, "json_object");

    const { response, structured } = await gateway.completeStructured({
      tier: "fast",
      systemPrompt: "sys",
      userPrompt: "每小时提醒我喝水",
    });
    assert.equal(response.stubbed, false);
    assert.equal(structured.intent, "water_reminder");
    assert.match(structured.reply, /喝水/);
  });

  it("streams Gemini SSE responses and supports vision inlineData", async () => {
    let capturedUrl = "";
    let capturedKey = "";
    let capturedBody = "";

    const mockFetch: FetchLike = async (input, init) => {
      capturedUrl = String(input);
      const headers = (init?.headers ?? {}) as Record<string, string>;
      capturedKey = headers["x-goog-api-key"] ?? "";
      capturedBody = String(init?.body ?? "");

      const sse1 = `data: ${JSON.stringify({
        candidates: [{ content: { parts: [{ text: "```json\n{\"reply\":\"收到面试通知啦\"," }] } }],
      })}\n\n`;
      const sse2 = `data: ${JSON.stringify({
        candidates: [{ content: { parts: [{ text: "\"intent\":\"deadline_alert\"}\n```" }] } }],
      })}\n\n`;

      return new Response(sseStream([sse1, sse2]), {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      });
    };

    const gemini = new GeminiProvider({
      apiKey: "gm-test-key",
      baseUrl: "https://example.proxy.invalid",
      models: { fast: "gemini-2.5-flash", vision: "gemini-2.5-flash" },
      fetchImpl: mockFetch,
    });

    const gateway = new ModelGateway();
    gateway.registerProvider(gemini);

    const { structured } = await gateway.completeStructured({
      tier: "vision",
      systemPrompt: "extract",
      userPrompt: "parse screenshot",
      imageBase64: "AAAA",
      imageMimeType: "image/png",
    });

    assert.equal(
      capturedUrl,
      "https://example.proxy.invalid/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
    );
    assert.equal(capturedKey, "gm-test-key");
    const parsedReq = JSON.parse(capturedBody);
    assert.equal(parsedReq.generationConfig.responseMimeType, "application/json");
    assert.equal(parsedReq.contents[0].parts[1].inlineData.data, "AAAA");
    assert.equal(structured.intent, "deadline_alert");
    assert.equal(structured.reply, "收到面试通知啦");
  });

  it("falls back safely when parseStructuredAssistantReply receives non-JSON text", () => {
    const parsed = parseStructuredAssistantReply("普通文本回复");
    assert.equal(parsed.reply, "普通文本回复");
    assert.equal(parsed.intent, "generic_reply");
  });

  it("serves /health, enforces WS auth, handles hub.ping, POST /api/chat, and WS chat.send", async () => {
    const gateway = new ModelGateway();
    gateway.registerProvider({
      name: "mock-llm",
      supports: () => true,
      async *stream() {
        yield {
          provider: "mock-llm",
          model: "mock-fast",
          delta: JSON.stringify({
            reply: "收到，已经帮你记下喝水提醒啦~",
            intent: "water_reminder",
            prospectiveCandidate: { kind: "habit", title: "每小时喝水" },
          }),
          done: false,
        };
        yield { provider: "mock-llm", model: "mock-fast", delta: "", done: true };
      },
      async complete() {
        const text = JSON.stringify({
          reply: "收到，已经帮你记下喝水提醒啦~",
          intent: "water_reminder",
          prospectiveCandidate: { kind: "habit", title: "每小时喝水" },
        });
        return {
          provider: "mock-llm",
          model: "mock-fast",
          text,
          stubbed: false,
          structured: parseStructuredAssistantReply(text),
        };
      },
    });

    const cfg = loadConfig({
      HOST: "127.0.0.1",
      PORT: "0",
      DB_PATH: ":memory:",
      HUB_TOKEN: "mesh-secret",
    });

    const runtime = createHubRuntime(cfg, { modelGateway: gateway });
    const bound = await runtime.start();
    const baseUrl = `http://${bound.host}:${bound.port}`;
    const wsUrl = `ws://${bound.host}:${bound.port}/ws`;

    try {
      // 1. /health
      const healthRes = await fetch(`${baseUrl}/health`);
      assert.equal(healthRes.status, 200);
      const healthJson = (await healthRes.json()) as {
        status: string;
        service: string;
        providers: string[];
      };
      assert.equal(healthJson.status, "ok");
      assert.equal(healthJson.service, "neko-core-hub");
      assert.deepEqual(healthJson.providers, ["mock-llm"]);

      // 2. Unauthorized WS rejected
      const unauthCode = await new Promise<number>((resolve) => {
        const badWs = new WebSocket(wsUrl);
        badWs.on("close", (code) => resolve(code));
      });
      assert.equal(unauthCode, 4001);

      // 3. Authorized WS hub.ping + chat.send + event.ingest
      const ws = new WebSocket(wsUrl, {
        headers: { Authorization: "Bearer mesh-secret" },
      });
      await new Promise<void>((resolve, reject) => {
        ws.once("open", () => resolve());
        ws.once("error", reject);
      });

      const messages: Array<Record<string, unknown>> = [];
      ws.on("message", (raw) => {
        messages.push(JSON.parse(raw.toString("utf8")) as Record<string, unknown>);
      });

      const rpcCall = (id: string, method: string, params: unknown) =>
        new Promise<Record<string, unknown>>((resolve) => {
          const check = () => {
            const found = messages.find((m) => m.id === id);
            if (found) {
              resolve(found);
            } else {
              setTimeout(check, 10);
            }
          };
          ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
          check();
        });

      const pingRes = await rpcCall("ping-1", "hub.ping", {});
      assert.equal((pingRes.result as { status: string }).status, "ok");

      const chatRes = await rpcCall("chat-1", "chat.send", {
        text: "每小时提醒我喝水",
        stream: true,
      });
      const chatResult = chatRes.result as {
        structured: { intent: string; reply: string };
        directive: { state: string };
        model: { provider: string };
      };
      assert.equal(chatResult.structured.intent, "water_reminder");
      assert.equal(chatResult.directive.state, "LIFE_WATER");
      assert.equal(chatResult.model.provider, "mock-llm");

      // Should have emitted chat.delta and directive.push notifications
      assert.ok(messages.some((m) => m.method === "chat.delta"));
      assert.ok(messages.some((m) => m.method === "directive.push"));

      // event.ingest wake_agent should push directive.push asynchronously
      const beforeDirectives = messages.filter((m) => m.method === "directive.push").length;
      const ingestRes = await rpcCall(
        "ing-1",
        "event.ingest",
        createEventEnvelope({
          idempotencyKey: "ws:test:1",
          type: "user.text_message",
          source: "android",
          payload: { text: "提醒我喝水" },
        }),
      );
      assert.equal((ingestRes.result as { route: string }).route, "wake_agent");

      await new Promise<void>((resolve) => {
        const waitDirective = () => {
          const count = messages.filter((m) => m.method === "directive.push").length;
          if (count > beforeDirectives) {
            resolve();
          } else {
            setTimeout(waitDirective, 10);
          }
        };
        waitDirective();
      });

      ws.close();

      // 4. POST /api/chat
      const unauthChat = await fetch(`${baseUrl}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: "hello" }),
      });
      assert.equal(unauthChat.status, 401);

      const authChat = await fetch(`${baseUrl}/api/chat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mesh-secret",
        },
        body: JSON.stringify({ text: "帮我记一下喝水习惯" }),
      });
      assert.equal(authChat.status, 200);
      const authChatJson = (await authChat.json()) as {
        ok: boolean;
        structured: { intent: string };
        directive: { state: string };
      };
      assert.equal(authChatJson.ok, true);
      assert.equal(authChatJson.structured.intent, "water_reminder");
      assert.equal(authChatJson.directive.state, "LIFE_WATER");

      // Prospective item persisted from structured reply
      assert.ok(runtime.memory.listProspectiveItems("active").length >= 1);
    } finally {
      await runtime.stop();
    }
  });
});
