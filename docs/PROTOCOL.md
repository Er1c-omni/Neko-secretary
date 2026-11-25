# Neko-Secretary Wire & RPC Protocol Specification

> 本文档定义 `Android Client (Node 0)`、`Core Hub (Node 1)` 与 `PC Workers (Node 2..N)` 之间的统一通信协议，对应代码实现位于 [`packages/protocol`](../packages/protocol)。

---

## 1. 传输层抽象（`TransportAdapter`）

所有端间实时通道基于 **JSON-RPC 2.0 over WebSocket**（辅以 HTTP `GET /health` 与可选 SSE/multipart 截图上传）：

- **私网优先默认实现**：`TailscaleDirectTransport`（直连 Core Hub 的 Tailscale IP，如 `ws://100.x.y.z:8787/ws`）。
- **未来开源云中继扩展**：`CloudRelayTransport`（`wss://relay.example.com/ws` + JWT/mTLS），业务层无需感知底层拓扑变化。
- **鉴权头**：连接握手需携带 `Authorization: Bearer <HUB_TOKEN>`（在 Tailscale 私网内也建议开启共享密钥校验）。

---

## 2. 统一事件信封（`EventEnvelope`）

所有外部信号（定时触发、截图分享、语音指令、Wi-Fi 切换、PC 报错、外部通知）均封装为统一信封：

```typescript
export interface EventEnvelope<TPayload = Record<string, unknown>> {
  id: string;                 // UUID v4
  idempotencyKey: string;     // 断网重连补发去重键 (e.g. "android:share:sha256")
  type: EventType;            // 标准事件类型
  source: EventSource;        // "android" | "scheduler" | "webhook" | `pc:${string}`
  ts: number;                 // Unix 毫秒时间戳
  payload: TPayload;
}
```

### 标准 `EventType` 目录
- 交互输入：`user.voice_transcript`、`user.text_message`、`user.action_reply`、`share.screenshot`、`share.text_or_url`
- 感知信号：`sensor.wifi_changed`、`sensor.activity_snapshot`、`sensor.usage_distraction`、`sensor.notification_external`、`pc.activity_heartbeat`
- 调度与状态：`scheduler.tick`、`state.script_selected`、`state.mode_switched`
- 编排与哨兵：`pc.worker.registered`、`pc.worker.log_alert`、`agent.thread.updated`、`agent.approval.requested`、`agent.approval.resolved`

---

## 3. 桌宠表达指令（`CharacterDirective`）

Core Hub 的 `PersonaRenderer` 将理性决策转化为标准指令下发至 Android 客户端，客户端**只负责按指令渲染，不维护独立任务逻辑**：

```typescript
export type CharacterStateId =
  | "PEEK_IDLE"
  | "LIFE_GREETING"
  | "LIFE_WATER"
  | "LIFE_STANDUP"
  | "ANTI_PROCRASTINATE"
  | "SECRETARY_ACTIVE"
  | "SECRETARY_ALERT"
  | "SLEEPY_NIGHT"
  | "DOCK_STANDBY"
  | "STEALTH_OFFICE";

export interface CharacterDirective {
  directiveId: string;
  mode: "life" | "secretary";
  state: CharacterStateId;
  stealthOverride: boolean;   // true 时客户端强制静音并切换极简隐身气泡
  text: string;
  live2d: {
    expression: string;
    motion: string;
    costume: string;
  };
  tts: {
    enabledIfAllowed: boolean;
    cacheKey: string | null;  // 优先播放本地缓存音频
    dynamicText: string | null;
  };
  actions: Array<{
    id: string;
    label: string;
    feedbackMotion?: string;
  }>;
  createdAt: number;
}
```

---

## 4. JSON-RPC 2.0 方法表

### 4.1 通用方法
- `hub.ping` → `{ status: "ok", serverTs, activeNodes }`
- `event.ingest`（params: `EventEnvelope`）→ `{ accepted: boolean, deduplicated: boolean, route: "ignore" | "fast_path" | "wake_agent" }`
- `state.setScript`（params: `{ script: "office" | "wfh" | "interview" | "holiday" }`）→ 更新 `world_state.today_script`
- `mode.switch`（params: `{ mode: "life" | "secretary" }`）→ 切换双态并返回对应 `CharacterDirective`

### 4.2 PC Worker 编排方法
- `worker.register`（params: `WorkerNodeInfo`）→ 注册电脑节点 ID、标签、Tailscale IP 与可用 Agents（`t3code` / `dsh` / `codex` / `claude-code` / `shell`）
- `worker.heartbeat`（params: `WorkerHeartbeatPayload`）→ 上报 `lastInputActiveAt`（键鼠活跃度）、CPU/RAM/GPU 指标
- `thread.dispatch`（Core → Worker）→ 派发子任务并附带 `threadId`、`parentThreadId`、`provider`、`prompt`、`workspace`
- `approval.resolve`（Android → Core → Worker）→ 返回用户对 `L2` 危险操作的批准或拒绝决定

### 4.3 服务端主动推送通知（Server Push Notifications，无 `id`）
- `directive.push`（params: `CharacterDirective`）
- `thread.status`（params: `AgentThreadRecord`）
- `approval.prompt`（params: `ApprovalRequest`）

---

## 5. 幂等性与错误码规范

1. **事件幂等**：所有客户端离线缓存事件重发时保留原 `idempotencyKey`，Core Hub 通过 `events.idempotency_key UNIQUE` 保证最多处理一次。
2. **调度防重**：`HybridScheduler` 每次触发生成唯一 `fire_key = ${jobId}:${slotTs}`，防止断线重连导致连弹多次。
3. **JSON-RPC 错误码**：
   - `-32600` Invalid Request
   - `-32601` Method Not Found
   - `-32602` Invalid Params
   - `-32001` Unauthorized（Token 缺失或无效）
   - `-32002` SafetyGate Rejected / Timeout
   - `-32003` Worker Offline
