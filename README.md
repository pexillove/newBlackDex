# newBlackDex

Android DEX 脱壳工具。基于 [BlackDex](https://github.com/CodingGay/BlackDex) 的复活/移植分支，向上兼容到 Android 14 以上（主要在 Android 16 / HyperOS 上测试），并修复了原项目在高版本上无法运行的一系列问题。

> 原项目作者：CodingGay。本仓库仅做向上兼容和 Bug 修复，不包含任何针对各加固厂商的过检测逻辑。

---

## 软件用途

BlackDex 用于把 Android 应用运行时加载到内存中的 DEX 文件 dump 出来，主要用于：

- 学习、分析加了壳（加固）的应用的 DEX 结构
- 对抗「类抽取」「函数抽取」等壳，尽量还原出可被反编译的 DEX
- 安全研究 / 逆向学习

**请勿用于破解、盗版或任何侵权用途。** 仅限对自有应用或已获授权的应用进行分析。

---

## 支持范围与限制

- **系统版本**：仅向上兼容到 Android 14+，实测机型为 Android 16（HyperOS 3）。更低版本不保证。
- **架构**：
  - arm64-v8a（默认 BlackDex64）
  - armeabi-v7a（通过内嵌 32 位 Helper 辅助程序实现，无需单独安装 BlackDex32）
- **可用脱壳方式**：
  - ✅ Cookie 模式内存 dump（主路径，已修复）
  - ✅ Hook 脱壳（已修复，Android 16 改 hook `LoadClass`）
  - ✅ 主动调用脱壳（cookie 模式下，用于对抗函数抽取壳）
  - ✅ 32 位应用脱壳（通过 Helper 辅助程序）
  - ✅ 双架构同时脱壳（32+64 位并行，结果分别保存）
  - ✅ CompactDex 支持（从 vdex/oat 编译加载的 dex，保留 `cdex` magic 写出）
  - ❌ 深度脱壳（fixCodeItem）**未修复**：Android 13 起 `ArtMethod` 删除了 `dex_code_item_offset_`，原偏移计算逻辑失效，本仓库未修复，用「主动调用」代替。
- **不做**：不包含任何针对加固厂商的过检测、反反调试等行为，能否脱出看壳的实现与运气。
- 可能出现：脱不出东西、进程卡死、目标 app 异常退出等。属正常现象。

---

## 脱壳逻辑

BlackDex 把目标 APK 安装到一个**内部沙箱**（虚拟化环境）里运行，在目标 app 运行时从内存中提取 DEX。整体流程：

```
App (Application)
  └─ AppManager
       └─ BlackDexLoader  (读取设置、ClientConfiguration)
            └─ BlackDexCore  (单例门面)
                 └─ BlackBoxCore  (真实引擎)
                      └─ dumpDex(pkg|file|uri)
                           ├─ installPackage  安装目标 APK 到沙箱
                           └─ launchApk       在代理进程里启动目标
```

### 进程模型

- **主进程** `top.niunaijun.blackdexa64`：UI 与调度，`WelcomeActivity -> MainActivity`。
- **`:black` 进程**：系统服务进程，承载 `DaemonService` 和 `SystemCallProvider`，是 IPC 与包管理的核心。启动时会把 `empty.jar`/`junit.jar`/`vm.jar` 从 assets 拷贝到 `virtual/cache/` 下作为运行时依赖。
- **`:p0`..`:p99` 代理进程**：目标 app 实际运行的进程。`BActivityThread` 是每个代理进程的入口，负责绑定目标 app 并触发脱壳。
- **Helper 辅助程序** `top.niunaijun.blackdex32helper`（32 位）：内嵌在主 App assets 中，按需安装。拥有自己的 `:black`、`:p0`..`:p99` 进程，全部以 32 位模式运行，用于脱壳 32 位应用。

### 启动一次脱壳的时序

1. 主进程调用 `BlackDexCore.dumpDex(...)`，把目标 APK 安装进沙箱。
2. `launchApk` 通过 `ProxyActivity$P0` 在 `:p0` 进程拉起目标 app。
3. `:p0` 里 `HCallbackProxy` 拦截 `EXECUTE_TRANSACTION`，在首次启动时调用 `BActivityThread.bindApplication`：
   - `VMCore.init(SDK)` 初始化 native（校准 ArtMethod 偏移、安装 JNI hook）
   - `IOCore.enableRedirect` 安装文件 IO 重定向（hook `UnixFileSystem` 的 native 方法，把目标 app 写死的路径重定向到沙箱目录）
   - `makeApplication` 构造目标 app 的 `Application`（此时目标 dex 已加载到内存）
   - 调度 `handleDumpDex`（独立线程，延迟 500ms 执行 cookie dump）
4. dump 完成后上报结果、卸载目标包、退出 `:p0`。

### 三种脱壳方式

#### 1. Cookie 模式内存 dump（主路径）

`VMCore.cookieDumpDex(ClassLoader, packageName)`：

- 通过 `DexFileCompat.getCookies(classLoader)` 反射拿到目标 ClassLoader 里所有 `DexFile` 的 `mCookie`（`long[]`，每个元素是一个 native `DexFile*` 指针）。
- 对每个 cookie，native 侧 `DexDump::cookieDumpDex` 读取 `DexFile` 对象里的 `begin_`（dex 内存起始地址）和 `size_`（dex 大小），把整段 dex 内存 `memcpy` 出来，修复 magic 后写文件。
- **`beginOffset` 运行时校准**：不同 Android 版本 `DexFile` 布局不同，不能硬编码偏移。`init()` 会加载一个已知大小（1872 字节）的 `empty.apk`，在它的 `DexFile` 对象里搜索这个大小值，定位到 `begin_` 字段的偏移，并校验该校准出的 `begin` 处确实是 `dex\n` magic，避免误匹配到 OatFile 等非 DexFile 的 cookie。
- 写出文件名：`cookie_<size>.dex`。

#### 2. Hook 脱壳

`VMCore.hookDumpDex(dir)`，在 `AppInstrumentation.newApplication` 里（开启「Hook Dump」时）调用：

- hook libart.so 里的类加载函数，每当加载一个类/方法时拿到 `DexFile*`，把对应 dex dump 出来。能抓到 cookie 里可能没有的、运行时动态加载的 dex。
- **Android 14/15**：hook `ClassLinker::LoadMethod`（按版本匹配多个 mangled 符号）。
- **Android 16+**：`LoadMethod` 不再从 libart.so 导出，改为 hook 仍导出的 `ClassLinker::LoadClass`（签名 `LoadClass(this, Thread*, const DexFile&, const dex::ClassDef&, Handle<mirror::Class>)`，DexFile 是第 3 个参数）。
- hook 回调里复用 `handleDumpByDexFile`，按 size 去重后写出 `hook_<size>.dex`。
- 同样使用运行时校准的 `beginOffset`，并做 size 合理性 + 整段可读性校验，避免布局偏移不对时 `memcpy` 越界崩溃。
- **CompactDex 支持**：从已编译的 vdex/oat 加载的 dex（重复脱壳、系统自动编译）在内存中是 CompactDex（magic `cdex`）。`handleDumpByDexFile` 与 `cookieDumpDex` 会保留 `cdex` magic 而非覆盖成 `"dex\n035"`，让 `DexFileLoader::OpenAll` 按 CompactDex 正确解析并写出。写出的文件带 `cdex` magic，可用支持 CompactDex 的工具（如 jadx、`compact_dex_converter`）分析。

#### 3. 主动调用脱壳（cookie 模式下，对抗函数抽取壳）

`VMCore.autoCallAllMethod`，在 cookie dump 前执行：

- 拿到目标 ClassLoader 的所有类名列表，逐个 `loadClass`，触发类初始化和方法加载。
- 对「函数抽取壳」（运行时才把被抽取的 code item 还原回内存），主动调用能让这些 code item 被还原，提升 dump 出的 dex 的完整度。
- 过滤掉 `com.luoye.dpt`（dpt 壳检测类）和 `top.niunaijun`（自身类），避免被检测或循环。

### 32 位兼容脱壳

Android 16 上同一 APK 只能以一种 ABI 运行，64 位主 App 无法直接脱壳 32 位应用。解决方案：

1. 主 App 内嵌一个精简的 32 位 Helper APK（`assets/helper32.apk`，约 3.7MB），按需安装。
2. 用户在设置页启用「32 位兼容」并安装 Helper。
3. 脱壳 32 位应用时，主 App 通过透明 Activity 启动 Helper 的 `DumpLauncherActivity`，传递目标包名和配置。
4. Helper 在自己的沙箱中执行完整的脱壳流程（与主 App 逻辑相同，但以 32 位运行）。
5. 脱壳进度通过 Broadcast 广播实时回传主 App，UI 统一在主 App 显示。
6. Helper 配置通过 SharedPreferences 跨进程共享（主进程设置，`:p0` 进程读取）。

**Dump 目录**：Helper 优先尝试写入主 App 的 `Download/dexDump/`，若不可写（scoped storage 限制）则回退到自身 `getExternalFilesDir/dexDump/`。

### 双架构同时脱壳

开启后，对于同时包含 32 位和 64 位库的目标应用，主 App（64 位）和 Helper（32 位）同时执行脱壳：

- 主 App 64 位 dump -> `dexDump/<包名>/arm64/`
- Helper 32 位 dump -> `dexDump/<包名>/arm32/`
- 两个 dump 并行执行，主 App 等待两者都完成后显示合并结果

### native 注册与 VMCore 类

- `VMCore` 是 native 方法承载类。`libblackdex.so` 在 `JNI_OnLoad` 里通过 `RegisterNatives` 把 native 方法注册到 `VMCore`。
- 历史上 `vm.jar`（沙箱 classloader 用的 dex）里也放了一份 `VMCore`，会与宿主的 `VMCore` 形成两个不同的 `Class`，而 `RegisterNatives` 只会注册到先加载 lib 的那个，导致另一个调 native 时 `UnsatisfiedLinkError`。本仓库已把 `vm.jar` 改为占位 dex（不含 `VMCore`），全局只保留宿主一个 `VMCore` 类，cookie 和 hook 两种模式都走它。
- `hookDumpDex` 由 `private` 改为 `public`，`AppInstrumentation` 直接用宿主 `VMCore` 调用，不再反射目标 classloader 里的 `VMCore`。

### Android 16 关键兼容修复

- `UnixFileSystem.canonicalize0` 在 Android 16 改为非 native 方法，`RegisterNatives` 对非 native 方法会失败并抛 `NoSuchMethodError`。`JniHook::HookJniFun` 在 `RegisterNatives` 失败、`FindClass`、`GetArtMethod` 等所有路径上清除了挂起异常，避免下一次 `FindClass` 触发 `AssertNoPendingException` -> `SIGABRT`。
- `:p0` 进程在 `ProxyActivity` 装饰窗创建时，框架会调 `Settings.Global` 读取桌面模式标志，目标 app 包名与宿主 uid 不匹配会抛 `SecurityException` 杀进程（在 dump 线程跑之前）。新增 `sDumping` 标志，dump 期间 `HCallbackProxy` 手动派发事务并吞掉框架异常，保证 dump 线程跑完；dump 完成后主动 `Process.killProcess` 退出 `:p0`。
- `ClassLinker::LoadMethod` 不再导出 -> 改 hook `LoadClass`。
- `vm.jar` 缓存不更新 -> `initJarEnv` 拷贝前先 `setWritable(true)`。
- `BlackDexCore.isRunning()` 进程名匹配改为按包名前缀过滤，避免误检测 Helper 的代理进程。
- Helper 配置（dumpDir、脱壳选项）通过 SharedPreferences 持久化，确保 `:p0` 进程能读到主进程设置的值。

---

## 设置项说明

| 设置 | 作用 |
|---|---|
| Use default storage path / Customize | dump 输出目录。默认 `Download/dexDump`（Android R+），否则 `<externalCacheDir>/../dump` |
| Hook Dump | 开启 Hook 脱壳（hook `LoadClass`/`LoadMethod`），输出 `hook_*.dex`，提高成功率 |
| Deep Unpacking（深度脱壳） | 修复被抽取的 DexCode（**Android 13+ 已失效，未修复**），开启会明显变慢且可能失败 |
| Call Method（主动调用） | 运行时主动调用目标所有类，对抗函数抽取壳（仅 cookie 模式生效） |
| Verify Dex Before Dump | dump 前校验 dex magic。部分加固会把内存中 dex magic 清零对抗脱壳，此时可关闭此项以 dump 出 magic 被破坏的 dex（写出时仍会回填 `dex\n035` magic）。放行 StandardDex（`dex`）和 CompactDex（`cdex`） |
| Enable 32-bit Compatibility | 启用 32 位兼容。安装 Helper 辅助程序后可脱壳 32 位应用 |
| Install/Update Helper | 从 assets 安装或更新 32 位 Helper APK |
| Dual-Architecture Dump | 目标同时包含 32/64 位时，同时执行两种架构的脱壳。结果分别保存到 `arm64/` 和 `arm32/` 子目录 |

---

## 输出位置

- **64 位脱壳**：`/storage/emulated/0/Download/dexDump/<目标包名>/`
- **32 位脱壳**：同上（若 Helper 可写入 Download），或 `/storage/emulated/0/Android/data/top.niunaijun.blackdex32helper/files/dexDump/<目标包名>/`（回退目录）
- **双架构脱壳**：
  - `dexDump/<目标包名>/arm64/` — 64 位结果
  - `dexDump/<目标包名>/arm32/` — 32 位结果
- 可在设置里自定义输出目录。
- 文件命名：`cookie_<size>.dex`（cookie 模式）、`hook_<size>.dex`（hook 模式）。同一 size 只写一次（去重）。

---

## 构建与安装

### 工具链（已锁定，勿随意升级）

- Gradle 6.7.1、AGP 4.2.0、Kotlin 1.5.0、**JDK 8~14**（JDK 17+ 不支持，Gradle 6.7.1 会报 `Unsupported class file major version 61`，实测 JDK 11 可用）
- NDK 21、CMake 3.10
- Maven 仓库使用**阿里云镜像 + jitpack**（见根 `build.gradle`），不要换成 `google()`/`mavenCentral()` 默认源，否则依赖解析会失败
- 首次原生构建较慢：Dobby 从 `Bcore/src/main/cpp/Dobby` 源码编译
- `local.properties`（SDK 路径）是机器本地的，不要提交
- Windows 主机用 `gradlew.bat`

### 构建命令

```bash
# 构建 Helper（32 位辅助程序，需先构建并复制到 app/assets/）
gradlew.bat :helper:assembleRelease
# 复制 Helper APK 到 app/assets/helper32.apk
copy helper\build\outputs\apk\release\helper-release.apk app\src\main\assets\helper32.apk

# 构建 主 App（arm64-v8a，默认）
gradlew.bat assembleBlackDex64Release

# 构建 32 位 flavor（传统方式，不含 Helper）
gradlew.bat assembleBlackDex32Release
```

JDK 17+ 环境下需先切到 JDK 11：

```powershell
$env:JAVA_HOME="D:\JDK\jdk11"; .\gradlew.bat :helper:assembleRelease; .\gradlew.bat :app:assembleBlackDex64Release
```

### 模块结构（`settings.gradle`）

- `:app` - Android 应用，Kotlin，包名 `top.niunaijun.blackdex`。UI 与调度。入口 `WelcomeActivity -> MainActivity`。
- `:helper` - 32 位辅助程序，Kotlin，包名 `top.niunaijun.blackdex32helper`。无独立 UI（仅一个卸载入口 Activity），脱壳逻辑复用 `:Bcore`。编译为 release APK 后内嵌到 `:app` 的 assets 中。
- `:Bcore` - 核心引擎，Java + C++/NDK，包名 `top.niunaijun.blackbox`。脱壳逻辑、native 库 `blackdex`（CMake 构建，内含 Dobby）。
  - `:Bcore:black-hook` - JNI hook 原语，包名 `top.niunaijun.jnihook`
  - `:Bcore:black-fake` - fake framework，包名 `top.niunaijun.black_fake`
- 依赖链：`app -> Bcore -> {black-hook, black-fake}`，`helper -> Bcore`，`black-fake -> black-hook`

### 签名

主 App 和 Helper 必须使用相同的签名密钥（`keystore.properties`），Helper 的 `build.gradle` 引用 `app/keystore.properties`。相同签名用于：
- ContentProvider/Service 的签名级权限校验
- `PackageManager.checkSignatures()` 验证调用方身份

`app/build.gradle` 已配置 `signingConfigs.release` 从 `keystore.properties` 读取。

---

## 使用方法

### 64 位应用脱壳

1. 安装 BlackDex。
2. （Android 11+）授予「所有文件访问权限」。
3. 在主页列表里选中要脱壳的应用，或在右下角 FAB 选择本地 APK 文件。
4. 等待脱壳完成（弹窗提示成功/失败，及 dex 保存路径）。
5. 到输出目录查看 `cookie_*.dex` / `hook_*.dex`，用 jadx 等工具反编译。

### 32 位应用脱壳

1. 安装 BlackDex。
2. 进入设置 > 「32位兼容」分类：
   - 开启「启用32位兼容」
   - 点击「安装/更新辅助程序」安装 Helper
   - Helper 桌面图标可用于卸载
3. 返回主页，32 位应用会显示橙色「32位」标签。
4. 点击 32 位应用，主 App 自动启动 Helper 的脱壳流程，进度实时回传。
5. 脱壳完成后弹窗显示结果和保存路径。

### 双架构同时脱壳

1. 先完成上述 32 位兼容设置。
2. 在设置中开启「双架构同时脱壳」。
3. 同时包含 32/64 位的应用会显示「32/64位」标签。
4. 点击后，主 App（64 位）和 Helper（32 位）同时脱壳，结果分别保存到 `arm64/` 和 `arm32/` 子目录。

> 遇到脱不出的壳，可尝试组合：开启「Hook Dump」+「主动调用」；若怀疑加固把 dex magic 清零，再关闭「Verify Dex Before Dump」。

---

## 已知问题 / 不做修复

- 深度脱壳（fixCodeItem）在 Android 13+ 失效，未修复，用「主动调用」代替。
- 不做任何过检测、反反调试。
- 对加固强度较高 / 有环境检测的 app，可能直接失败或目标 app 崩溃。
- 没有真实测试套件（仅占位 `ExampleUnitTest`），验证方式 = 能构建、能安装、能在真机上 dump 出 dex。
- Helper 在部分设备上可能因 scoped storage 无法写入 `Download/dexDump/`，此时回退到自身 `Android/data/` 目录。

---

## 版本历史

### v3.3.1

- **修复重复 dump 相同目标脱不出 dex**：目标从已编译的 vdex/oat 加载时，dex 在内存中是 **CompactDex**（magic `cdex`）。原代码在 `OpenAll` 前无条件把 magic 覆盖成 `"dex\n035"`，破坏了 CompactDex 结构导致解析失败（`Unknown map section type f000`）。现在仅在原 magic 不是 CompactDex 时才修复 magic，`handleDumpByDexFile` 和 `cookieDumpDex` 均支持 CompactDex，dump 出的 `.dex` 文件保留 `cdex` magic（可用 jadx 或 `compact_dex_converter` 处理）
- **修复双架构脱壳后续只显示主架构进度条**：`dumpDex` 在 IO 线程运行，`postValue(LOADING)` 与 `isDualDumping = true` 之间存在竞态，主线程可能在标志位置位前创建单架构模式的进度对话框。现将 `isDualDumping = true` 提前到 LOADING 通知之前，保证进度对话框始终以双架构模式创建
- **修复 `ProgressDialog` 崩溃**：`show()` 是异步 commit，进度回调可能在 Fragment 尚未 attach 时访问 `viewBinding`（惰性 inflate 调用 `getLayoutInflater`）导致 `IllegalStateException`。所有 setter 在访问 `viewBinding` 前先判断 `isAdded`
- **修复 Helper 超时任务泄漏**：300s 超时协程改为可取消的 `Job`，dump 完成时取消，避免旧超时误注销后续 dump 的 status receiver
- **ARM32/64 完成状态回传**：Helper 完成广播现在也写入进度 LiveData，进度对话框能正确显示「✓ 成功」状态

### v3.3.0

- **新增 32 位兼容脱壳**：内嵌 32 位 Helper 辅助程序，无需单独安装 BlackDex32 即可脱壳 32 位应用
- **新增双架构同时脱壳**：目标同时包含 32/64 位时，两种架构并行脱壳，结果分别保存到 `arm64/` 和 `arm32/` 子目录
- **修复 `isRunning()` 进程名误匹配**：改为按包名前缀过滤，避免双架构场景下误检测对方进程
- **Helper 配置跨进程同步**：使用 SharedPreferences 持久化配置，确保 `:p0` 进程能读到主进程设置的 dumpDir 等值
- **Dump 目录自适应**：Helper 优先尝试写入共享 `Download/dexDump/`，不可写时回退到自身 `getExternalFilesDir`
- **跨 App 通信**：透明 Activity + Broadcast 广播，绕过 MIUI 的 `bindService` 限制

### v3.2.4

- 修复 Android 16 `isRunning()` 进程名匹配问题

### v3.2.3

- 修复 Android 16 `ProxyActivity` 装饰窗 `SecurityException` 崩溃
- 修复 cookie dump `SIGSEGV` 和错误 dex 匹配
- 修复 hook dump `LoadMethod` 在 Android 16 不导出
- 修复 `vm.jar` / 双 VMCore 类冲突
- 修复 `vm.apk` 缓存不更新（Android 14+ dex 验证）
- 新增「Verify Dex Before Dump」设置
- 新增中文本地化
- 新增下拉刷新
- 更新 README

### v3.2.2 及更早

- 原项目 [BlackDex](https://github.com/CodingGay/BlackDex) 代码

---

## 致谢

- 原项目 [BlackDex](https://github.com/CodingGay/BlackDex) 及其作者 CodingGay
- [Dobby](https://github.com/jmpews/Dobby)（内嵌于 `Bcore/src/main/cpp/Dobby`，从源码编译）
- [free_reflection](https://github.com/tiann/FreeReflection)、xhook 等

## 问题反馈

使用问题或 Bug 欢迎提 Issue。请附上 logcat（过滤 `VmCore`、`HelperManager`、`DumpLauncher` 标签）和设备信息（机型、Android 版本、目标 app 及加固类型）。
