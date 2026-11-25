import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type {
  AssistantMode,
  EventEnvelope,
  RpcRequest,
  RpcResponse,
  TodayScript,
  WorkerHeartbeatPayload,
  WorkerNodeInfo,
} from "@neko/protocol";
import { WebSocketServer, type WebSocket } from "ws";
import { EventBus } from "./bus/event-bus.ts";
import type { HubConfig } from "./config.ts";
import { MemoryRepository } from "./memory/repository.ts";
import { OrchestratorRegistry } from "./orchestrator/registry.ts";
import { PersonaRenderer } from "./persona/renderer.ts";
import { ProactiveEngine } from "./proactive/engine.ts";
import { SafetyGate } from "./policy/safety-gate.ts";
import { HybridScheduler } from "./scheduler/scheduler.ts";
import { HubDatabase } from "./storage/database.ts";

export interface HubRuntime {
  db: HubDatabase;
  eventBus: EventBus;
  memory: MemoryRepository;
  proactive: ProactiveEngine;
  persona: PersonaRenderer;
  safetyGate: SafetyGate;
  scheduler: HybridScheduler;
  orchestrator: OrchestratorRegistry;
  start(): Promise<{ host: string; port: number }>;
  stop(): Promise<void>;
}

export function createHubRuntime(config: HubConfig): HubRuntime {
  const db = new HubDatabase(config.dbPath);
  db.migrate();

  const eventBus = new EventBus(db.db);
  const memory = new MemoryRepository(db.db);
  const proactive = new ProactiveEngine();
  const persona = new PersonaRenderer();
  const safetyGate = new SafetyGate();
  const scheduler = new HybridScheduler(db.db);
  const orchestrator = new OrchestratorRegistry(db.db);

  const httpServer = createServer((req: IncomingMessage, res: ServerResponse) => {
    if (req.method === "GET" && req.url === "/health") {
      const payload = JSON.stringify({
        status: "ok",
        service: "neko-core-hub",
        workers: orchestrator.listWorkers().length,
        ts: Date.now(),
      });
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(payload);
      return;
    }
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not_found" }));
  });

  const wss = new WebSocketServer({ server: httpServer, path: "/ws" });
  const clients = new Set<WebSocket>();

  wss.on("connection", (ws, req) => {
    if (config.hubToken) {
      const auth = req.headers.authorization;
      if (auth !== `Bearer ${config.hubToken}`) {
        ws.close(4001, "unauthorized");
        return;
      }
    }

    clients.add(ws);
    ws.on("close", () => clients.delete(ws));

    ws.on("message", (raw) => {
      let parsed: RpcRequest;
      try {
        parsed = JSON.parse(raw.toString("utf8")) as RpcRequest;
      } catch {
        const errResp: RpcResponse = {
          jsonrpc: "2.0",
          id: 0,
          error: { code: -32700, message: "Parse error" },
        };
        ws.send(JSON.stringify(errResp));
        return;
      }

      const response = handleRpc(parsed, {
        eventBus,
        memory,
        persona,
        orchestrator,
      });
      ws.send(JSON.stringify(response));
    });
  });

  return {
    db,
    eventBus,
    memory,
    proactive,
    persona,
    safetyGate,
    scheduler,
    orchestrator,
    start: () =>
      new Promise((resolve, reject) => {
        httpServer.once("error", reject);
        httpServer.listen(config.port, config.host, () => {
          const addr = httpServer.address();
          const boundPort = typeof addr === "object" && addr ? addr.port : config.port;
          resolve({ host: config.host, port: boundPort });
        });
      }),
    stop: async () => {
      scheduler.stop();
      for (const client of clients) {
        client.close();
      }
      await new Promise<void>((resolve) => wss.close(() => resolve()));
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
      db.close();
    },
  };
}

function handleRpc(
  req: RpcRequest,
  deps: {
    eventBus: EventBus;
    memory: MemoryRepository;
    persona: PersonaRenderer;
    orchestrator: OrchestratorRegistry;
  },
): RpcResponse {
  try {
    switch (req.method) {
      case "hub.ping": {
        return {
          jsonrpc: "2.0",
          id: req.id,
          result: {
            status: "ok",
            serverTs: Date.now(),
            activeNodes: deps.orchestrator.listWorkers().length,
          },
        };
      }
      case "event.ingest": {
        const envelope = req.params as EventEnvelope;
        return {
          jsonrpc: "2.0",
          id: req.id,
          result: deps.eventBus.ingest(envelope),
        };
      }
      case "worker.register": {
        const info = req.params as WorkerNodeInfo;
        deps.orchestrator.registerWorker(info);
        return { jsonrpc: "2.0", id: req.id, result: { registered: true, nodeId: info.nodeId } };
      }
      case "worker.heartbeat": {
        const hb = req.params as WorkerHeartbeatPayload;
        deps.orchestrator.recordHeartbeat(hb);
        return { jsonrpc: "2.0", id: req.id, result: { ok: true } };
      }
      case "state.setScript": {
        const { script } = (req.params ?? {}) as { script?: TodayScript };
        if (!script) {
          return {
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32602, message: "Missing script parameter" },
          };
        }
        const entry = deps.memory.upsertWorldState("today_script", script);
        return { jsonrpc: "2.0", id: req.id, result: entry };
      }
      case "mode.switch": {
        const { mode, stealthOffice = false, ttsAllowed = false } = (req.params ?? {}) as {
          mode?: AssistantMode;
          stealthOffice?: boolean;
          ttsAllowed?: boolean;
        };
        if (mode !== "life" && mode !== "secretary") {
          return {
            jsonrpc: "2.0",
            id: req.id,
            error: { code: -32602, message: "mode must be 'life' or 'secretary'" },
          };
        }
        deps.memory.upsertWorldState("assistant_mode", mode);
        const directive = deps.persona.render({
          intent: "generic_reply",
          mode,
          stealthOffice,
          ttsAllowed,
          summaryText:
            mode === "secretary"
              ? "已切换至指挥官秘书模式，多机工作台与死线雷达就绪。"
              : "已切回生活陪伴模式，继续陪着主人~",
        });
        return { jsonrpc: "2.0", id: req.id, result: directive };
      }
      default: {
        return {
          jsonrpc: "2.0",
          id: req.id,
          error: { code: -32601, message: `Method not found: ${req.method}` },
        };
      }
    }
  } catch (err) {
    return {
      jsonrpc: "2.0",
      id: req.id,
      error: {
        code: -32603,
        message: err instanceof Error ? err.message : "Internal error",
      },
    };
  }
}
