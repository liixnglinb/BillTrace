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

v0.4.3 —— 全量代码审查后的修复版：读取失败不再伪装成「未授权」（以前会打出 ¥NaN 并诱导用户反复开权限）；删除账目加确认且 6 秒内可撤销（软删除）；判重改原子，通知与短信同时到达不再重复入库；导出 CSV 加引号转义与公式注入防护；详情能看原始文本了；去掉多余的 INTERNET 权限与本地文件访问；触控目标 ≥44px、支持减弱动态效果。规则数实测 111 条，文案改称 110+。

有意未做：无障碍引擎（脆弱且要高危权限）、SQLCipher 全库加密、云同步、Flutter UI。设计文档见 [docs/](docs/)。

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



### 文档

- [产品与架构设计方案](docs/设计方案-自动账单管理App.md)
- [前端设计方案](docs/前端设计方案.md)
