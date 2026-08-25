# XAutoDaily 抢打卡延迟优化 — 交接文档

## 一、需求 / 目标

群打卡（群组打卡）在定时时间（目标 00:00:00）到点后，要「**一秒内**」发出打卡请求抢排名。
现状问题：到点 → 执行成功有 **6~7 秒延迟**，抢不过别人。

---

## 二、代码改动（做了什么）

### 改动 1：`task.delay` 改为「相邻请求之间」的间隔
- 文件：`app/src/main/java/me/teble/xposed/autodaily/task/util/TaskUtil.kt`
- 原逻辑：每个请求前都 `Thread.sleep(task.delay * 1000)`，**包括第一个请求**，导致首包被 delay 拖慢。
- 改后：加 `firstRequest` 标志，**首个请求立即执行**，delay 只在相邻两次请求之间生效。

### 改动 2：预热功能管理器（FunctionPool.preWarm）
- 文件：`app/src/main/java/me/teble/xposed/autodaily/hook/function/proxy/FunctionPool.kt`
  - 新增 `preWarm()`：遍历管理器触发 `isInit`，提前跑完 ByteBuddy 子类化 + 各 `init()` 反射。
  - **注意**：发送消息管理器分 **NT / 非NT 两个版本**（`NtSendMessageManager` / `SendMessageManager`），循环里**跳过这俩**，改为 `sendMessageManager.isInit` **只预热当前 QQ 版本对应的那个**（`sendMessageManager` 内部按 `isNtQQ()` 选择），避免误初始化不匹配版本弹「初始化失败」。
- 文件：`app/src/main/java/me/teble/xposed/autodaily/hook/SplashActivityHook.kt`
  - splash `doOnCreate` 时在后台线程调用 `FunctionPool.preWarm()`。

> 原因：管理器是 `by lazy` 的，首次使用才做 ByteBuddy 子类化 + 反射找方法，午夜首次使用耗时 **~2 秒**。

### 改动 3：时间源改为 NTP（优先腾讯）
- 文件：`app/src/main/java/me/teble/xposed/autodaily/utils/TimeUtil.kt`
- 原逻辑：向 `http://www.baidu.com` 发 HTTP 读 `Date` 头 +500ms。
- 改后：标准 SNTP 协议（UDP 123 端口），服务器列表**腾讯优先**、失败回退阿里云/公共池；用标准 NTP 偏移公式 `((t1-t0)+(t2-t3))/2` 校正，去掉硬编码 +500ms。

### 改动 4：「首次执行补偿」加 1 分钟临近阈值（避免午夜前提前触发）
- 文件：`app/src/main/java/me/teble/xposed/autodaily/task/util/ConfigUtil.kt`
- 「首次执行补偿」保留（补跑今天漏跑的），但加临近阈值 `IMMINENT_TRIGGER_MS = 60 * 1000L`（1 分钟）：距下次执行时间 < 1 分钟时不补跑，等真正的 cron 时刻。
- 效果：0 点任务在午夜前（23:59）启动时，不会在 23:59:59 提前触发，而是等 `task_timer` 在 00:00:00 触发；其它时间启动仍正常补跑。

---

## 三、根因分析（发现了什么）

### 6~7 秒延迟的构成（来自实测日志）
| 因素 | 耗时 |
|------|------|
| 管理器懒初始化（TicketManager ~1.05s + GroupSignInManager ~0.97s + FavoriteManager） | ~2s |
| `task.delay`（3 个群之间各 2s） | ~4s |
| 服务器往返（实测） | ~124~370ms |

**结论：服务器根本不慢，6~7 秒全是本地「懒初始化 + delay」造成的。**

### 为什么任务在 23:59:59 就触发（而不是 0:00）—— 已修复
- **不是** `task_timer`（它只在第 0 秒触发，不可能在 23:59:59）。
- 真正触发链：`SplashActivityHook` 在 splash 后 `handler.sendEmptyMessageDelayed(AUTO_EXEC, 10_000)` → `checkExecuteTask` 里的「**首次执行补偿**」逻辑（`lastExecTime` 为空且下次执行时间不在当天 → 立即执行）。
- **已修复**：改动 4 给「首次执行补偿」加 1 分钟临近阈值后，0 点任务在午夜前启动不再提前触发，而是等 `task_timer` 在 00:00:00 触发。

