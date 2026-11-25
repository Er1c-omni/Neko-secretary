# Project Neko-Secretary 完整技术架构与详细设计文档

> **文档版本**：v1.1  
> **目标形态**：模型无关、事件驱动、私网/端云协同、具备 **Live2D 情感化交互** 与 **跨多机多智能体编排** 能力的个人智能体运行系统（Personal Agent OS）  
> **运行策略**：轻量级私网优先（Tailscale Mesh），接口完全解耦，预留未来 Docker 云部署与开源扩展能力  
> **开发周期**：3 周（AI Coding 加速模式）

---

## 1. 项目定位与核心设计哲学

### 1.1 双态人格，统一大脑（Dual-Persona, Unified Harness）

系统并非传统的“套壳聊天机器人”，也不是冷冰冰的极客仪表盘，而是由**一套共享长期记忆与任务内核的 Personal Agent Harness** 驱动的**双态个人助理**，用户可随时手动或按情境自动切换：

1. **生活助理态（猫娘 / 女仆陪伴模式）**
   - **核心目标**：提供高情绪价值、零摩擦记录日常碎片、主动呵护健康与作息、温柔对抗拖延。
   - **典型行为**：贴边潜伏陪伴（不遮挡屏幕）、每日 3 秒剧本定调、喝水 / 三源融合久坐 / 下班锻炼 / 深夜劝睡主动微交互提醒、专注防摸鱼抓包、截图一键吞噬随手记、消费续费 / 物品保质期 / 承诺跟进 / Home Assistant 智能家居控制。
2. **指挥官秘书态（多智能体编排模式）**
   - **核心目标**：以手机为统一指挥台，调度手机端多模型/应用能力，并远程编排**多台电脑**上的本地 Coding Agent 与自动化 Worker。
   - **典型行为**：跨多机派发开发 / 论文 / 实验任务、实时接收报错与 `SafetyGate` 权限审批卡片、多 AI 平台任务通知汇总、笔面试死线雷达与考前/会前速览卡、下班语音攒周报与知识库自动整理。

> **核心安全铁律**：人格与模式切换仅作用于**表达层（Persona Renderer）与主动打扰策略**，绝对不改变底层 **Policy Engine / SafetyGate** 的操作安全边界。

### 1.2 开源与前沿架构借鉴矩阵

| 参考项目 / 协议 | 借鉴层次 | 具体吸收的核心设计 |
| :--- | :--- | :--- |
| **字节 小豆** | 产品形态与主动服务 | 长期个人状态维护、跨应用连接器、生活服务持续跟进、自然语言转长期目标 |
| **Grok Bot** | 独立常驻运行环境 | Agent 拥有 24/7 独立“主机大脑”，异步自主执行，仅在需审批或汇报时找用户 |
| **DeepSeek Harness (`dsh`)** | Agent 微内核 | `Turn/Step Loop`、事件溯源、能力接缝（Capability Seam）、一切皆插件（Plugin）架构 |
| **dsh-Go** | Android 移动端落地 | 手机端悬浮桌宠联动、原生通道通信、远程主机遥控、手机操控的 `SafetyGate` 安全阀门 |
| **T3 Code (Orchestrator V2)** | 跨机多智能体编排 | **Linked Environments 跨机互联**、Provider 统一抽象（Codex / Claude Code / OpenCode / dsh）、持久化 Thread 管理、基于 MCP 的子智能体委托与谱系追踪 |
| **Hermes Agent** | 持久化调度与低成本常驻 | `Cron Scheduler`、`no_agent` 零 Token 脚本任务（空闲不烧大模型）、可复用 Skills 与长期上下文维护 |
| **MCP + Device RPC** | 工具与通信协议 | 外部服务与多机工具统一采用 **MCP（Model Context Protocol）**；设备传感器与 UI 动作采用轻量级 JSON-RPC 2.0 over WebSocket |

---

## 2. 系统总体架构与私网拓扑

### 2.1 轻量级私网优先拓扑（Tailscale Mesh First）

