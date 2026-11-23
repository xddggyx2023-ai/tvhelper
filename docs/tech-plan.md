# 兔子电视助手 — 技术方案 v1（待审核）

> 状态：草案，未开始编码。
> 日期：2026-11-23
> 对应产品名：兔子电视助手 / 包名 `com.tztvhelper.app`

---

## 0. 一页纸结论

- 产品骨架：**AI/知识库带路 → 用户在电视上手动开 ADB → APP 检测 → 连接 → 管理**。
- ADB 开启之前，APP 不做任何"操作电视"的事，只做识别、查询、讲解、引导。这一点贯穿全部设计。
- 四个独立模块：**设备识别**、**知识库/教程**、**ADB 连接**、**电视管理**，互相不依赖内部实现，靠接口通信。
- 不写死的部分：教程、型号库、AI 知识全部是外部数据（内置基线 + 联网增量），APP 只做解析和渲染。
- 预留但不实现：激活码校验、会员权限、在线更新、云端大模型。全部走"接口 + 空实现 + 可配置服务器地址"。
- 没有一处在 APK 里放密钥。

---

## 1. 产品流程（状态机）

```
启动
 └─ 有设备档案？ ──是──> 直接进「连接」页
        │否
        ▼
 [S1 建档] AI 引导：怎么在电视上找到品牌/型号/系统版本
        │  （不知道型号 → 教看设置里的设备信息 → 教看背面标签）
        ▼
 [S2 匹配] 查知识库：精确型号 → 同型号不同版本 → 品牌通用 → 未收录
        ▼
 [S3 教程] 分步卡片：菜单路径 + 截图 + 注意事项 + 风险提示
        │  用户在电视上手动操作
        ▼
 [S4 等待] "开好了，开始检测"（用户主动点，不自动跳）
        ▼
 [S5 检测] mDNS 发现 → 5555 探测 → 无线调试配对 → 手动 IP
        ├─ 成功 → [S6 连接] 读系统属性校正档案 → 进入管理
        └─ 失败 → [S7 排查] 排查清单 → 「全端口扫描」高级诊断
```

关键约束：

1. **S4 必须由用户触发**。APP 不能在用户看教程时后台狂扫网络。
2. **S6 连上后要用 `getprop` 读到的真实品牌/型号/版本回写档案**，覆盖用户手填的猜测值，并标记"已核实"。
3. 每一步都能"返回上一步"，档案随时可改。

---

## 2. 模块划分

```
com.tztvhelper.app
├─ app/                      Application、手工依赖装配、导航图
├─ ui/                       只放界面，不直接调 ADB
│   ├─ onboarding/           欢迎、建档引导
│   ├─ identify/             品牌型号选择、AI 引导问答、标签拍照入口
│   ├─ guide/                教程分步卡片
│   ├─ connect/              检测、配对、连接、手动 IP
│   ├─ diagnose/             排查清单、全端口扫描
│   ├─ device/               概览、应用管理、电视控制
│   ├─ tools/                APK 扫描、诊断报告
│   ├─ assistant/            AI 助手（文字/语音）
│   ├─ settings/             档案、更新、激活码、关于
│   └─ common/               组件、主题
├─ domain/                   用例层：把流程串起来
│   ├─ IdentifyDeviceUseCase
│   ├─ FindTutorialUseCase
│   ├─ ConnectDeviceUseCase
│   ├─ DiagnoseConnectionUseCase
│   ├─ ManagePackagesUseCase
│   └─ AskAssistantUseCase
├─ core/
│   ├─ adb/                  AdbGateway 实现（Kadb 封装）
│   ├─ discovery/            局域网扫描、mDNS、配对发现、端口扫描
│   ├─ identify/             型号归一化、匹配算法
│   ├─ security/             应用分类器、危险操作守卫
│   ├─ apkscan/              本地 APK 解析
│   └─ assistant/            本地规则引擎
└─ data/
    ├─ kb/                   知识库（assets + 可下载覆盖层）
    ├─ profile/              设备档案存储
    ├─ license/              激活码/权益（接口 + 空实现）
    ├─ update/               更新客户端（接口 + 空实现）
    └─ settings/             偏好设置
```

**依赖方向必须单向**：`ui → domain → core/data`，`core` 不认识 `ui`，`data` 不认识 `domain`。
理由：以后接服务器、换 ADB 库、换知识库格式，只改一层，不会牵连界面。

---

## 3. 数据结构

### 3.1 品牌库 `brands.json`

```json
{
  "schema": 1,
  "updatedAt": "2026-11-23",
  "brands": [
    {
      "id": "xiaomi",
      "name": "小米",
      "aliases": ["Xiaomi", "MI", "Redmi", "米家"],
      "deviceNameHints": ["MiTV", "Mi TV", "Redmi TV"],
      "genericTutorialId": "tut-xiaomi-generic",
      "riskNotes": "部分型号开启 ADB 后需重启才生效"
    }
  ]
}
```

`deviceNameHints` 用于网络发现时把"网络上自报的名字"映射到品牌，例如 SSDP 返回 `MiTV-4A` → 小米。

### 3.2 型号库 `models.json`

```json
{
  "schema": 1,
  "models": [
    {
      "id": "mi-tv-4a-32",
      "brandId": "xiaomi",
      "model": "L32M5-AD",
      "displayName": "小米电视4A 32英寸",
      "aliases": ["MiTV4A", "小米电视4A"],
      "releaseYear": 2018,
      "androidVersions": ["8.1"],
      "adbPath": "设置 → 账号与安全 → ADB 调试",
      "tutorialId": "tut-xiaomi-4a",
      "verified": false,
      "confidence": "medium"
    }
  ]
}
```

`verified` = 是否在真机上核对过；`confidence` = 资料来源可信度。两者都直接显示给用户，不隐瞒。

### 3.3 教程 `tutorials/tut-xxx.json`

```json
{
  "id": "tut-xiaomi-generic",
  "scope": "brand",
  "brandId": "xiaomi",
  "title": "小米/Redmi 电视开启 ADB",
  "appliesTo": { "androidMin": 6, "androidMax": 14, "models": ["*"] },
  "risk": "low",
  "estMinutes": 3,
  "steps": [
    {
      "n": 1,
      "title": "回到电视主页",
      "detail": "按遥控器上的「主页」键，找到右上角的齿轮图标。",
      "path": ["设置"],
      "image": "xiaomi/generic/step1.webp",
      "note": "有些型号齿轮在左侧菜单最下面"
    },
    {
      "n": 2,
      "title": "打开开发者选项",
      "detail": "设置 → 关于 → 找到「版本号」，连续按确认键 7 次，提示“已进入开发者模式”。",
      "path": ["设置", "关于", "版本号"]
    },
    {
      "n": 3,
      "title": "开启 ADB 调试",
      "detail": "返回设置 → 账号与安全 → ADB 调试 → 选择「开启」。",
      "path": ["设置", "账号与安全", "ADB 调试"],
      "note": "开启后电视可能会自动重启一次，属正常现象"
    }
  ],
  "afterEnable": {
    "expect": "电视屏幕出现「允许 USB 调试吗」弹窗",
    "likelyPorts": [5555],
    "needsPairing": false
  },
  "sources": [
    { "type": "forum", "url": "https://example.com/x", "collectedAt": "2026-11-20" }
  ],
  "updatedAt": "2026-11-20",
  "reviewed": false
}
```