### 其它发现（隐患，未处理）
1. `GroupSignInManager.syncSignIn` 返回 `parseOIDBPkg == 0`（**oidb 传输层成功**），读了 `respBody.signInWriteRsp.ret.code`（**业务结果**）却**没判断** → 业务失败（未到时间/重复打卡）也报「执行成功」。
2. **没有失败即重试机制**：发一次就等回包，失败（网络/超时）只打日志「等待重试」，要等下一次触发（10 分钟后的 `task_timer` 或下次 app 启动的 `AUTO_EXEC`）才重跑。`repeat>1` 是「发 N 次」，不是「失败重试」。

### 时间来源（`utils/TimeUtil.kt`）
- `TimeUtil.init()` 通过 SNTP（UDP 123）向 NTP 服务器校时，**优先腾讯**（`time1~5.cloud.tencent.com`、`ntp.tencent.com`、`ntp1~5.tencent.com`），失败回退阿里云/公共池。
- 用标准 NTP 偏移公式 `((t1-t0)+(t2-t3))/2` 算 `diff` 存 MMKV。
- 之后 `cnTimeMillis() = 设备时钟 + diff`（跟随设备时钟漂移）。
- cron 匹配用 `GMT+8` 时区 + `cnTimeMillis()`。

---

## 四、构建环境改动（本机）

原 U 盘满（0GB），编译时「磁盘空间不足」多次失败，做了以下迁移：

1. **Android SDK** 从 `U:\...\Android\Sdk` 迁到 **`D:\Android\Sdk`**。
2. 新增 **`local.properties`**（git 已忽略），内容 `sdk.dir=D:/Android/Sdk`。
3. 补齐组件：NDK `27.0.12077973`、CMake `3.22.1`、build-tools `33.0.1` + `30.0.3`、platform `android-32`。
4. 工具链：JDK 18（`U:\Program Files\Java\jdk-18.0.1.1`）+ Gradle 7.5.1（验证可用）。

编译命令：
```powershell
$env:JAVA_HOME = "U:\Program Files\Java\jdk-18.0.1.1"
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

---

## 五、遇到的问题与解决

| 问题 | 解决 |
|------|------|
| U 盘 0GB，NDK/CMake 装不下、构建日志写不进去 | SDK 迁到 D 盘，local.properties 指向 D |
| AGP 自动装 SDK 组件报 `Failed to delete .temp\...` | 手动下载 zip 解压安装 |
| `tar` 解压后 `Move-Item` 报 `Access denied` | 改用 `robocopy /E /MOVE` |
| build-tools 30.0.3 下载 URL 404（需带 SHA1 前缀） | 用 `91936d4ee...build-tools_r30.0.3-windows.zip` |
| CMake 3.22.1 URL 写错（返回 HTML 错误页） | 正确 URL 是 `cmake-3.22.1-windows.zip`（非 `-windows-x86_64.zip`） |
| 预热改动后进软件弹「初始化 SendMessageManager 失败」 | `preWarm()` 误把 NT/非NT 两个发送消息管理器都 init，不匹配版本抛异常被 `BaseFunction.isInit` 弹窗；改为循环里跳过、只 `sendMessageManager.isInit` 预热当前版本对应的那个 |

---

## 六、验证 / 构建结果

- `assembleDebug` 构建成功（首次 8m23s，增量 23s）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可装手机验证）。
- 预热效果已在实测日志验证：管理器初始化日志消失，群打卡单次往返 **~124~370ms**。

---

## 七、未完成 / 待办

1. **`syncSignIn` 不判断 `ret.code`**：业务失败会被误报「执行成功」，建议补上 `ret.code == 0` 判断。
2. **失败重试机制**：当前无「失败即重试」，建议评估是否需要。
3. `task.delay`：用户已决定**不改**（避免触发 QQ 风控），保持 delay=2。

> 已解决：①「首次执行补偿」提前触发 → 加 1min 临近阈值（改动 4）；②时间源 +500ms 偏移 → 已改 NTP（改动 3）。

---

## 八、其它说明

- 本仓库是 fork，`origin` = `github.com/jrbtxus/XAutoDaily`，**没有 upstream 远程**，同步/推送只到自己的 fork，不会影响主仓库（teble/XAutoDaily）。
- `local.properties` 已被 `.gitignore` 忽略；本次真实代码改动共 5 个文件：`TaskUtil.kt`、`FunctionPool.kt`、`SplashActivityHook.kt`、`TimeUtil.kt`、`ConfigUtil.kt`。
