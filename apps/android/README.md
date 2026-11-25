# Neko-Secretary Android Client

原生 Kotlin + Jetpack Compose + Live2D (`TextureView` + EGL) 客户端骨架。

## 本地环境要求
- Android Studio (Ladybug / Meerkat+)
- JDK 17+
- Android SDK Platform 35 (compileSdk = 35, minSdk = 28)

## Live2D 合规接入步骤（必读）
本仓库不包含闭源 Live2D SDK 二进制库及版权模型资源。运行 Live2D 渲染前：
1. 前往 Live2D 官网下载 **Cubism SDK for Java / Native**。
2. 将 `Live2DCubismCore.jar` 与对应 ABI (`arm64-v8a/libLive2DCubismCore.so`) 放入 `apps/android/live2d-core/`（详见 [`live2d-core/README.md`](./live2d-core/README.md)）。
3. 运行时在 App 设置页导入你自己的 `.zip` Live2D 模型包，或临时放入测试目录 `app/src/main/assets/live2d/`（已被 `.gitignore` 忽略）。

## 核心模块映射
- `overlay/CompanionOverlayService.kt`: 悬浮窗前景服务、边缘贴边潜伏与防社死隐身开关
- `live2d/Live2DTextureRenderer.kt`: `TextureView` + 60/20/10/0 FPS 自适应 EGL 渲染器
- `live2d/CharacterStateMachine.kt`: 10 态角色状态机
- `voice/VoicePipeline.kt`: 长按流式 ASR + Sherpa-ONNX 离线兜底 + 可选缓存 TTS
- `perception/ShareIntakeHandler.kt`: `ACTION_SEND` 截图与链接一键接收
- `sync/TransportClient.kt`: `TransportAdapter`（Tailscale 直连默认，预留 Cloud Relay）
- `actions/SafetyGateClient.kt`: L2 级远端审批弹窗状态模型