教程字段是"可执行说明书"，不是文章：每步都有可对齐的**菜单路径**和**预期画面**，这是后面 AI 追问"你看到的是哪个界面"的依据。

**教程图片从哪来（版权与准确性）**

网上教程里的电视截图**不能直接截下来用**。三个原因：

1. **著作权**：那是拍摄者/作者的作品，商用产品里使用属于侵权，被人投诉要下架整改。
2. **准确性**：同一型号不同批次、不同固件的菜单都可能变。抄来的图和你用户的电视对不上，比没有图更糟——用户会照着找不到的菜单乱点。
3. **质量**：分辨率参差、带别人的水印和广告，一眼看出是拼凑的。

三种可行来源，可混用：

| 来源 | 版权 | 准确性 | 成本 |
|---|---|---|---|
| 我用矢量图画"界面示意"（灰色菜单框 + 高亮"设置→关于"） | 干净，自己画的 | 中，能表达路径 | 低，可批量 |
| 你/测试用户实拍电视屏幕 | 干净，自己拍的 | 高 | 需要有人对着电视拍 |
| 公开许可素材（CC0/CC-BY，注明来源） | 干净但需署名 | 低 | 低，但要逐个核对 |

**第一版落地方式**：所有教程先配**示意图**（保证每步都有东西看），字段 `imageKind: "diagram" | "photo"`，只有实拍过的才标 `photo` + `verified: true`。以后你拍了真图，替换文件即可，不用改代码、不用发新版 APP（走知识库更新通道）。

### 3.4 设备档案（本地，不上传）

```json
{
  "id": "uuid",
  "brandId": "xiaomi",
  "brandName": "小米",
  "model": "L32M5-AD",
  "modelId": "mi-tv-4a-32",
  "androidVersion": "8.1",
  "source": "user|network|adb",
  "verifiedByAdb": false,
  "lastHost": "192.168.1.23",
  "lastPort": 5555,
  "notes": "",
  "createdAt": 1763900000000
}
```

`source` 三档：用户手填 / 网络发现猜的 / ADB 读到的。界面用不同标记显示，用户一眼能看出哪些是猜的。

### 3.5 收费桶（已并入 9.2 的策略文档）

早期草案里单独有一份 `features.json` 管分档。最终定案：**不设两份数据**，功能分档、限时免费、宽限天数、最低版本统一放在策略文档 `policy.json` 里（见第 9 节）。

对应关系：

| 早期草案字段 | 最终位置 |
|---|---|
| `features[].tier` | `policy.features[feature] = "free" \| "paid"` |
| `features[].enabled` | `policy.features[feature] = "disabled"`（应急关闭） |
| `freezeUntil` | `policy.promo.active` + `policy.promo.until` |
| `tiers` 价格表 | `policy.plans[]` |

好处：服务器下发**一份** JSON 就能同时控制"谁免费、谁收费、免费到什么时候、太老的版本要不要提示升级"，不用维护两套互相对不上的规则。

### 3.6 知识库清单 `kb-manifest.json`

```json
{
  "schema": 1,
  "kbVersion": "2026.11.1",
  "minAppVersionCode": 1,
  "files": [
    { "path": "brands.json", "sha256": "…", "size": 12031 },
    { "path": "models.json", "sha256": "…", "size": 402113 },
    { "path": "tutorials/tut-xiaomi-generic.json", "sha256": "…", "size": 2211 }
  ],
  "signature": "ed25519:…"
}
```

---

## 4. 接口设计（Kotlin，只有签名，不含实现）

```kotlin
// domain
interface IdentifyDeviceUseCase {
    suspend fun suggestFromNetwork(): List<DeviceCandidate>   // 只猜，不断定
    suspend fun searchBrands(query: String): List<Brand>
    suspend fun searchModels(brandId: String, query: String): List<TvModel>
    suspend fun save(profile: DeviceProfile)
}

interface FindTutorialUseCase {
    suspend fun match(profile: DeviceProfile): MatchResult
}

sealed interface MatchResult {
    data class Exact(val tutorial: Tutorial) : MatchResult        // 型号+版本命中
    data class ModelOnly(val tutorial: Tutorial) : MatchResult    // 只有型号
    data class BrandGeneric(val tutorial: Tutorial, val reason: String) : MatchResult
    data class NotFound(val brandName: String?, val feedbackId: String) : MatchResult
}

// core.adb
interface AdbGateway {
    suspend fun connect(target: Endpoint, auth: AuthMode, onEvent: (ConnectEvent) -> Unit): Result<AdbSession>
    suspend fun disconnect()
    fun isConnected(): Boolean
}

data class Endpoint(val host: String, val port: Int)
sealed interface AuthMode {
    data object LegacyTcp : AuthMode                                  // 老式 5555 + 电视弹窗授权
    data class WirelessPairing(val code: String, val pairPort: Int) : AuthMode  // Android 11+
}

interface AdbSession {
    suspend fun shell(cmd: String): ShellResult
    suspend fun install(apk: ApkSource): InstallResult
    suspend fun close()
}

// core.discovery
interface LanDiscovery { suspend fun findAdbEndpoints(): List<Endpoint> }
interface MdnsDiscovery { suspend fun findAdbServices(): List<MdnsService> }
interface PortScanner {
    suspend fun scan(host: String, onProgress: (Progress) -> Unit, cancel: CancelSignal): PortScanReport
}

data class PortScanReport(
    val open: List<OpenPort>,
    val closedCount: Int,        // 明确拒绝
    val filteredCount: Int,      // 超时，无法判断
    val unreachable: Boolean,    // 主机不可达
    val aborted: Boolean
)

data class OpenPort(val port: Int, val service: ServiceGuess?, val evidence: String)

// core.security
interface PackageClassifier {
    suspend fun classify(pkg: String): PackageClass
}

sealed interface PackageClass {
    data class ThirdParty(val evidence: List<String>) : PackageClass
    data class SystemApp(val evidence: List<String>) : PackageClass
    data class Critical(val role: String, val evidence: List<String>) : PackageClass
    data class Unknown(val reason: String) : PackageClass   // 一律禁止卸载
}

// data.license（第一版全部空实现）
interface LicenseService {
    suspend fun activate(code: String, deviceId: String): Result<LicenseToken>
    suspend fun refresh(): Result<LicenseToken>
    fun cached(): LicenseToken?
    fun isAllowed(feature: Feature): Boolean
}

interface FeatureGate { fun features(): Set<Feature> }

// data.update（第一版只留接口）
interface UpdateService {
    suspend fun check(channel: String): Result<UpdateManifest>
    suspend fun download(asset: UpdateAsset, onProgress: (Long, Long) -> Unit): Result<File>
}

// core.assistant
interface AssistantEngine {
    suspend fun ask(question: String, ctx: AssistantContext): AssistantAnswer
}

data class AssistantContext(
    val profile: DeviceProfile?,
    val connectionState: String,
    val lastError: String?,
    val currentScreen: String
)
```

**AI 输出约束**：`AssistantAnswer` 只允许两种形态——引用知识库步骤（带 tutorialId + step 号）或明确说"不知道"。不允许输出可直接执行的 shell 命令。

---

## 5. 应用分类（"第三方 vs 系统"可靠判定）

不靠包名关键词。三路证据交叉：

