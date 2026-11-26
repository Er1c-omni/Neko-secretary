# Neko-Secretary Android Client

原生 Kotlin + Jetpack Compose + Live2D (`TextureView` + EGL) 客户端骨架。

## 本地构建与单测（CLI / Android Studio）
- Android Studio (Ladybug / Meerkat+) 直接打开 `apps/android`
- 命令行（Windows，复用 Android Studio 内置 JBR）：
  ```powershell
  cd apps/android
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
  ```

## Live2D 合规接入步骤（必读）
本仓库不包含闭源 Live2D SDK 二进制库及版权模型资源：
1. 未放入闭源 SDK 时，Gradle 自动编译内置的 `PlaceholderNekoBackend`（透明 SDF 占位猫娘），悬浮窗拖拽、贴边潜伏、点击透传、防社死隐身与 60/20/10/0 FPS 自适应均可开箱运行。
2. 如需启用真实 Live2D 模型渲染，前往 Live2D 官网下载 **Cubism SDK for Java**，按 [`live2d-core/README.md`](./live2d-core/README.md) 放入 `Live2DCubismCore.aar`（或 `.jar` + `jniLibs/`）与 `framework/src/main/java/`，Gradle 会自动启用 `CubismModelBackend`（`BuildConfig.CUBISM_ENABLED = true`）。
3. 运行时在 App 控制台点击 **Import .zip** 导入个人 Live2D 模型包，或临时放入测试目录 `app/src/main/assets/live2d/`（均已被 `.gitignore` 忽略）。

## 核心模块映射
- `overlay/CompanionOverlayService.kt`: 悬浮窗前景服务、拖拽甩边吸附、贴边潜伏（`PEEK_IDLE`）、透明区域点击透传与防社死隐身
- `overlay/EdgeDocking.kt` / `BubbleWindow.kt` / `StealthTileService.kt` / `OverlayState.kt`: 贴边几何、独立气泡窗、系统快捷设置隐身磁贴与运行遥测
- `live2d/Live2DTextureRenderer.kt` / `Live2DRenderThread.kt` / `EglCore.kt`: `TextureView` + 独立 RGBA8888 EGL 渲染线程（pbuffer 保活、丢上下文重建）
- `live2d/FramePacingPolicy.kt` / `HitTestMask.kt` / `Live2DModelRepository.kt`: 60/20/10/0 四级帧率策略、Alpha 命中网格与安全 `.zip` 模型导入
- `live2d/Live2DModelBackend.kt` / `PlaceholderNekoBackend.kt` / `cubism/CubismModelBackend.kt`: 渲染后端解耦接口、占位渲染器与条件编译的真实 Cubism 后端
- `live2d/CharacterStateMachine.kt`: 10 态角色状态机（隐身正交锁）
- `voice/VoicePipeline.kt` / `VolcengineProtocol.kt` / `FunAsrProtocol.kt` / `AudioFrameCapture.kt` / `VoiceGestureMachine.kt` / `SherpaOnnxFallback.kt`: 长按说话/松手发送/上滑取消状态机、16kHz PCM 分帧 + RMS 包络、火山引擎/FunASR 双协议流式 ASR、Sherpa-ONNX 离线兜底与口头禅清洗
- `perception/ShareIntakeHandler.kt`: `ACTION_SEND` 截图与链接一键接收
- `sync/TransportClient.kt`: `TransportAdapter`（Tailscale 直连默认，预留 Cloud Relay）
- `actions/SafetyGateClient.kt`: L2 级远端审批弹窗状态模型
