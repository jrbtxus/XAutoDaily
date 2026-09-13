# XAutoDaily 交接文档：MSF 探针 + 好友续火花修复

> 分支：`mine/ntp-preheat`
> 涉及提交：
> - `5533287` feat: 增加 MSF 长连接探针（23:55:55 提前检测）
> - `e299a89` fix: 修复好友续火花 NPE（NT 发送消息方法查找走接口）
> - `0f835a8` / `e299a89` 附带：CI 工作流同时编译 debug + release

---

## 一、背景：两个待解决的问题

### 问题 A：午夜协议类任务全部超时，重启 QQ 才恢复

现象（来自用户 09-06 → 09-13 的完整日志）：

- 每天 00:00 起，**协议类任务**（群打卡 `OidbSvc.0xeb7`、好友点赞 `VisitorSvc.ReqFavorite`）全部无响应，一直超时；
- 同一时段 **HTTP 类任务**（QQ 晚安卡、QQ 收集卡、fetch meta）完全正常；
- 直到用户手动重启 QQ（约 01:12）才恢复，重启后群打卡 `成功4个，失败0个`。

根因：**不是时间/NTP 问题**。是 QQ 的 **MSF 长连接「黑洞」**——网络/VPN 切换后 TCP socket 半开但 QQ 不感知，MSF 协议请求发出去收不到响应；HTTP 每次新建连接所以不受影响。用户也确认过：VPN 建立后启动它的 app（如浏览器）关闭时，QQ 会网络不可达，重启才恢复。

### 问题 B：好友续火花任务一直「执行失败次数过多」

现象：续火花任务 17 次执行 **全部 `NullPointerException`、0 次成功**，NPE 位置全在
`NtSendMessageManager.getMsgUniqueId(NtSendMessageManager.kt:91)`。

历史：用户分支最初提交「预热功能管理器」（`678bd2f`）引入了两处问题，之前只修了一半：

1. **已修**：preWarm 最初版本遍历所有 manager 强制 `isInit`，在 NT QQ 上误 init 了
   非 NT 版 `SendMessageManager` → 反射失败 → 弹「初始化 SendMessageManager 失败」。
   （`HANDOVER.md` 第五节记录过，改成了循环里跳过、只 init 当前版本对应的那个）
2. **未修（本次修）**：`NtSendMessageManager.init()` 用 hutool 找方法，但封装把
   `withSuperInterface` 写死为 false，**永不遍历接口**，导致接口上的方法查不到 →
   init 静默成功 → 运行时 NPE。

---

## 二、改动内容

### 改动 1：MSF 长连接探针（新增 `utils/MsfProbe.kt`）

- 每天 **23:55:55**（cron `"55 55 23 * * *"`，调度器已开秒匹配）用一次无害点赞请求
  探测 MSF 是否真的能收到响应；
- 若超时（`TaskTimeoutException`）则弹通知 + Toast 提醒用户重启 QQ；
- **不做自动重连**（重连要碰 QQ 内部接口，超出范围）。

关键设计（为什么这么做）：

- **独立调用** `FunctionPool.favoriteManager.syncFavorite(uin, 1)`，**不经过任务框架**，
  因此不写 `lastExecTime` / `nextShouldExecTime`，不会把 00:00 的正经任务挤掉；
- 目标 uin 取 `QApplicationUtil.currentUin`（账号自身），`count = 1`，**不打扰好友**；
- **只判断「有没有响应」**，不看业务返回码：`syncFavorite` 只看 MSF 传输层
  `fromServiceMsg.isSuccess`（`failCode == 1000`），即使服务端返回「当天已点过」，
  只要有响应就判定正常，**不会误报**。

注册点：`TaskExecutor.startCorn()` 末尾 `MsfProbe.schedule()`。

### 改动 2：修复续火花 NPE（`NtSendMessageManager.kt`）

根因定位：

- `ReflectUtil.getMethods(withSuper)` 封装是
  `ReflectUtil.getMethodsDirectly(clazz, withSuper, false)`——hutool 第三参
  `withSuperInterface` 被写死 `false`，**永不遍历接口**；
- QQ NT kernel 的 `msgService`（`IKernelMsgService` 的 JNI 实现）把
  `generateMsgUniqueId` / `getMsgUniqueId` / `sendMsg` 声明在**接口**上；
- 所以 `getMethods(false)` / `getMethods(true)` 都查不到 → `init()` 静默成功 →
  运行时 `getMsgUniqueIdMethod!!` 空指针。

修复：

- 方法查找改用**原生 `Class.getMethods()`**（`msgService.javaClass.methods`，含接口继承）；
- 兼容 primitive 签名（`Long`/`long`、`Int`/`int`）；
- **一并修 `sendMsg`**：原来 `msgService.invoke("sendMsg", ...)` 也是 hutool invoke，
  同样找不到接口方法会静默失败、白等 10 秒；改为用查到的 `sendMsgMethod` 反射调用；
- 找不到方法时**显式抛异常并打印方法列表**，便于后续定位真实签名。

### 改动 3：CI 工作流（`.github/workflows/build-apk.yml`）