| 证据 | 命令 | 含义 |
|---|---|---|
| 分区路径 | `pm list packages -f` | `/system`、`/system_ext`、`/product`、`/vendor`、`/odm`、`/cust` 下的包 = 系统 |
| 系统包集合 | `pm list packages -s` | 系统分区安装的包 |
| 第三方集合 | `pm list packages -3` | 数据分区安装的包 |
| 包标志 | `dumpsys package <pkg>` | `flags=[SYSTEM]` 或 `UPDATED_SYSTEM_APP` = 系统 |
| 安装来源 | 同上 `installerPackageName` | 厂商应用商店/预装脚本装的 = 可疑 |
| 角色 | `cmd package resolve-activity -c android.intent.category.HOME` / IME / SystemUI | 桌面、输入法、系统界面 = 关键应用 |

判定顺序：

1. 路径在系统分区，或属于 `-s` 集合 → **SYSTEM**（禁止卸载、禁止禁用）
2. 是桌面/输入法/SystemUI/设置 → **CRITICAL**（连信息页的开关都不给）
3. 路径在 `/data/app` 且属于 `-3` 集合 → 再查 `dumpsys` 标志：
   - 有 `SYSTEM`/`UPDATED_SYSTEM_APP` → SYSTEM
   - `installerPackageName` 为空（用 adb 直接装的）或为常见应用商店 → **THIRD_PARTY**
   - 安装时间与系统首次开机时间接近（≤ 10 分钟）且来源不明 → **UNKNOWN**
4. 其余 → **UNKNOWN**（一律禁止卸载）

**性能处理**：`-f`/`-s`/`-3` 三条命令一次拿全，列表页只显示路径推断结果；点进某个应用详情时才跑 `dumpsys` 精判并缓存。避免 200 个包 × 200ms 把列表卡住。

**卸载规则**：只有 `THIRD_PARTY` 出现卸载按钮，点击后弹窗必须写清"卸载后电视上的这个应用就没了，电视自带的应用市场里可能搜不回来"。`UNKNOWN` 页面只显示"这是系统应用或无法确认来源，不能卸载"。

---

## 6. ADB 连接架构

三路并行，按成功率排序：

1. **mDNS 发现**（新电视）：`_adb-tls-connect._tcp`（Android 11+ 无线调试）、`_adb._tcp`（老式）。
2. **5555 扫描**（老电视）：现有 `Net.kt` 的扫描升级为可配置端口 + 可取消，扫到后先做 ADB 握手确认，不是"端口开着就算电视"。
3. **无线调试配对**（Android 11+）：`_adb-tls-pairing._tcp` 发现配对端口，用户在电视上读出 6 位配对码，APP 输入 → `Kadb.pair()`。
4. **手动 IP**：兜底，用户从电视"关于"里抄 IP。

已核实：当前使用的 Kadb 2.1.1 自带 `Kadb.pair(host, port, pairingCode, name)` 和独立的 `kadb-mdns` 模块（基于系统 NsdManager），**不需要换库、不需要自己实现配对协议**。这会多引一个依赖 `com.flyfishxu:kadb-mdns:2.1.1`，编译时验证是否与现有版本对齐。

连接成功后的固定动作：

```
getprop ro.product.brand / model / manufacturer
getprop ro.build.version.release / sdk
getprop ro.product.cpu.abi
```
读到的值回写档案，`source` 改成 `adb`，`verifiedByAdb = true`。

---

## 7. 全端口扫描（高级诊断）

**入口**：只出现在"连接失败"页面，按钮文字「全端口扫描」。首页、设置页都不出现。界面不写 1–65535 这种字样。

**执行**：TCP connect 扫描，并发 128，单端口超时 300ms，跳过本机与网关，可随时中断。

**结果必须分四类，不能合并成"失败"**：

| 结果 | 判定依据 | 对用户的说法 |
|---|---|---|
| 开放 | TCP 握手成功 | 端口 N 开放，可能在跑 X |
| 关闭 | 立刻收到 RST（`Connection refused`） | 该端口没有服务 |
| 被过滤/无响应 | 连接超时 | 无法判断，可能是电视防火墙、路由器隔离或电视休眠 |
| 主机不可达 | 完全无响应/ARP 失败 | 电视可能不在线，或不在同一 Wi-Fi |

**服务识别**：只用低风险手段——端口号常识映射（5555=ADB、8009=Chromecast、8080=HTTP）、HTTP HEAD 探测、ADB 握手包（发 `CNXN` 看是否回 `AUTH`/`CNXN`）。识别不出就写"未知服务"，不猜。

**结论文案模板**：
- 找到 5555 且 ADB 握手通过 → "检测到 ADB 服务，但刚才连接失败，可能是电视端授权弹窗没点允许"
- 有开放端口但都不是 ADB → "电视在线，但没检测到 ADB 服务，说明 ADB 可能没真正打开"
- 全部超时 → "扫描没有得到任何回应，无法判断 ADB 是否开启（可能是网络隔离）"

---

## 8. 知识库更新机制

**三层结构**：

1. **内置基线**：打包在 `assets/kb/`，装完就能用，断网可用。
2. **增量覆盖**：下载到 `files/kb/<version>/`，启动时优先读覆盖层。
3. **版本切换**：读取指针存 SharedPreferences，新版本校验通过才切；解析失败自动回退到上一版，再失败回退到内置基线。

**下载流程**：

```
GET {baseUrl}/v1/kb/manifest.json
  → 校验 manifest 签名（Ed25519，公钥内置在 APP）
  → 对比本地 kbVersion
  → 逐文件下载到临时目录
  → 校验每个文件 sha256
  → 解包到 files/kb/<newVersion>/
  → 写指针（原子操作）
  → 下一次进入知识库时生效
```

**为什么要签名**：以后服务器被人拿下，也没法往你用户手机里塞带恶意步骤的教程。公钥放 APK 里是安全的，私钥只在你的服务器/本地。

**独立更新通道**（四个互不影响）：

| 通道 | 内容 | 频率 |
|---|---|---|
| `app` | APK 本体 | 月/季 |
| `models` | 型号库 | 周 |
| `tutorials` | 教程 | 周 |
| `ai` | AI 意图/问答库 | 月 |

**第一版没有服务器时的过渡**：`baseUrl` 可配置且默认为空。为空时只用内置基线，界面显示"知识库：内置版本 2026.11.1"。你以后部署 VPS，改一行配置就能开更新，不需要改用户手机上已装的 APP（配置项支持通过远端配置或新版本下发）。

---

## 9. 收费 / 激活码架构（第一版只预留）

**核心设计：一个闸门，一份策略，不做分散判断。**

### 9.1 一个闸门（唯一入口）

APP 里任何"能不能用"的判断，只允许调用一个东西：

```kotlin
object FeatureGate {
    fun allows(feature: Feature): Decision
}

sealed interface Decision {
    data object Allow : Decision
    data class AllowInGrace(val until: Long) : Decision          // 宽限期内，界面提示"网络恢复后自动续期"
    data class Deny(val reason: DenyReason) : Decision           // 需要激活码 / 已过期 / 设备已解绑 / 功能已关闭
    data class Blocked(val minVersionCode: Int) : Decision       // 版本过低，需升级
}
```