前期无需购买昂贵云集群，利用 **Tailscale** 将手机、核心主机与多台工作电脑组成零配置加密点对点私网（WireGuard），无公网暴露面；同时通过抽象 `TransportAdapter` 接口，未来开源或迁移云端仅需替换为 `WSS + JWT/mTLS` 即可无缝切换。

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
        │ • 豆包级流式 ASR 输入     │   │ • EventBus │   │ • T3 Code / dsh /     │
        │ • ShareSheet 截图吞噬     │   │ • Scheduler│   │   Codex / Claude Code │
        │ • 可选 TTS 播报 & 缓存    │   │ • MemoryDB │   │ • 本地文件 / 论文库    │
        │ • 传感器/Wi-Fi/UsageStats │   │ • Proactive│   │ • 键鼠活跃度心跳上报   │
        │ • 本地离线 Alarm 兜底     │   │ • Orchestr.│   │ • 实验日志/训练监控    │
        └──────────────────────────┘   └────────────┘   └───────────────────────┘
                                              │
                         ┌────────────────────┼────────────────────┐
                         ▼                    ▼                    ▼
                  [Model Gateway]      [External MCPs]      [Home Assistant /
                DeepSeek/Gemini/GPT    Calendar/GitHub/      Webhooks / RSS /
                  火山方舟/Ollama       Notion/Obsidian       网站与论文监控]
```

> **Node 1（Core Hub）的灵活部署策略**：
> - **极简零成本阶段**：直接作为后台常驻进程跑在宿舍/实验室 24 小时开机的电脑上（与 Node 2 共机）。
> - **完全离机常驻阶段**：单进程或 `docker compose up` 迁移至一台 2C2G 轻量云，手机和所有电脑通过 Tailscale 接入，笔记本合盖也不影响全天主动提醒与网站监控。

### 2.2 五层逻辑架构

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ 1. 交互表达层 (Interaction & Persona Layer - Android为主 + 可选Web/桌面端)    │
│  [Live2D 桌宠状态机] [流式ASR语音] [截图ShareSheet] [横屏桌搭] [可选TTS]       │
│  [职场防社死隐身态] [双模式一键切换: 生活女仆 <-> 指挥官秘书]                   │
├──────────────────────────────────────────────────────────────────────────────┤
│ 2. 感性渲染层 (Persona Expression Renderer)                                  │
│  将理性事件 + 当前模式 + 人设Prompt + 情绪/好感 → 转化为对话/动作/表情/按钮    │
├──────────────────────────────────────────────────────────────────────────────┤
│ 3. 核心大脑 (Personal Agent Harness - Core Hub)                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌─────────────────┐  │
│  │  Event Bus   │ │  Scheduler   │ │ Proactive Engine │ │ Agent Runtime   │  │
│  │ 统一事件总线  │ │ 持久化调度器  │ │ 主动打扰评分决策  │ │ Turn/Step Loop  │  │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └─────────────────┘  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌─────────────────┐  │
│  │Memory Engine │ │ Goal Manager │ │Multi-Agent Orch. │ │  Policy Engine  │  │
│  │  六维记忆系统 │ │ 长期目标状态  │ │ 多机多Agent编排   │ │ SafetyGate 审批  │  │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └─────────────────┘  │
├──────────────────────────────────────────────────────────────────────────────┤
│ 4. 感知与执行层 (Perception & Action Plane)                                  │
│  [手机端感知/执行]          [多机 Worker Daemon]        [外部服务与模型]       │
│  • 通知/日历/健康/位置      • 远程命令/代码执行          • Model Gateway      │
│  • UsageStats/Wi-Fi        • Codex/Claude/dsh/T3 代理   • MCP Plugins        │
│  • Intent/闹钟/(实验UI)     • 电脑活跃度/GPU状态上报     • 网站/论文/价格监控   │
├──────────────────────────────────────────────────────────────────────────────┤
│ 5. 基础设施层 (Storage & Observability)                                      │
│  [SQLite (node:sqlite) + WAL + FTS5 + 可选向量扩展] [事件溯源日志] [成本追踪] │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心大脑：Personal Agent Harness 详细设计

### 3.1 事件驱动 Agent Loop（Always-on Agent ≠ Always-on LLM）

系统彻底摒弃“死循环轮询大模型”的烧钱做法，遵循 **Hermes 式零 Token 空闲原则**：

```text
外部/设备/定时信号 (定时到期 / 截图分享 / 电脑报错 / 刷B站超5分钟 / Wi-Fi切换 / 外部通知)
       │
       ▼
  [Event Bus] ──► 统一封装为标准 EventEnvelope { id, type, source, ts, idempotency_key, payload }
       │
       ▼
  [Rule Engine & Trigger Router] (纯本地代码执行，0 Token 延迟 < 5ms)
       ├──► 路径 A (ignore)：重复事件、静默时段低优事件、无状态变化 → 直接丢弃/归档
       ├──► 路径 B (fast_path)：确定性任务（喝水定时、Wi-Fi切防社死、简单记录）
       │         → 跳过推理 LLM，直接查模板/轻量渲染，推送至手机端
       └──► 路径 C (wake_agent)：复杂推理/多步规划/截图多模态解析/多机任务编排
                 → 组装 Context Snapshot → 启动 Turn/Step Loop → Tool Call → SafetyGate 校验 → 输出
