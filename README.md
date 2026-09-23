# 账迹 BillTrace

> 付完款，账就自动记好了。Android 自动记账 App —— 零手动、零截图、隐私本地优先。

英文 | [中文](#中文)

## English

**BillTrace** is an Android auto bookkeeping app: it captures payments silently by listening to payment notifications and parsing bank SMS, classifies merchants automatically, and keeps everything in a local database. No manual entry, no screenshots, no cloud.

- Two capture engines: notification listener (Alipay / WeChat / bank apps) + bank SMS parser
- Historical backfill: on first run it can import past bank transactions from the SMS inbox
- On-device auto classification: 120+ merchant rules, plus per-merchant learning from your corrections
- Local only: plain SQLite in app-private storage, no network, no account, uninstall = gone
- Tech: Java collector services (NotificationListenerService / BroadcastReceiver) + WebView UI

## 中文

**账迹（BillTrace）** 是一款 Android 自动记账应用：付款后 2 秒内自动生成交易记录并自动归类，全程零手动、零截图。

### 核心特性

- **双引擎自动采集**：通知监听（支付宝 / 微信 / 银行 App）+ 银行短信解析，付款后 2 秒内入库
- **历史回填**：授权短信后，把过去几个月的银行账目一次补进账本
- **自动归类**：内置 120+ 条商户规则；认不出来的标为待确认，改一次就记住这个商户
- **只存本机**：App 私有目录里的本地 SQLite，不联网、不上传、不需要账号，卸载即删除
- **零打扰**：不弹窗、不推送广告，安静待在后台

### 当前状态

v0.4.0 —— 真机可用的采集版本，通知监听与短信解析均已落地，界面数据全部来自本机真实记录，不含任何演示数据。

有意未做：无障碍引擎（脆弱且要高危权限）、SQLCipher 全库加密、云同步、Flutter UI。设计文档见 [docs/](docs/)。

### 下载

前往 [Releases](https://github.com/liixnglinb/BillTrace/releases/latest) 下载 `BillTrace-debug.apk`（Android 8.0+），或访问官网下载页：https://lxlrwxs.top/billtrace/

### 构建

```bash
gradle assembleDebug   # 产物：app/build/outputs/apk/debug/app-debug.apk
```

push 到 main 即触发 GitHub Actions 自动构建并上传 Release（资产名固定 `BillTrace-debug.apk`）。

### 文档

- [产品与架构设计方案](docs/设计方案-自动账单管理App.md)
- [前端设计方案](docs/前端设计方案.md)