**规矩**：界面层、业务层都不许出现 `if (isPaid)`、`if (hasLicense)` 这种分支。要判断就去问 `FeatureGate`。
这条规矩的好处：以后改收费规则，改的是**数据**，不是代码，不用满项目找散落的判断。

### 9.2 策略文档（Policy，服务器下发，APP 只解析）

```json
{
  "schema": 1,
  "policyVersion": 7,
  "mode": "free_all",
  "issuedAt": 1763900000,
  "minSupportedVersionCode": 2,
  "offlineGraceDays": 7,
  "promo": { "active": true, "note": "内测期，全部功能免费", "until": 1795440000 },
  "features": {
    "IDENTIFY": "free", "TUTORIAL_BASIC": "free", "CONNECT_LEGACY": "free",
    "CONNECT_PAIRING": "free", "APP_MANAGE": "free", "APK_SCAN": "free",
    "TV_CONTROL": "free", "DIAGNOSE_FULL_PORT_SCAN": "paid",
    "AI_CHAT": "paid", "AI_VOICE": "paid", "BATCH_OPS": "paid"
  },
  "plans": [
    { "id": "pro_month",    "name": "月度授权", "durationDays": 31 },
    { "id": "pro_year",     "name": "年度授权", "durationDays": 365 },
    { "id": "pro_lifetime", "name": "永久授权", "durationDays": 0 }
  ],
  "signature": "ed25519:…"
}
```

`mode` 三档，**服务器一个字段就能切换全场**：

| mode | 行为 |
|---|---|
| `free_all` | 全部功能开放，忽略 `features` 里的 paid 标记。**第一版默认值** |
| `mixed` | 按 `features` 的 free/paid 分档；`promo.active` 为 true 时付费功能仍开放但界面显示"限时免费" |
| `paid_all` | 除少数基础功能外全部需要授权 |

第一版：策略文档打包在 `assets/policy.json`，`mode = free_all`，用户不需要注册、不需要激活码、界面上不出现任何锁。**联网拉策略的代码先不写**，只留接口。

### 9.3 判定顺序（FeatureGate 内部，逐条短路）

```
1. policy.mode == free_all            → Allow
2. appVersionCode < minSupportedVersionCode → Blocked(提示升级)
3. features[feature] == "free"        → Allow
4. 本地有有效 token，且 token.features 含该功能，且未过期 → Allow
5. 已过期但在 offlineGraceDays 内     → AllowInGrace
6. 其它                               → Deny(需要激活码 / 需要联网验证)
```

免费功能永远走第 3 条，**断网、token 过期、服务器挂了，都不影响免费功能**。APP 不会变成一块砖。

### 9.4 设备身份（不用 IMEI / MAC）

```
首次启动：
  生成 Ed25519 密钥对（BouncyCastle，已在依赖里）
  私钥用 Android Keystore 里的 AES 密钥加密后存 filesDir
  deviceId = Base32(SHA-256(公钥)) 取前 16 位
```

- 不申请 READ_PHONE_STATE，不读 IMEI、MAC、序列号（合规，也避免权限申请）。
- 换手机/重装 = 新 deviceId = 需要重新绑定（服务器端可解绑，见 9.6）。
- 公钥随激活请求上报，服务器可据此校验"同一设备"。

### 9.5 本地缓存与有效期

```kotlin
data class Entitlement(
    val token: String,          // 服务器签名的授权串
    val deviceId: String,
    val features: Set<Feature>,
    val planId: String,
    val issuedAt: Long,
    val expiresAt: Long,        // 0 = 永久
    val lastVerifiedAt: Long    // 上次成功联网校验的时间（不是本地时钟，见下）
)
```

| 情况 | 行为 |
|---|---|
| 有 token、未过期、设备匹配 | 放行，不联网 |
| 过了 `expiresAt`，在宽限期内 | 放行（`AllowInGrace`），界面一行小字提示"网络恢复后自动续期" |
| 宽限期也过了，仍连不上 | 付费功能 `Deny`，免费功能照常 |
| 服务器明确返回失效/退款/解绑 | 立即 `Deny` 并说明原因，删本地 token |
| 本地时间被往回调 | 用 token 里 `issuedAt` + 单调计时的 `lastVerifiedAt` 交叉判断，回拨视为异常 → 付费功能 `Deny`，免费功能照常 |

`offlineGraceDays` 从策略文档读，**以后改宽限天数不需要发版**。

### 9.6 预留的服务器接口（后期实现，这里只定契约）

| 接口 | 方法 | 用途 |
|---|---|---|
| `/v1/policy` | GET | 拉最新策略（mode、功能分档、宽限天数、最低版本） |
| `/v1/license/activate` | POST | 激活码 + deviceId + 公钥 → 签名 token |
| `/v1/license/refresh` | POST | 旧 token → 新 token（续期） |
| `/v1/license/unbind` | POST | 解绑设备（换手机、卖出旧机） |
| `/v1/time` | GET | 服务器时间，校正本地时钟漂移 |

- 全部 HTTPS；地址来自 `BuildConfig` 默认值 + 可被签名策略覆盖（不写死在代码常量里）。
- **私钥只在服务器**，APK 里只有验签公钥。
- 接口契约先写成文档 + Kotlin 接口（`LicenseClient`），第一版实现是 `NoopLicenseClient`：永远返回 `free_all`。

### 9.7 现在实现 vs 后期开发（这张表是关键）

| 内容 | 现在（第一版）| 后期（真正收费时）|
|---|---|---|
| `Feature` 枚举 + `FeatureGate` | ✅ 实现 | 不改 |
| 策略文档解析 + `assets/policy.json`（free_all） | ✅ 实现 | 改内容即可 |
| `DeviceIdentity`（密钥对 + deviceId） | ✅ 实现 | 不改 |
| `Entitlement` 缓存读写 + 验签代码 | ✅ 实现（用自签测试 token 单测） | 不改 |
| `LicenseClient` 接口 + `NoopLicenseClient` | ✅ 实现 | 换 `HttpLicenseClient` |
| 设置页"内测版：全部功能免费" | ✅ 实现 | 变成"授权状态/输入激活码" |
| 服务器、数据库、策略后台 | ❌ 不做 | 部署 VPS + 后台页面 |
| 激活码生成/核销/退款 | ❌ 不做 | 第三方平台发码 + 后台核销 |
| 支付 | ❌ 不做（个人开发者开不了微信/支付宝商户） | 走发卡平台的支付，APP 不接支付 SDK |
| 版本强制升级 | ❌ 不做 | 服务器 `minSupportedVersionCode` + 界面提示 |

第一版为这件事增加的东西：**一个枚举、一个 gate、一个 policy.json、一个 noop 客户端**，合计几百行，不会让 APP 变复杂。

### 9.8 从"全免费"平滑切到"收费"的操作顺序（不改 APP 代码）

1. **先上服务器，但策略仍是 `free_all`**：所有已装用户下次启动拉到策略，行为完全不变。这一步只验证"能拿到策略"，用户无感。
2. **改成 `mixed` + `promo.active = true`**：付费功能开始显示"限时免费"角标，但仍然能用——给用户心理准备，也验证 UI 不出错。
3. **`promo.active = false`**：付费功能开始要求激活码。愿意付的用户输入码；不愿意的继续用免费功能。老用户可发"老用户专享码"（服务器按 deviceId 白名单）。
4. **需要时提高 `minSupportedVersionCode`**：提示旧版本升级（只能提示，不能强制，见 9.9）。