```

### 3.2 持久化混合调度器（Hybrid Scheduler）

支持 8 类触发器，所有任务持久化于 SQLite，进程崩溃或手机重启后自动恢复（支持补发窗口与 `fire_key` 幂等去重）：

| 触发类型 | 说明与典型场景 |
| :--- | :--- |
| `time` | 固定时刻触发（如每天 08:30 晨间简报） |
| `interval` | 周期循环触发（如工作模式下每 60 分钟喝水提醒） |
| `event` | 事件监听触发（如电脑训练进程退出、收到“笔试”短信通知） |
| `location` | 地理围栏 / Wi-Fi SSID 触发（连上公司 Wi-Fi 进防社死；离开公司触发下班锻炼判断） |
| `condition` | 状态条件满足触发（今日饮水 < 1500ml 且已到 17:00） |
| `deadline` | 死线梯度逼近触发（笔面试、订阅续费、优惠券到期前 24h / 2h / 30m 梯度升级提醒） |
| `dependency` | 任务依赖触发（等电脑 A 跑完数据预处理，自动唤醒电脑 B 跑评估） |
| `composite` | 组合条件触发（工作日 + 已下班 + 无晚间会议 + 今日未运动 + 天气晴朗 → 建议夜跑） |

**任务持久化 Schema 示例**：
```json
{
  "id": "task_workout_weekday_01",
  "version": 2,
  "name": "下班后锻炼提醒",
  "mode_scope": ["life"],
  "no_agent_script": false,
  "timezone": "Asia/Shanghai",
  "trigger": {
    "type": "composite",
    "all": [
      { "type": "event", "event": "state.work_finished" },
      { "type": "condition", "expr": "state.today_script in ['office', 'wfh']" }
    ]
  },
  "conditions": {
    "workout_completed": false,
    "calendar_busy_next_2h": false,
    "fatigue_level_lt": 80
  },
  "fallback": { "type": "time", "time": "19:30" },
  "policy": {
    "quiet_hours": ["23:30", "07:30"],
    "defer_when_busy": true,
    "max_fires_per_day": 2,
    "retry_on_offline_ttl_min": 60
  },
  "action": {
    "type": "proactive_notify",
    "intent": "encourage_workout",
    "channel": "live2d_bubble",
    "quick_replies": ["换衣服开练！", "今天太累休一天", "推迟30分钟"]
  },
  "status": "active"
}
```

### 3.3 主动决策引擎（Proactive Engine）与多源融合感知

区分于普通闹钟的核心模块：在行动前先评估**“此刻是否适合打扰主人”**。

#### 1) 决策评分公式与硬性约束
$$S = w_1 U + w_2 R + w_3 V - w_4 I - w_5 F$$
- $U$（Urgency 紧急度）：笔面试死线、电脑实验崩溃 = 95+；喝水 = 45；闲聊/资讯 = 20。
- $R$（Relevance 相关性）：与今日剧本模式、当前活跃目标的匹配度。
- $V$（Value 提醒价值）：错过后果的严重性。
- $I$（Interruption 打扰成本）：是否正在开会（日历）、是否全屏打游戏/看视频、是否正高速输入代码（PC 心跳高频活跃）、是否处于工位防社死态。
- $F$（Fatigue 通知疲劳度）：过去 2 小时内已弹窗次数与连续被用户“忽略/推迟”的惩罚项。
- **硬约束拦截（Hard Guards）**：静默时段除 L0 级紧急事件（`urgency >= 95`，如即将开始的笔面试或实验崩溃）外一律拦截；驾驶状态仅允许极简语音；防社死态强制禁音+缩略显示。
- **策略自学习闭环**：记录每次提醒的反馈（`accepted` / `snoozed` / `dismissed` / `ignored`），定期微调权重与时间窗口。

#### 2) 多源融合久坐识别算法
单靠手机传感器静止极易把“手机放桌上人已离开”误判为久坐。系统采用**三源加权融合判定**：
```text
久坐判定 = (手机静止 >= 45min)
        AND (Health Connect 近 45min 步数增量 < 30 步)
        AND ( [任一 PC Worker 上报过去 10min 有键鼠活跃] OR [手机亮屏且处于静止] )
        AND (今日剧本 ∈ {office, wfh, thesis_sprint})
