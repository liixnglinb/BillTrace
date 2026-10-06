# 账迹 BillTrace

> 付完款，账就自动记好了。Android 自动记账 App —— 零手动、零截图、隐私本地优先。

英文 | [中文](#中文)

## English

**BillTrace** is an Android auto bookkeeping app: it captures payments silently by listening to payment notifications and parsing bank SMS, classifies merchants automatically, and keeps everything in a local database. No manual entry, no screenshots, no cloud.

- Two capture engines: notification listener (Alipay / WeChat / bank apps) + bank SMS parser
- Historical backfill: on first run it can import past bank transactions from the SMS inbox
- On-device auto classification: 110+ merchant rules, plus per-merchant learning from your corrections
- Local only: plain SQLite in app-private storage, no network, no account, uninstall = gone
- Tech: Java collector services (NotificationListenerService / BroadcastReceiver) + WebView UI

## 中文

**账迹（BillTrace）** 是一款 Android 自动记账应用：付款后 2 秒内自动生成交易记录并自动归类，全程零手动、零截图。

### 核心特性

- **双引擎自动采集**：通知监听（支付宝 / 微信 / 银行 App）+ 银行短信解析，付款后 2 秒内入库
- **历史回填**：授权短信后，把过去几个月的银行账目一次补进账本
- **自动归类**：内置 110+ 条商户规则；认不出来的标为待确认，改一次就记住这个商户
- **只存本机**：App 私有目录里的本地 SQLite，不联网、不上传、不需要账号，卸载即删除
- **零打扰**：不弹窗、不推送广告，安静待在后台

### 当前状态

v0.4.4 —— 把「账多了以后」的体验补齐：列表分页（以前共 5000 条只给看 200 条且无提示）、搜索改数据库全量检索（以前只在已翻出的那一小段里找）、搜索/翻页失败不再说成「没找到」；报表修了三处会看错的地方——分类占比之前因无序容器**随机挑 6 类**、「其余 N 类」几乎隐形、柱形最矮几根看不清；全部文字对比度按 WCAG AA 逐像素校准（159 组文字/背景 + 11 个图形对象实测达标）；冷启动不再闪黑屏；界面拆成结构/样式/纯逻辑/交互四层，纯逻辑层 32 条 Node 单测连同 Java 侧 47 条一起进 CI 门禁。

有意未做：无障碍引擎（脆弱且要高危权限）、SQLCipher 全库加密、云同步、Flutter UI、深色模式（界面按浅色一套设计 tokens 校准到 WCAG AA，半套深色只会把配色冲掉；已在 `index.html` 用 `color-scheme: light only` 钉死，不让系统强制反色）。设计文档见 [docs/](docs/)。

### 下载

前往 [Releases](https://github.com/liixnglinb/BillTrace/releases/latest) 下载 `BillTrace.apk`（Android 8.0+），或访问官网下载页：https://lxlrwxs.top/billtrace/

`BillTrace-debug.apk` 是同内容的兼容副本，仅因为站内旧链接指向这个名字。

### 构建

前置：**JDK 17** 与 **Android SDK（compileSdk 34）**。仓库已提交 Gradle wrapper（8.9），不需要单独装 Gradle。

```bash
./gradlew assembleRelease   # Windows 用 gradlew.bat；产物：app/build/outputs/apk/release/app-release.apk
```

本地没有签名材料时 `assembleRelease` 会失败，这是故意的；`assembleDebug` 可以在不配签名的情况下出包验证编译。签名需要两个环境变量：

```bash
export BILLTRACE_KEYSTORE=/path/to/billtrace.p12
export BILLTRACE_KEY_PASSWORD=...
```

push 到 main 即触发 GitHub Actions 自动构建并上传 Release，签名材料从 Secrets `BILLTRACE_KEYSTORE_B64` / `BILLTRACE_KEY_PASSWORD` 注入，仓库内不放 keystore。

> GitHub Secrets 写入后不可读回，只能覆盖。`billtrace.p12` 一旦丢失，已装机的用户将永远无法覆盖升级，必须在仓库外另存离线备份。

Release 的标题与说明由 CI 生成：标题取 `app/build.gradle` 的 `versionName`，正文取 [docs/release-notes.md](docs/release-notes.md)。发版改了用户可见行为时，**先更新这个文件**，否则线上说明会停在旧内容。（`latest` 是固定 tag，`gh release create` 只有第一次会成功，所以 CI 在 create 失败时改走 `edit`——少了这一步，标题和说明会永远冻结在首个构建。）



### 架构

采集在 Java 侧，界面在 WebView 里，两边只通过一个 JS 桥说话。

**Java（`app/src/main/java/com/voyra/billtrace/`，8 个类 / 约 1840 行）**

| 类 | 职责 |
|---|---|
| `MainActivity` | 唯一的 Activity。建 WebView、注入桥、把系统授权入口接上（通知使用权 / 短信权限，含被系统永久拒绝后的设置页兜底） |
| `Bridge` | `@JavascriptInterface` 门面。前端能调的全部方法在这里，含错误回传与条数钳制 |
| `TxnStore` | SQLite。软删除、判重事务、键集分页、SQL 侧检索、CSV 导出与转义、损坏库恢复 |
| `PayParser` | 从通知文本 / 短信正文里解析出金额、方向、商户、账户 |
| `Rules` | 111 条商户关键字规则表 + 分类中文名 + 渠道包名映射，大小写不敏感 |
| `PayNotifyListener` | `NotificationListenerService`，付款通知到达即入库 |
| `SmsReceiver` | `RECEIVE_SMS` 广播，以及「导入历史短信」的全表回填（后台单线程 + 进行中标志） |
| `Txn` | 交易记录数据结构 |

**前端（`app/src/main/assets/`，4 个文件）**

| 文件 | 内容 | 为什么单独一个 |
|---|---|---|
| `index.html` | 只有骨架、弹层结构和两行 `<script src>` | 结构改动不再和生产逻辑混在一次 diff 里 |
| `ui.css` | 全部样式与设计 tokens | 颜色/字号/间距集中在一处，对比度才有单一修改点 |
| `core.js` | **纯函数层**：金额与日期格式化、HTML 转义、分类表、筛选、按天聚合、设置读写、桥返回值形状校验 | 不碰 DOM / 桥 / localStorage，因此能被 Node 直接 `require` 单测 |
| `app.js` | DOM 渲染、桥调用、事件与弹层 | 需要真浏览器才能验，交给 Playwright 那套 |

`core.js` 用 UMD 写法同时满足两边：Node 里 `require` 拿到导出对象，WebView 里把函数铺成全局，所以 `app.js` 的调用点不需要写成 `BTCore.money(...)`。

**数据流**：通知/短信 → `PayParser` + `Rules` → `TxnStore` →（前端主动调）`Bridge` 返回 JSON → `core.js` 校验形状 → `app.js` 渲染。前端不持有数据，也没有任何写入路径绕过 `TxnStore`。

**权限面**：`AndroidManifest.xml` 只声明 `RECEIVE_SMS` 与 `READ_SMS`（通知使用权走 `BIND_NOTIFICATION_LISTENER_SERVICE` 的服务绑定，不是 `uses-permission`）。**没有 `INTERNET`** —— 代码里没有任何网络调用，这一点和"数据只存本机"的说法必须能被清单文件验证。

### 测试与量测

| 命令 | 内容 | 是否 CI 门禁 |
|---|---|---|
| `./gradlew test` | JVM 单测 47 例：解析、规则、CSV 转义、检索语句拼装 | ✅ 不过就不出包 |
| `npm run test:core` | 纯函数层 32 例，只用 `node:assert`，不装任何包 | ✅ 同上 |
| `npm run test:ui` | Playwright 驱动真实渲染的 `index.html`（桥打桩），77 条断言覆盖正常/空/故障三态、权限被永久拒绝、坏设置、XSS、分页与检索失败态、三视口无横向溢出 | ❌ 本机跑（CI 无浏览器） |
| `npm run bench:ui` | 渲染性能基线 | ❌ |
| `npm run ab:group` | 分组聚合的算法对照，并断言新旧实现逐日合计一致 | ❌ |

`test:core` 里有一条跨语言断言：读 `Rules.java` 的源码，核对 `CATS` 的 10 个分类 id 与中文名和 Java 侧 `catName()` 逐字一致。这条约束原先只写在注释里靠人眼守，两边改一边就会让导出的 CSV 出现界面看不到的分类名。

对比度是量出来的，不是"看着还行"：`diag-a11y.js`（文字级，逐像素取背景中位色）与 `diag-graphic.js`（非文字对象：柱形、堆叠条接缝、预算环、FAB）。当前覆盖 4 个页面 + 4 个弹层 + 横幅，159 组文字/背景与 11 个图形对象全部达 WCAG AA。这两个脚本按 `diag*.js` 忽略，属于一次性量测而非产品代码。

### 常见问题

**「通知使用权」为什么不在应用详情页里？**
它是系统特殊访问，不在普通权限列表。应用会先尝试直接跳原生设置页，失败则试厂商页（小米 `miui.intent.action.APP_PERM_EDITOR`），再失败则退回系统设置主页。你到的是哪个页面取决于 ROM，但不会跳到一个找不到「账迹」的页面。

**拒绝过两次短信权限之后，按钮为什么变成「去设置里开启」？**
Android 11+ 在第二次拒绝后自动等效于「禁止后不再询问」，此后 `requestPermissions()` 不弹框直接回调 DENIED。应用检测到这个状态就不再让你白点，直接把入口换成跳设置页。

**为什么 APK 只有一百来 KB？**
没有第三方依赖、没有网络库、没有图表库、没有字体文件（`assets/` 里只有 4 个前端文件和 14 个 WebP 图标），UI 是 WebView 加载本地 HTML/CSS/JS。体积小不是偷工，是这个架构的必然结果。下载页上标的具体字节数取自 CI 实际产物，本地无法复算（这台机器没有 Android SDK）。

**认不出商户怎么办？**
该笔会进「待确认」，点一下选分类即可；这个商户下次就被自动记住。认不出的记录不会被静默归到「其他」当成已确认。

**账目很多时列表只显示一部分？**
列表一次取 200 条，底部有「加载更多（还有 N 条）」，按 `(时间, id)` 键集往后翻，翻到底按钮消失。搜索不受这个窗口限制，它走 SQL 检索，能命中没翻页到的账。

**数据存在哪，会上传吗？**
App 私有目录下的一个普通 SQLite 文件。不联网（见上面的权限面）、无账号、无后台上报。卸载即随应用目录一起删除。

**导出的 CSV 打开是空文件 / 数字不能求和？**
导出会走文件保存对话框，取消即为空文件，不是丢数据。金额列不会被加上防公式注入的单引号前缀（那样 Excel 会当文本，就没法求和），文本列才做中和处理。

**账本打不开怎么办？**
界面顶部会给红色横幅说明是「读取失败」而不是「未授权」，并提供「重建账本」入口（二次确认）。重建只新建空账本，损坏的文件改名保留在原处，不会被删除。

### 版本与更新说明约定

- 版本号只有一处真值：`app/build.gradle` 的 `versionName` / `versionCode`，成对递增。
- Release 的标题由 CI 从 `versionName` 取，正文取 `docs/release-notes.md`。**改了用户可见行为就必须先更新这个文件**，否则线上说明会停在旧内容（`latest` 是固定 tag，`gh release create` 只有第一次成功，CI 在失败时改走 `edit`）。
- 说明写"用户能感知到什么"，不写提交罗列：写「删除账目现在会先弹确认，6 秒内可撤销」，不写「TxnStore 增加软删除」。
- 有意未做的部分（无障碍引擎、SQLCipher 全库加密、云同步、Flutter UI、深色模式）在上面的「当前状态」里点名，不用"暂未支持"糊过去。

### 文档

- [产品与架构设计方案](docs/设计方案-自动账单管理App.md)
- [前端设计方案](docs/前端设计方案.md)