四步都是改服务器上的一个 JSON，**不需要重装 APP**。

### 9.9 诚实边界（写清楚，不吹）

- **拦不住不检查授权的旧版本**。已经在用户手机上的、带旧逻辑的 APK，只要它不联网校验，就能继续用。能做的只有三件事：① 服务器返回旧版本"策略不支持"；② 新内容（知识库、AI）只对通过校验的客户端发放；③ 版本过低时提示升级。
- **不承诺绝对防破解**。APK 可以被反编译、被改包。能做到的是"改包后拿不到服务器签发的内容"，而不是"改了就用不了"。
- 因此收费的价值要落在**服务器侧的东西**上（持续更新的型号库、云端 AI、诊断服务），而不是只靠一个本地开关。这一点从第一版就要按这个方向设计。
- 设备解绑、退款、换机，都需要服务器在线处理，离线做不到。

### 9.10 安全红线

- APK 里没有支付密钥、没有服务器私钥、没有"离线判定会员永久有效"的逻辑。
- 不申请读取 IMEI/序列号的权限。
- 服务器地址、策略、宽限天数都可更新，代码里只有默认值。

---

## 10. AI 助手设计

**第一版：本地规则引擎**（不联网也能答）

```
用户问题
  → 意图分类（关键词 + 槽位：品牌场景 / 连接问题 / 安装问题 / 应用管理 / 通用）
  → 结合上下文（档案里的品牌型号版本、当前是否已连接、最近一次错误码、当前所在页面）
  → 从知识库检索对应条目（教程步骤 / 排查卡 / 术语解释）
  → 输出：答案 + 引用来源（哪篇教程第几步）
```

**能答什么（示例）**：

- "我这个电视怎么开 ADB" → 结合档案直接给对应教程第 1 步
- "我点开了开发者选项，但是没有 ADB 调试" → 排查卡：该品牌部分型号的 ADB 开关在"账号与安全"
- "连接一直转圈" → 结合最近错误：授权弹窗没点 / 端口没开 / 不在同一网段
- "装应用失败提示 INSTALL_FAILED_VERIFICATION_FAILURE" → 解释 + 步骤

**答不了时**：明确说"我的资料里没有这台电视的这个情况"，并给两个动作——「换个说法问」/「反馈型号，等更新」。**绝不编造菜单路径**，因为编错了用户会在电视上乱点，可能改坏设置。

**语音**：调用系统 `SpeechRecognizer`/`RecognizerIntent`，不引入第三方 SDK、不额外收集语音。识别失败自动退回键盘输入。

**云端大模型（预留）**：

```kotlin
interface AssistantEngine {
    suspend fun ask(question: String, ctx: AssistantContext): AssistantAnswer
}
// LocalRuleEngine（第一版）
// RemoteLlmEngine（以后，走自己的服务器中转，密钥只存在服务器）
```

APP 永远不直接连 OpenAI/通义，因为那需要把 API Key 放进 APK——等于公开。以后必须走你的 VPS 转发。

---

## 11. 改名 / 改包名 / 签名迁移

| 项 | 现在 | 改为 | 影响 |
|---|---|---|---|
| 应用名 | 电视助手 | **兔子电视助手** | 只影响显示 |
| applicationId | `com.tvhelper.app` | **`com.tztvhelper.app`** | 等于换了一个 App，旧版无法覆盖升级，必须卸载重装 |
| 代码包名 | 同上 | 同上 | 全项目改 import，机械工作 |
| 版本 | 0.1.0 | 0.2.0 | 因为包名变了，versionCode 从头算也行 |
| 签名 | debug 自动签名 | **已生成正式密钥（2026-11-23）** | 见下方 |

**正式签名状态：已生成，两处存放**

