# Neko-Secretary

> **Personal Agent OS** — 模型无关、事件驱动、私网优先（Tailscale Mesh）、具备 **Live2D 情感化桌宠** 与 **跨多机多智能体编排** 能力的个人智能体运行系统。

---

## 核心定位：双态人格，统一大脑

Neko-Secretary 不是传统“套壳聊天机器人”，而是由**同一套长期记忆与任务内核（Personal Agent Harness）**驱动的双态个人助理：

1. **生活助理态（猫娘 / 女仆陪伴模式）**
   - 屏幕边缘潜伏（Peek Mode）零挡屏，需要时探头微交互（喝水、多源融合久坐、下班锻炼、深夜劝睡）
   - 每日 3 秒剧本切换（`office` / `wfh` / `interview` / `holiday`）、职场 Wi-Fi 自动防社死隐身（`STEALTH_OFFICE`）
   - 零摩擦输入：长按桌宠即时流式 ASR 语音指令 + 系统分享菜单（`ACTION_SEND`）截图一键吞噬提取
   - 专注防摸鱼监工（`UsageStatsManager`）、论文灵感胶囊、消费续费与物品保质期、Home Assistant 智能家居
2. **指挥官秘书态（多智能体编排模式）**
   - 一键切换干练秘书换装，作为手机端统一指挥台调度多台电脑（实验室 GPU 机、宿舍台式、笔记本）
   - 通过 `pc-worker` 纳管 `T3 Code`、`DeepSeek Harness (dsh)`、`Codex`、`Claude Code` 与实验进程哨兵
   - 笔面试死线雷达（零遗漏）与考前/会前 30 分钟速览卡（**无压迫感，不做突击八股抽查**）
   - L2 级高危操作（`git push`、删除文件、生产变更）挂起并通过手机弹窗 Diff 审批（`SafetyGate`）

> **安全铁律**：人格与模式切换仅作用于表达层（`PersonaRenderer`）与主动打扰策略，绝对不改变底层 `SafetyGate` 的权限边界。

---

## 私网优先架构拓扑

```text
                       ┌───────────────────────────────────────────┐
                       │      Tailscale Private Mesh Network       │
                       │    (未来开源可一键切换为 Cloud WSS Relay)   │
                       └───────┬─────────────┬─────────────┬───────┘
                               │             │             │
        ┌──────────────────────▼───┐   ┌─────▼──────┐   ┌──▼────────────────────┐
        │   Node 0: Android 手机    │   │ Node 1:    │   │ Node 2..N: 多台电脑    │
        │  (瘦终端 + 感知 + 交互)    │   │ Core Hub   │   │ (PC / 实验室GPU / 本)  │
        ├──────────────────────────┤   │ (常驻大脑)  │   ├───────────────────────┤
        │ • Live2D 悬浮桌宠渲染     │   │ • Harness  │   │ • neko-worker (Go)    │
        │ • 流式 ASR + 可选 TTS     │   │ • EventBus │   │ • T3 Code / dsh /     │
        │ • ShareSheet 截图吞噬     │   │ • Scheduler│   │   Codex / Claude Code │
        │ • 传感器/Wi-Fi/UsageStats │   │ • MemoryDB │   │ • 键鼠活跃度心跳上报   │
        │ • 本地离线 Alarm 兜底     │   │ • Proactive│   │ • 实验日志/GPU哨兵     │
        └──────────────────────────┘   └────────────┘   └───────────────────────┘
```

---

## Monorepo 结构

```text
neko-secretary/
├── AGENTS.md                 # AI Coding Agent 执行规范与硬性不变量
├── docs/
│   ├── ARCHITECTURE.md       # 完整系统架构、记忆、调度、主动决策与交互设计
│   ├── PROTOCOL.md           # EventEnvelope / CharacterDirective / JSON-RPC 2.0 协议规范
│   ├── ENGINEERING_SPEC.md   # Node 24/Go/Kotlin 编码规范、SQLite 迁移与安全基线
│   └── ROADMAP.md            # 3 周 AI Coding 分天落地路线图与验收标准
├── packages/
│   ├── protocol/             # 共享 TypeScript 类型与协议定义 (@neko/protocol)
│   ├── core-hub/             # 核心大脑 Node 24 + TS + node:sqlite (@neko/core-hub)
│   └── pc-worker/            # 多机电脑端轻量守护进程 (Go 1.27)
└── apps/
    └── android/              # Kotlin + Jetpack Compose + Live2D TextureView 客户端
```

---

## 快速开始

### 前置依赖
- **Core Hub & Protocol**：Node.js `>= 24.0.0`（利用内置 `node:sqlite` + FTS5 与原生 TypeScript 类型剥离）、`pnpm >= 11`
- **PC Worker**：Go `>= 1.27`
- **Android 客户端**：Android Studio + JDK 17+ + Android SDK 35 +（可选）自行从官网下载的 Live2D Cubism SDK for Java/Native

### 1. 启动 Core Hub（Node 24）
```bash
cp .env.example .env
pnpm install
pnpm typecheck
pnpm test
pnpm dev:hub
```
健康检查：`GET http://127.0.0.1:8787/health`

### 2. 运行 PC Worker（多台电脑）
```bash
cd packages/pc-worker
go test ./...
go run ./cmd/neko-worker --dry-run
# 实际连接 Core Hub：
go run ./cmd/neko-worker --node-id lab-gpu-01 --hub-url ws://100.x.y.z:8787/ws --tags gpu,linux,thesis
```

### 3. Android 与 Live2D 合规导入说明
本仓库保持 **100% 纯净 MIT 开源**：
- `apps/android/live2d-core/` 与 `apps/android/app/src/main/assets/live2d/` 已在 `.gitignore` 中排除闭源二进制与版权模型资源。
- 请按 [`apps/android/README.md`](./apps/android/README.md) 与 [`apps/android/live2d-core/README.md`](./apps/android/live2d-core/README.md) 放置自行下载的 Live2D SDK 库，并在运行时通过模型包导入你的猫娘/女仆模型。

---

## 开发路线图（3 周落地）

详见 [`docs/ROADMAP.md`](./docs/ROADMAP.md)：
- **Week 1**：Core Hub 底座 + Live2D `TextureView` 悬浮窗 + 长按流式 ASR + 截图分享入库 + 喝水主动提醒闭环
- **Week 2**：早安 3 秒剧本 + Wi-Fi 自动防社死 + 可选 TTS 缓存播报 + 死线雷达/考前速览 + 防摸鱼抓包 + 六维记忆检索
- **Week 3**：多机 `pc-worker` 部署 + 3 源融合久坐算法 + 秘书态多智能体编排与 `SafetyGate` Diff 审批 + 可靠性压测

---

## License

[MIT](./LICENSE) © 2026 ER1C-6832
