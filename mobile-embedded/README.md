# DiceFrame 嵌入后端 Android POC(mobile-embedded)

验证 DiceFrame 主仓库方案的阶段 0/1:**把 Python 后端整体嵌入 Android 应用,
手机本机运行完整后端,不依赖 PC/服务器**(AI API 等远端调用除外)。

## 架构

```
┌─ APK: com.diceframe.embedded ──────────────────────────┐
│  WebView ← http://127.0.0.1:18000 ← aiohttp(嵌入运行)  │
│  MainActivity: 解压 assets → filesDir/app_root,        │
│    Chaquopy CPython 3.11 调 poc_boot.start()            │
│  后端源码: 构建期从主仓库根 copy 进 APK assets           │
└─────────────────────────────────────────────────────────┘
```

- REST + SSE 契约与桌面后端完全一致;WebView 加载的即是主仓库 `static-v2`
  构建产物(完整 Web UI:创建向导、AI 服务商配置、世界书等)。
- 与 diceframe-mobile(Expo 原生客户端)的关系:契约不变,后续阶段 2 只需
  把客户端的 backend 地址指向 `127.0.0.1`(本机模式),客户端无需感知差异。

## 构建

### CI(推荐)

`.github/workflows/mobile-embedded-build.yml` 在 `poc/embedded-backend`
分支的 push 与手动触发时运行:Node 20 构建 `frontend-v2` → 输出 `static-v2`
→ Gradle 8.7 + Chaquopy 17 编译 debug APK → 上传 artifact
`diceframe-embedded-debug-apk`。

### 本地

要求:JDK 17、Android SDK(compileSdk 34)、buildPython 可用的 Python 3.11。

```bash
# 先构建前端,保证 static-v2 内容完整
(cd frontend-v2 && npm ci && npm run build)
gradle -p mobile-embedded assembleDebug
# 产物: mobile-embedded/app/build/outputs/apk/debug/app-debug.apk
```

## 运行行为

- 每次启动把 APK assets 里的后端目录解压到 `filesDir/app_root`(约 30MB,
  覆盖式,换取"零陈旧资源"的简单性),随后在后台线程启动嵌入服务。
- `data/` 落在 app 私有目录,等价桌面便携版布局;运行日志在
  `app_root/data/logs`。
- 首次启动生成的访问密码会直接显示在启动界面(设备上无控制台可看
  `data/access_token.txt`),WebUI 打开时按提示输入即可。

## 已知限制(POC 边界,均有意为之)

- 不含前台服务:退到后台后系统可能回收进程;POC 阶段在前台使用。
- 未打包内置 `plugins/`:插件宿主依赖子进程,iOS 与部分 Android 场景不可用,
  按 fail-closed 原则整组不装载;后续以 capability 开关提供 in-process 模式。
- `cryptography` 在 Chaquopy 预编译仓库的 Android/py311 目标上最高 42.0.8,
  POC 依赖下限已放宽(`>=42`,仅限 APK 内;后端只用到其中的稳定 API,且
  本机回环 HTTP 不涉及证书签发)。构建失败时优先检查各包的 wheel 覆盖
  (`--only-binary=:all:` 会直接给出明确报错)。
- iOS 未包含;`web.run_app` 的桌面重启路径(`os.execv`)在嵌入模式下不生效。