| 项 | 值 |
|---|---|
| 文件 | `rabbit-release.jks`（PKCS12，RSA 4096，有效期 30 年 / 10950 天） |
| 别名 | `rabbit` |
| 证书 SHA-256 | `18:18:37:9F:AF:AE:E9:88:D7:C5:15:5D:0E:7E:AD:59:23:37:E7:05:99:4D:2A:44:16:19:9E:4B:F1:07:ED:D5` |
| 存放 1 | `D:\dev\keystore\`（构建用） |
| 存放 2 | `C:\Users\1\Desktop\智能电视助手\签名\`（备份用，已核对两份文件 SHA-256 一致） |

构建时从 `keystore.properties`（不进仓库）读取，密码不写在 build.gradle 里：

```properties
storeFile=D:/dev/keystore/rabbit-release.jks
storePassword=***
keyAlias=rabbit
keyPassword=***
```

**你必须做**：把 `rabbit-release.jks` 复制到 U 盘和网盘各一份，密码单独记。丢了就永远无法覆盖升级。

**正式签名**：一旦生成，**密钥文件 + 密码丢了，这个 App 就再也无法升级**（只能换包名重来）。所以：

- 生成时间点建议在"功能基本定型、准备第一次给外人装"之前。
- 生成后必须两处备份（U盘 + 网盘/另一台电脑），密码单独记。
- 签名密钥不进 git 仓库（`.gitignore` 已经排除 `*.jks/*.keystore`）。

这一项需要你点头再执行。

---

## 12. 分阶段开发计划

> 标注说明：**模拟器** = 在 Android 模拟器上跑通；**代码级** = 单元测试/编译通过；**真机** = 必须在真实电视上验证（现在不具备条件）。任何未做真机验证的功能，界面上不写"可用"，文档里标 `未验证`。

| 阶段 | 内容 | 验收标准 | 验证方式 |
|---|---|---|---|
| **P0 结构重构** | 改包名/应用名、拆模块目录、导航骨架、把现有扫描/连接/应用列表迁移进新结构 | 编译通过；原有能力（扫描 5555、连接、列应用）在新结构下仍能用 | ✅ **已完成 2026-11-23**：debug 13MB / release 9.7MB 均编译通过；release 已用正式密钥签名；模拟器安装启动无崩溃，标题显示"兔子电视助手" |
| **P1 建档与知识库** | 设备档案存储；品牌/型号选择；AI 引导找型号；知识库解析、匹配链、教程卡片渲染；"未收录"反馈页 | 能建档 → 命中教程；无匹配时走品牌通用；再失败给反馈页 | 模拟器 + 代码级 |
| **P2 连接架构** | mDNS 发现、5555 探测升级、无线调试配对、手动 IP；连上后回写档案 | 模拟器上 5555 通；配对流程单测通过；mDNS 在模拟器能找到假服务 | 模拟器 + 代码级，真机未验证 |
| **P3 应用管理** | 分类器（三路证据）、应用详情、卸载/禁用/恢复 + 守卫、安装 APK | 用真实 `pm` 输出样本做分类单测，误判为 0；系统应用无按钮 | 代码级 + 模拟器 |
| **P4 诊断** | 排查清单、全端口扫描（可中断、四类结果）、服务探测、报告页 | 扫描可中断；关闭/超时/不可达三种场景各有独立文案 | 代码级 + 模拟器 |
| **P5 APK 扫描** | 本地 APK 解析（包名/版本/权限/签名/大小/minSdk/ABI）、风险与兼容提示 | 对 3 个真实 APK 输出与系统"应用信息"一致 | 代码级 + 模拟器 |
| **P6 AI 助手** | 本地规则引擎、意图与上下文、引用来源、语音输入 | 20 条常见问题命中率 ≥ 80%，答不出时明确说不知道（不编造） | 代码级 |
| **P7 更新机制** | manifest/签名校验/原子切换/回滚；本地起 HTTPS mock 验证 | 篡改任意一个文件会被拒绝；版本切换失败能回退 | 代码级 |
| **P8 许可** | 激活码接口、token 验签、离线缓存与宽限、降级；mock 服务器 | 伪造 token 被拒；断网 7 天后降级到免费功能且 APP 可用 | 代码级 |
| **P9 收尾** | 正式签名、加固与安全检查、隐私说明、官网下载页、打包 | 生成 release APK；泄露扫描（无密钥、无调试开关） | 代码级 + 人工 |

**关于工作量**：P0–P2 是地基，占整体约四成；P3–P5 各自独立，可并行；P6–P8 是增强。真实时间取决于两件事——真机能不能拿到、教程素材谁出。**没有真机，P2 之后的东西都只能做到"代码看起来对"**，这一点我不会在文档里美化。

---

## 13. 风险清单（按严重度）

| 风险 | 影响 | 应对 |
|---|---|---|
| 无真机验证 | 连接、装包、禁用可能全部"纸面正确" | 明确标注未验证；拿到真机后逐个回归 |
| 厂商改 adbd：只允许 USB、IP 白名单、ADB 开关藏工厂菜单 | 部分电视永远连不上 | 知识库里标注"该型号不支持无线 ADB"，不硬吹 |
| 教程内容错误 | 用户在电视上乱点，可能改坏设置 | 教程标 `verified/confidence`；高风险步骤加红框警告 |
| 教程素材版权 | 盗图被投诉 | 优先自己截图/示意图；引用注明来源 |
| 全端口扫描触发路由器防护 | 短暂断网 | 限制并发、可中断、扫描前告知 |
| 手机系统杀后台 | 连接中断、安装失败 | 关键操作在前台服务中执行并显示通知，不后台偷偷跑 |
| 安全软件误报 | 用户不敢装 | 官网说明 + 开源部分逻辑 + 申请白名单（如各家手机管家的申诉通道） |

---

## 14. 需要你后续提供/确认

1. **真机**：找到可开 ADB 的电视后，品牌/型号/系统版本发我，我按真机做 P2 起的验证。
2. **教程素材**：截图由你拍还是我用示意图？拍的话需要每台电视的固定几个画面（设置首页、关于页、开发者选项页、ADB 开关页、授权弹窗）。
3. **正式签名**：现在生成还是等 P9？（建议 P9 前，理由见第 11 节）
4. **服务器**：以后用哪家的 VPS、有没有域名。没有也能做，只是更新和激活码功能先空着。
5. **免费/收费边界**：哪些功能免费、哪些收费。现在留空，但越早定越省事——因为要把"付费功能"在界面上提前标出来（比如显示"高级诊断"带锁图标），后加会显得像阉割。

---

## 15. 和现有代码的关系（哪些留、哪些改、哪些删）

| 现有代码 | 处理 |
|---|---|
| `Adb.kt` 连接重试、读属性、禁用/恢复回读校验 | **保留**，搬进 `core/adb`，接口化 |
| `Adb.kt` 的 `uninstall`（对系统应用也放行） | **改**：入口只剩 `PackageClassifier` 判定为 THIRD_PARTY 时出现 |
| `Adb.kt` 的 `listApps`（只靠 `-s` 判系统） | **改**：加路径 + `-3` + `dumpsys` 三路证据，未知归 UNKNOWN |
| `Net.kt` 扫 5555 | **保留**为 `core/discovery` 的一个实现，加可取消与握手确认 |
| `Pkgs.kt` 关键词黑名单 | **删**：换成基于角色的判定（桌面/输入法/SystemUI 用系统命令查，不猜关键词） |
| `MainActivity.kt` 单文件 705 行界面 | **拆**：按 `ui/` 下的页面重写，逻辑逐步搬 |
| `Pkgs.kt` 包名中文名映射 | **保留**，扩成知识库里的"应用名对照表"，可更新 |
| 现有 APK / 签名 / 包名 | **弃用**（包名变更，旧包不兼容升级） |

---

## 16. 本方案不做的事（明确划界）

- 不做一键清理、一键加速、内存优化——这不是清理工具。
- 不改电视系统分区、不刷机、不改 build.prop。
- 不在用户不知情时扫描网络或执行命令。
- 不承诺"所有电视都能连"。
- 不把未验证的功能写成"已支持"。

---

## 17. 实时搜索 + 知识库累积（2026-11-23 调整，已开始实现）

### 17.1 检索顺序（每次查教程都走这条链）

```
用户填/确认：品牌 → 型号 → 系统版本
        ↓
1. 本地知识库（SQLite）：型号+版本精确 → 仅型号 → 品牌通用
        ↓ 命中就展示，并标出"本条来源 + 成功/失败次数 + 最后验证时间"
2. 本地没有 → 实时联网搜索（需搜索源，见 17.5）
        ↓
3. AI 把搜索结果整理成步骤（只做归纳，不许补细节）
        ↓
4. 新方法一律以「候选」入库，附全部原始链接
        ↓
5. 用户照着做 → APP 检测成功/失败 → 写验证记录 → 达标才升为「推荐」
```

**没有可靠结果时，界面必须直说**：「目前没有找到适用于 品牌+型号+版本 的可靠方法」，并提供"反馈我的型号"和"全端口扫描诊断"两条路。**不许生成看起来像教程的编造步骤。**

### 17.2 数据模型（已实现：`core/kb/`）

| 表 | 存什么 | 关键字段 |
|---|---|---|
| `brands` | 品牌 | id、名称、别名（小米/Redmi/红米） |
| `models` | 具体型号 | id、品牌、型号、系统版本列表 |
| `methods` | 一条开启方法 | 适用品牌/型号、系统版本上下限、步骤、状态、成功/失败次数、最后验证时间 |
| `verifications` | 每次验证流水 | 结果（APP实测成功/用户反馈成功/失败）、系统版本、机型、失败原因、时间 |
| `method_history` | 方法变更历史 | CREATED / PROMOTED / DEMOTED / FAILED / EDITED + 说明 |

`methods.status` 四个状态：**候选 → 推荐 → 待复核 → 否决**。

### 17.3 升级 / 降级规则（已写成纯函数 + 9 条单元测试）

| 事件 | 结果 |
|---|---|
| 新搜到的方法（哪怕是官网） | 只能进**候选**，不能顶掉已推荐 |
| APP 实测连接成功 | 直接升**推荐**（最强证据），成功次数 +1 |
| 用户口头反馈成功 | 累计 **2 次**才升推荐 |
| 任何失败 | 失败次数 +1；原本是推荐的降为**待复核**，**成功次数不清零** |
| 失败后继续搜到新方法 | 新方法进候选；原方法的历史记录完整保留 |

这条就是你要的"新搜索结果不能覆盖已验证成功的方法"，已经用代码固定住，不是靠自觉。

### 17.4 来源展示（界面必须显示）

每条方法都要带：**资料类型**（官方 / 论坛 / 用户反馈 / 未验证）、**原始链接**、**抓取时间**、**最后更新时间**、**成功次数 / 失败次数**、**当前状态**。

界面上一律用文字写清楚，例如：

> 来源：论坛帖子（可信度：中）· 2026-11-23 抓取 · 未在实机验证 · 成功 0 次 / 失败 0 次

不许只写"根据资料"这种含糊说法。

### 17.5 搜索源的真实约束（必须先说清）

APK 里**不能放搜索 API Key**（放进去等于公开，谁都能拿去刷），这是你之前定的红线，我按它执行。所以"实时联网搜索"有三种落地方式：

| 方案 | 现在能不能做 | 代价 |
|---|---|---|
| **A. 自建服务器代理搜索**（推荐） | 等你的 VPS | 服务器上存 key，APP 只调自己的接口；还能做缓存、限流、AI 整理 |
| B. 用户自己填搜索 Key（高级设置） | 能做 | 普通用户不会填，只适合懂的人；key 存手机本地 |
| C. APP 内直连某搜索服务 | 不建议 | 要么违规，要么随时被封，且必须把 key 打包进 APK |

**第一版已实现的**：搜索接口 `SearchProvider` + 明确返回"未配置"的 `DisabledSearchProvider`。界面会如实显示「未配置搜索源，当前只能查本地知识库」，**不会假装搜过了**。

等 VPS 好了，新增一个 `HttpSearchProvider` 接上即可，知识库、界面、规则都不用改。

### 17.6 现在实现 vs 后期

| 内容 | 现在 | 后期 |
|---|---|---|
| 知识库 SQLite + 7 个品牌通用教程内置 | ✅ 已实现 | 持续扩充型号级教程 |
| 方法状态机 + 升降级规则 + 单测 | ✅ 已实现 | 不变 |
| 验证记录 / 失败原因 / 变更历史 | ✅ 已实现 | 服务器汇总各用户数据（需隐私授权） |
| 来源与可信度展示 | ✅ 数据层已支持 | 界面展示 + 排序 |
| 实时搜索 | ✅ APP 侧协议/错误/缓存/来源留档已实现并用模拟数据验证；⛔ VPS 未部署、真实结果未跑 | 部署后改配置即可，无需改 APP |
| AI 整理搜索结果 | ✅ 本地规则问答已实现（第 18 节）；⛔ 云端大模型未接 | 接云端大模型，`RemoteAiClient` 接口已留 |
| 跨用户共享验证结果 | ⛔ 不做（无服务器） | 后端聚合，做成"这台型号有 37 人成功" |

---

## 18. 搜索服务接口 + 本地规则问答（2026-11-23 已实现，待真机验证）

### 18.1 顺序：先定契约，不等服务器

服务器可以晚点上线，但 APP 侧的协议、错误分类、缓存、来源留档必须先定死。否则等 VPS 好了再改一遍界面和数据结构，等于返工。
所以这一版的做法是：**接口与整条流程全部实现，用本地模拟数据跑通；真实服务器只需按契约返回 JSON，APP 不用改。**

### 18.2 接口契约（APP → 自建 VPS）

```
POST {baseUrl}/v1/search
Content-Type: application/json
Authorization: Bearer <可选，服务器签发的 APP 令牌>
```

请求：

```json
{
  "v": 1,
  "brand": "小米",
  "model": "4A",
  "osVersion": "8.1",
  "maxResults": 8
}
```

响应（成功）：

```json
{
  "v": 1,
  "provider": "vps-search-v1",
  "searchedAt": 1763900000000,
  "isMock": false,
  "error": null,
  "hits": [
    {
      "title": "小米电视4A 开启 ADB 调试",
      "url": "https://example.com/a",
      "snippet": "设置 → 账号与安全 → ADB 调试…",
      "sourceType": "FORUM",
      "credibility": "FORUM",
      "fetchedAt": 1763899000000,
      "extractedSteps": ["打开设置", "进入账号与安全", "打开 ADB 调试"]
    }
  ]
}
```

约定（服务端必须遵守，否则 APP 侧按"不可信"处理）：

| 字段 | 要求 |
|---|---|
| `url` | 必填，原始网页地址；没有链接的结果不收 |
| `fetchedAt` | 抓取时间戳，不能用"现在"糊弄 |
| `sourceType` | `OFFICIAL` / `FORUM` / `USER` / `UNKNOWN` 四选一 |
| `credibility` | `OFFICIAL` / `FORUM` / `USER_REPORT` / `UNVERIFIED` 四选一 |
| `isMock` | 只有本地模拟数据允许为 true；真实结果永远 false |
| `extractedSteps` | 可以为空；为空的结果不能转成教程 |

服务器侧不做的事（写进契约，避免以后扯皮）：不做"结论式回答"，只返回网页摘要和链接；不返回"以上方法保证可用"这类话。

### 18.3 错误处理（不自动重试，避免烧钱）

| 情况 | APP 归类 | 界面文案 |
|---|---|---|
| 没配服务器地址（Release） | `NOT_CONFIGURED` | 没有配置搜索服务：当前只能查本地知识库 |
| 域名解析失败 / 无网络 | `NETWORK` | 网络不通：检查手机网络，或稍后再试 |
| 连接超时 8s / 读取超时 15s | `TIMEOUT` | 搜索超时：网络慢或服务器没响应 |
| 401 / 403 | `BLOCKED` | 被拦截：服务器拒绝了这个请求 |
| 429 | `RATE_LIMIT` | 请求太频繁：等一会儿再搜 |
| 5xx | `NETWORK` | 服务器出错，稍后再试 |
| JSON 结构不对 | `PARSE_FAILED` | 返回结果解析失败：服务器格式可能变了 |

一律**不自动重试、不静默失败**：失败就显示真实原因，不让用户以为"没结果=没有方法"。

### 18.4 缓存

- 键：`search:v1:{品牌}|{型号}|{系统版本}`，忽略大小写和空格；型号不同不复用（防止把 4A 的教程套到 4C 上）。
- 有效期：默认 7 天，可按安装实例配置（`AppConfig.setSearchCacheTtlMs`）。
- 表：`search_cache`（整包 JSON + 是否模拟）和 `search_hits`（逐条来源留档，即使没转成方法也能追溯）。
- 缓存命中会在界面上标"来自缓存"，不冒充新搜索。
- 时钟回拨导致 `now < createdAt` 视为过期，防止用未来时间伪造缓存。

### 18.5 模拟数据的三条红线

1. 只有 **Debug 包** 且未配置服务器时才启用 `MockSearchProvider`；Release 包未配置就是 `NOT_CONFIGURED`，绝不假装搜过。
2. 模拟结果一律 `isMock = true`，界面显示"模拟搜索结果 / 这不是真实联网结果"。
3. 模拟结果 **禁止写入知识库**（`SearchGuards.canWriteToKb`，已单测覆盖）。理由：真假混在一起后，用户和我们都无法判断哪条是实际联网得来的。

### 18.6 本地规则问答（不是大模型）

| 用户要求 | 对应实现 |
|---|---|
| 品牌、型号、系统版本识别引导 | `IDENTIFY_DEVICE`：三处看型号的位置 + 「我的电视」卡片保存 |
| 本地知识库查询 | `FIND_TUTORIAL` → `KbRepository.find()`，真实查库 |
| ADB 教程查询与步骤引导 | 命中后展示真实步骤、路径、注意事项 |
| 不同型号/版本差异判断 | 匹配顺序 型号+版本 → 型号 → 品牌通用；找不到就明说 |
| 没有匹配教程时怎么处理 | `NO_KB` 回答：不编步骤，给三条可行替代 |
| 未验证信息风险提示 | 回答固定带「依据」和「风险」标签；候选=中风险，待复核=高风险 |
| 开启前后分阶段引导 | `BEFORE_START` / `AFTER_OPENED` / `CONNECT_FAILED` 三段式 |

其它意图：`STEP_HELP`（菜单里找不到）、`RISK_CHECK`（安全边界）、`WHAT_IS_ADB`（大白话解释）、`UNKNOWN`（给示例，不瞎答）。

明确不做的事：
- 不接云端大模型（`RemoteAiClient` 接口留着，实现类为空）；
- 不做多轮上下文记忆（每轮独立，只共享"我的电视"档案）；
- 不做意图外的自由问答，看不懂就说看不懂。

### 18.7 全端口扫描

设计保留（第 7 节未改动），本版未实现，**没有删除任何相关设计**。规则问答在"连不上"场景会提到它，但会注明"这个功能在设计里，还没做"。

### 18.8 这一版仍未实现 / 未验证

| 项 | 状态 |
|---|---|
| VPS 搜索服务本体 | ⛔ 未部署，只有 APP 侧契约实现 |
| 真实联网搜索结果 | ⛔ 一次都没跑过（只有模拟数据） |
| 云端 AI 整理 | ⛔ 接口预留，无实现 |
| 搜索结果转候选的完整把关 | ⚠️ 已实现"模拟数据禁入 + 无步骤禁入"，但没接入人工复核界面 |
| 7 条内置教程的真机可用性 | ⚠️ 仍未验证（`CANDIDATE` + `UNVERIFIED`） |
| 规则问答的意图覆盖度 | ⚠️ 只用单测和模拟器问句验证，未做真实用户语料测试 |


---

## 19. P1 修复（2026-11-24，针对 03d2328 的代码审查）

### 19.1 「APP 实测成功」不再由按钮自己宣布

- 新增 `VerificationRules` + `ConnectionOracle`（实现 `AdbConnectionOracle` 读 Kadb 会话）。
- 只有会话真的建立、且读到了设备信息，`SUCCESS_APP` 才允许写库；否则 `recordVerification` 直接返回 null，数据库一行都不写。
- 界面「连上了（APP 检测）」在没有连接时置灰并写明原因；用户口头确认走独立的「手动记一次成功」（`SUCCESS_USER`）。
- 单测覆盖：没连接、连着但读不到设备信息、有真实连接三种情况。

### 19.2 型号匹配与型号级知识库

- 新增 `ModelMatcher`：用户手输的型号（含「小米4A」这种带品牌写法）先归一化，再与 `models` 表精确比对；命中不唯一或没有命中一律返回 null，**不拿自由文本当数据库 ID**。
- 新增 `MatchPicker`，统一匹配顺序：型号+系统版本（`EXACT_MODEL_OS`，100 分）→ 仅型号（`MODEL`，80 分）→ 品牌通用（`BRAND_GENERIC`，40 分）。
- 修正了旧逻辑两处错误：`EXACT_MODEL_OS` 之前永远不会出现；型号方法声明的系统范围不覆盖用户版本时，之前仍按 80 分硬套，现在直接判为不适用。
- 界面会显示「已识别型号…」或「知识库里没有这个型号，只会按品牌通用查」，不假装型号专属。
- 搜索结果保存时使用解析出的 `modelId`，型号方法按 `model_id` 隔离，4A/4C 不互相顶替。

### 19.3 搜索结果与设备身份绑定

- `SearchOutcome` 携带完整 `SearchQuery`（含 `brandId`），缓存键也改用 `brandId`。
- 新增 `SearchSaveGuard`（纯函数），保存前核对品牌、型号、系统版本；不一致返回 `IdentityMismatch`。界面切换品牌/型号/版本时立即清空旧搜索结果。
- 服务层自己校验，不依赖界面隐藏按钮。
- 单测覆盖：小米结果存成海信教程被拒、同品牌 4A→4C 被拒、版本变化被拒。

### 19.4 搜索响应严格校验

- 必须存在 `v` 且等于协议版本；`isMock` 必须显式存在且为布尔；`searchedAt` 必须为正数。
- 每条结果必须有非空标题、合法 http/https + 主机名的链接、有效抓取时间、合法来源类型与可信度；不合格的条目丢弃，全部不合格则整包 `PARSE_FAILED`。
- 解析失败/错误响应不写缓存、不写 `search_hits`。
- `saveAsCandidate` 自身重新检查 error、isMock、URL、步骤，并只使用用户选中的那一条；模拟数据、无链接、无步骤一律拒绝。
- 明文 HTTP 地址不发送 Bearer 令牌（协议层与工厂层各拦一次），配置界面会提示尽快改 HTTPS。

### 19.5 已否决方法退出匹配

- `MethodRules.pickBest` 与 `MatchPicker` 都过滤 `REJECTED`；历史记录仍保留在库中。
- 只有已否决方法时返回「未找到可用教程」，不展示其步骤。

### 19.6 失败原因

- 点「没用，失败」弹出原因输入框（哪一步、电视显示什么），可「记录原因」或「不填，直接记录」。
- 不填固定记为「未提供原因」；历史详细文案包含原因、系统、机型与累计成功/失败数，成功次数不会被清零。

### 19.7 AI 与搜索的流程边界

- 本地规则回答不宣称联网/大模型；本地库没有答案时，答案里出现「联网搜索这个型号」按钮，由用户点击后才调 `SearchService`。
- 搜索结果必须由用户点选一条才能保存；不再固定取第一条。
- 保存进去的是「候选方法」，备注写明“未经实机验证”，不会变成 AI 已验证教程。
- `RemoteAiClient` 仍是空接口，未接云端大模型。

### 19.8 测试与构建

- 单测 73 个，全部通过（34 个为本轮新增/改写：连接闸门 7、型号匹配 12、保存校验 10、响应解析 12、通用守卫补充 4，另有既有用例回归）。
- `testDebugUnitTest assembleDebug`、`testDebugUnitTest assembleRelease` 均 BUILD SUCCESSFUL。
- 模拟器实测：debug 包显示型号未识别 → 品牌通用；APP 检测置灰；失败原因对话框与「未提供原因」入库；模拟搜索结果单选、不可入库提示。release 包显示「未配置搜索服务」，未使用模拟数据，无崩溃。

### 19.9 仍未实现 / 仍需真机验证

| 项 | 状态 |
|---|---|
| `models` 表内型号数据 | ⚠️ 目前为空：匹配逻辑有单测，但真实界面只会显示“品牌通用”，需补型号数据后才有个性化教程 |
| VPS 搜索服务本体 | ⛔ 未部署，真实搜索结果仍为 0 次 |
| 云端 AI 整理 | ⛔ 未实现 |
| 真电视连接下的 `SUCCESS_APP` | ⚠️ 逻辑与模拟器已验证，真实电视未验证 |
| 失败原因 UI 的文本输入 | ⚠️ 模拟器无中文输入法，只验证了“不填直接记录”路径；规则与历史文案有单测 |
| 全端口扫描 | ⛔ 本版未实现（设计保留） |