```

### 3.4 理性内核与感性表达解耦（Persona Expression Pipeline）

```text
[Harness 产出理性指令]
  intent: "sedentary_alert", pc_active_mins: 65, current_mode: "life", stealth: false
       │
       ▼
[Persona Expression Renderer]
  输入: 人设配置 + 当前模式 + 今日情绪记忆 + 是否允许TTS
  策略: 高频常规场景走【本地多样化变体模板库(0 Token)】; 复杂/安慰/汇报场景走【轻量快速模型渲染】
       │
       ▼
[输出标准 CharacterDirective 下发至 Android]
```

---

## 4. 指挥官秘书：多智能体与多设备编排架构

### 4.1 跨多台电脑的 Multi-Agent Orchestrator

支持同时纳管多台异构电脑（例如：`lab-gpu-01` 实验室训练机、`dorm-win-pc` 宿舍台式机、`personal-mac` 随身笔记本）。

#### 1) 电脑端轻量守护进程（`neko-worker`，Go 单二进制）
每台电脑运行一个轻量级常驻守护进程，通过 Tailscale 与 Core Hub 保持长连接：
1. **能力注册与心跳**：上报主机名、标签、硬件负载（CPU/GPU 显存利用率）、本地工作区目录、**键鼠最后活跃时间**（喂给久坐算法）、以及本机已安装的 Coding Agents。
2. **多 Harness 统一适配（Provider Adapter）**：
   - **T3 Code Server / MCP**：复用其 Orchestrator MCP 接口，统一调度本机 Codex、Claude Code、OpenCode 并复用 Git Worktree 与持久化 Thread。
   - **DeepSeek Harness (`dsh`) / CLI Agents**：以子进程 PTY / JSON-Stream 方式托管 `dsh`、`codex`、`claude` 或 Shell 任务。
3. **文件系统与知识库桥梁**：按白名单暴露论文目录（LaTeX / Markdown）、Obsidian 笔记库读写能力。
4. **进程与实验哨兵**：监听指定日志或训练进程关键字（如 `CUDA out of memory`、`Epoch finished`、`Test Failed`），第一时间推送事件至 Core Hub。

#### 2) 跨机任务路由与 Human-in-the-Loop 审批流
1. `Intent & Topology Router` 按节点标签或工作区把用户指令拆分为多个并行子任务，创建带 `parent_thread_id` 谱系追踪的 `agent_threads`。
2. 手机端**指挥官秘书工作台**实时展示各机任务状态（`queued` / `running` / `waiting_approval` / `completed` / `failed`）。
3. 遇 L2 高危操作（`git push`、删除文件、生产配置修改），`SafetyGate` 挂起并向手机推送审批卡片与 Diff 摘要，用户一键批准或拒绝。

### 4.2 手机端多 AI 协同与系统控制（零逆向 P1–P5 分层方案）

| 优先级 | 技术机制 | 覆盖能力与典型场景 | 稳定性与合规性 |
| :--- | :--- | :--- | :--- |
| **P1 官方 API / MCP** | 自建 **Model Gateway** | 统一封装 DeepSeek / OpenAI / Gemini / 火山方舟 / Claude / 本地 Ollama；按场景路由与成本统计 | 极高，首选 |
| **P2 分享菜单与截图吞噬** | Android `ACTION_SEND` + 多模态 Vision | 任意 App 截图点发送/分享给助理，秒级提取面试通知/账单/续费/论文图表/灵感并入库 | 极高，零风控 |
| **P3 系统通知感知** | `NotificationListenerService` | 授权监听其他 AI App 任务完成通知及短信/邮件笔面试/快递/扣款通知 | 高，仅读授权通知 |
| **P4 Android 公开系统 API** | `Intent`, `CalendarProvider`, `AlarmManager`, `HealthConnect`, `UsageStats`, `Geofencing` | 设闹钟、写日历、拉起应用、读步数、防摸鱼检测、公司 Wi-Fi 切换防社死 | 极高，系统原生 |
| **P5 实验性 UI 操控（可选）** | `AccessibilityService` / `Shizuku` | 完全独立的可插拔实验模块（默认关闭），仅个人自用且强制过 `SafetyGate` | 中，仅自用扩展 |

---

## 5. 六维记忆系统与极简 SQLite 数据建模

采用 **Node.js 24 原生 `node:sqlite` (`DatabaseSync`) + WAL 模式 + FTS5 全文检索 + 可选向量扩展**，零外部 C++ 编译依赖，单文件随走随备份。

### 5.1 六维记忆分工
1. **Profile Memory（个人画像）**：称呼、学校/实习信息、作息规律、偏好、双模式人设参数。
2. **Episodic Memory（情景事件记忆）**：带时间戳的日常碎片、截图归档、实习工作流水、面试经历、锻炼记录。
3. **Semantic Memory（语义知识记忆）**：论文灵感、技术笔记、整理的资料摘要、长期偏好提炼。
4. **Procedural Memory（程序性记忆 / Skills）**：可复用工作流 SOP（如“周五下午汇总实习周报”、“收到面试截图后建日程与速览卡”）。
5. **Prospective Memory（前瞻性记忆）**：未来目标、笔面试死线、承诺跟进、订阅续费、物品保质期/补货周期。
6. **State Memory（实时世界状态）**：键值快照——今日剧本模式、当前场景、防社死开关、电脑活跃状态、今日饮水量、通知疲劳度。

### 5.2 核心 SQLite 表结构
见初始化迁移脚本 [`packages/core-hub/migrations/001_initial_schema.sql`](../packages/core-hub/migrations/001_initial_schema.sql)，包含：
- `events`（事件溯源 + `idempotency_key` 去重）
- `memories` + `memories_fts`（FTS5 全文检索与增量同步触发器 + 软删除）
- `prospective_items`（`goal | deadline | commitment | subscription | inventory | habit`）
- `world_state`（含 TTL 自动过期状态）
- `scheduled_jobs`（持久化调度规格）
- `worker_nodes` + `agent_threads`（多机节点、键鼠心跳与多智能体谱系）
- `proactive_feedback`（主动提醒反馈闭环日志）

---

## 6. Android 客户端与 Live2D 桌宠交互深度设计

### 6.1 Live2D 进阶渲染架构（悬浮窗 + 低功耗治理）
1. **透明悬浮窗渲染**：前台服务 + `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` + **`TextureView` + 自定义 EGLThread**（避免 `GLSurfaceView` 在拖拽、贴边和透明合成时的黑底与撕裂问题）。
2. **功耗与帧率自适应**：
   - 交互 / 口型同步 / 动作播放：**60 FPS**
   - 展开待机：**20 FPS**
   - 贴边潜伏态（`PEEK_IDLE`）：**10 FPS** 或静态缓存帧
   - 息屏或防社死隐身态（`STEALTH_OFFICE`）：**0 FPS（完全暂停 GL 渲染）**
3. **开源合规设计**：闭源 `libLive2DCubismCore.so` 与个人 Live2D 模型资源在 Git 中忽略，运行时支持外部 `.zip` 模型包导入。

### 6.2 10 态角色状态机（`CharacterStateMachine`）
`PEEK_IDLE`（边缘潜伏） / `LIFE_GREETING`（早安3秒剧本卡） / `LIFE_WATER`（抱水杯） / `LIFE_STANDUP`（久坐提醒） / `ANTI_PROCRASTINATE`（防摸鱼抓包） / `SECRETARY_ACTIVE`（换装眼镜秘书） / `SECRETARY_ALERT`（报错/L2审批/死线） / `SLEEPY_NIGHT`（睡衣劝睡） / `DOCK_STANDBY`（横屏桌搭陪写） / `STEALTH_OFFICE`（职场防社死极简徽章）。

### 6.3 零摩擦输入与可选 TTS
1. **长按桌宠流式 ASR**：主力火山引擎流式语音识别（或阿里云 FunASR），端侧 Sherpa-ONNX 离线兜底；松手发送、上滑取消，自动去口头禅送入 Intent Router。
2. **截图一键发送（`ACTION_SEND`）**：任意 App 截完图直接分享给本 App，多模态解析后 2~3 秒内弹出确认卡片（自动入库日历/死线/账单/论文灵感）。
3. **可选 TTS 语音**：默认关闭或仅居家私密开启；处于 `STEALTH_OFFICE` 或公司 Wi-Fi 且未戴耳机时**强制静默**。开启时优先播放本地缓存的高频短句音频（0 延迟 0 Token + Live2D RMS 口型同步）。

---

## 7. 全场景功能矩阵（生活、工作与跨设备生态）

| 场景类别 | 功能模块 | 触发与数据源 | 体验要点 |
| :--- | :--- | :--- | :--- |
| **每日节律** | 晨间简报 + 3 秒剧本定调 | 早间解锁 / 定时 | 一键选今日剧本（搬砖/居家/面试/放假），联动全天提醒策略 |
| | 下班总结与语音攒周报 | 离开围栏 / 18:30 | 按住桌宠碎碎念 30 秒今日进度，周五一键生成周报与简历素材 |
| **秋招与工作** | 笔面试死线雷达（零遗漏） | 截图发送 + 通知监听 | 24h / 2h / 30m 梯度升级提醒 |
| | 考前 / 会前速览准备 | 面试/会议前 30 分钟 | 秘书态弹出公司/会议文档与项目准备清单（**不做突击八股抽查**） |
| | 可选语音复盘归档 | 面试后（用户主动） | 温和慰问，若用户语音吐槽则自动整理成 Markdown 面经 |
| | 承诺跟进 | 语音 / 截图提取 | 自动记住口头/消息承诺，到期前温柔提醒 |
| **论文与专注** | 专注防摸鱼监工 | `UsageStatsManager` | 专注期刷娱乐 App 超阈值，桌宠爬到屏幕中央撒娇赶人回去 |
| | 论文灵感与知识库 | 语音碎碎念 + 截图 | 打标签直写指定电脑的 Obsidian / Markdown 仓库 |
| **多机与极客** | 多台电脑 Agent 指挥台 | 秘书态语音 / 卡片 | 跨多机向 T3 Code / dsh / Codex / Claude Code 派单与审批 |
| | 网站 / 仓库 / 实验监控 | Cron + PC Worker | 部署异常、GitHub Release、Arxiv、GPU Loss 报错即时报信 |
| | 跨设备接力 | Tailscale Mesh | 手机查看审批电脑任务，收集的灵感回电脑立即可用 |
| **生活与健康** | 动态健康习惯 | 三源融合 + HealthConnect | 全天喝水、仅工作态久坐、结合天气与疲劳度的下班锻炼建议、深夜劝睡 |
| | 消费与物品管理 | 截图发送（账单/订单） | 订阅续费、优惠券到期、日用品/隐形眼镜/滤芯保质期提醒 |
| | 智能家居控制 | Home Assistant MCP | 语音控灯/空调，支持桌搭联动与离家自动关闭 |

---

## 8. 安全、隐私与工程可靠性设计

1. **SafetyGate 三级权限**：`L0`（只读自动放行）、`L1`（低风险可逆执行并通知）、`L2`（删除/高危 Shell/`git push`/外部消息/实验性 UI 操控，强制挂起并手机弹窗审批）。
2. **Prompt Injection 防御**：所有不可信外部文本统一以 `<untrusted_external_data source="...">...</untrusted_external_data>` 包裹，禁止把外部数据中的指令当成系统/工具指令执行。
3. **长期运行可靠性**：`BOOT_COMPLETED` 重启自恢复、断网降级本地 `AlarmManager` 兜底与 `idempotency_key` 补发、`fire_key` 防重复弹窗、权限撤销自动优雅降级。

---

## 9. 轻量级技术栈与 Monorepo 规范

- **Core Hub & Protocol**：TypeScript + Node.js v24+ (`node:sqlite`, `ws`, `node:test`) + pnpm Workspace
- **PC Worker**：Go 1.27（单二进制、零运行时依赖）
- **Android 客户端**：Kotlin + Jetpack Compose + Coroutines + Room + Ktor Client + Live2D (`TextureView`)
- **组网通信**：Tailscale Mesh + JSON-RPC 2.0 over WebSocket（通过 `TransportAdapter` 预留 Cloud WSS Relay）

---

## 10. 3 周 AI Coding 路线图与开源解耦检查点

详细任务拆分见 [`docs/ROADMAP.md`](./ROADMAP.md)。开源解耦三要素：
1. 传输层走 `TransportAdapter`（私网直连与云中继可插拔）；
2. Live2D 闭源库与模型资产运行时导入，仓库 MIT 纯净；
3. 所有业务技能基于标准化 MCP Tool + Manifest 扩展。