- 从只编译 `assembleDebug`，改为 `assembleDebug + assembleRelease`；
- 产物拆成**两个独立 artifact**：`XAutoDaily-debug-<sha>`、`XAutoDaily-release-<sha>`；
- 触发方式不变：推送到 `mine/**` 分支或手动 `workflow_dispatch`。

---

## 三、遇到的问题与坑

| 问题 | 说明 / 解决 |
|------|------|
| **MSF 根因误判（时间理论）** | 最初怀疑是 NTP 偏移导致提前触发 / 服务端拒绝。被日志推翻：offset 只有 +263ms/+76ms，HTTP 任务午夜成功，只有 MSF 请求超时。**结论：不是时间问题，是 MSF 长连接黑洞**。 |
| **探针不能走任务框架** | 若经 `TaskUtil.execute`，会写 `lastExecTime` / 推进 `nextShouldExecTime`，导致 00:00 正任务被 `checkExecuteTask` 判为「已执行」而跳过。故探针**直接调用** `syncFavorite`，绕过框架。 |
| **HTTP 任务不能当探针** | 晚安卡/收集卡是 HTTP（ti.qq.com），午夜 MSF 挂时它们照常成功，测不出问题。故选**协议类**的「好友点赞」当探针。 |
| **「当天已点过」是否误报** | 已确认不会：`syncFavorite` 只查传输层 `isSuccess`，不解析业务 body。日志里 09-08/09-11 两天点赞都返回 `failCode:1000` + 成功，即使已点过。 |
| **接口方法反射查不到（核心坑）** | `ReflectUtil.getMethods` 封装把 hutool `getMethodsDirectly` 的 `withSuperInterface` 写死 false，导致 QQ NT 接口方法查不到。必须用原生 `Class.getMethods()`。 |
| **无法核对 QQ 真实方法签名** | 用户禁止扩大范围（不 decompile QQ、不查 MSF 内部）。修复是依据 hutool 封装行为 + 日志推理的。为此在找不到方法时打印 `msgService` 全部方法列表，留了诊断后路。 |
| **无 release keystore** | 仓库无 `signing.properties`，release 变体回退 **debug 签名**（`CN=Android Debug`，SHA-256 `fab32e52…`）。好处：和手机上现有 debug 版签名一致，**可覆盖安装不丢配置**。 |
| **fetch GitHub raw 被拦** | 会话里 `web_fetch` / `fetchWebContent` 对 `raw.githubusercontent.com` 报「解析到非公网 IP/私有网段」，无法在线查上游；改用本地 `git show master:` 确认上游同源 bug。 |

---

## 四、验证结果

- 构建：GitHub Actions run `34751530405`（`assembleDebug` + `assembleRelease`）success。
- 反编译 release 确认修复已打包：`Method[] methods = H0.getClass().getMethods();`。
- 产物签名：均为 `CN=Android Debug`，SHA-256 `fab32e5201c538b2a9b1526dacb010d6b0e616bf90a9ba53f8c815d7f19ae278`。
- 版本：`versionCode 26091310`，`versionName 3.0.37`（release）/ `3.0.37.091310-debug`（debug）。

| APK | 大小 | SHA-256 |
|-----|------|---------|
| release | 7.6M | `12f6ff23fd7234708b072bd52f39c173431d28a53f317252c600b959f36c751b` |
| debug | 17M | `f601a31ba151a376db08d7936ba2e9f9a04f3e53c15d95bb050ead43a70b642e` |

---

## 五、待验证 / 待办

1. **续火花**：中午 12:00 观察是否还 NPE。若仍失败，看日志里
   `NtSendMessageManager init 失败：未找到 … msgService 方法列表` 段，把真实方法签名发回
   （QQ 可能改了方法名，`generateMsgUniqueId` 未必还存在）。
2. **MSF 探针**：今晚 23:55:55 观察。正常应出现日志 `MSF 探活正常`；异常弹通知。
   - 一个无法在本环境验证的点：**点自己**（`targetUin = currentUin`）服务端是否返回响应。
     逻辑上 MSF 是请求-响应协议、总会回包；若发现每晚 23:55:55 都误报「异常」而实际
     签到正常，说明点自己没回包，把 `MsfProbe.probe()` 里的 `currentUin` 换成好友 uin
     （如日志里的 `2161969949`）重编一次。
3. **探针的「异常时怎么处理」**：当前只提醒，不自动重连。若日后要做自动重连，需评估
   是否触碰 QQ 内部 API（当前因「禁止扩大范围」未做）。

---

## 六、其它说明

- 本仓库是 fork，`origin = github.com/jrbtxus/XAutoDaily`，无 upstream 远程；`master` 停留在
  上游 base `7bdcceb`（`Tinker 支持`），且 `master` 的 `NtSendMessageManager` 与本分支一致
  ——即**续火花 NPE 是上游原版就有的**，不是分支独有的新引入 bug。
- 本次真实代码改动文件：`MsfProbe.kt`（新增）、`TaskExecutor.kt`、`NtSendMessageManager.kt`、
  `.github/workflows/build-apk.yml`。
