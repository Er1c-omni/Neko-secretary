# Live2D Cubism SDK Core Placement (Excluded from Git)

To enable the real Live2D Cubism renderer (`CubismModelBackend`), download **Live2D Cubism SDK for Java** from the official Live2D website and place the closed-source artifacts here before building in Android Studio:

```text
apps/android/live2d-core/
├── Live2DCubismCore.aar               # (or Live2DCubismCore.jar + jniLibs/<abi>/libLive2DCubismCore.so)
├── jniLibs/                           # optional when using .jar instead of .aar
│   └── arm64-v8a/libLive2DCubismCore.so
└── framework/
    └── src/main/java/com/live2d/sdk/cubism/framework/...  # copied from CubismSdkForJava/Framework/src/main/java
```

How `app/build.gradle.kts` detects it:
- When **both** `Live2DCubismCore.{aar,jar}` and `framework/src/main/java` are present, Gradle sets `BuildConfig.CUBISM_ENABLED = true` and compiles `app/src/cubism/java/com/neko/secretary/live2d/cubism/CubismModelBackend.kt`.
- When absent (fresh clone / CI / OSS users), the app compiles cleanly and uses `PlaceholderNekoBackend` (transparent SDF chibi-cat) so the overlay, 60/20/10/0 FPS pacing, edge docking, click-through, and stealth mode all work out of the box.

All binaries and framework files in this folder (except this README and `.gitkeep`) are ignored by `.gitignore` to keep the repository MIT-clean.
