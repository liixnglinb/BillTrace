# 账迹 BillTrace

> 付完款，账就自动记好了。Android 自动记账 App —— 零手动、零截图、隐私本地优先。

英文 | [中文](#中文)

## English

**BillTrace** is an Android auto bookkeeping app: it captures every payment silently via notification listening, SMS parsing and an accessibility engine, classifies merchants automatically, and keeps everything in a local encrypted database. No manual entry, no screenshots, no cloud required.

- 100% auto capture (notification + SMS + accessibility engines, event-driven, ~0 background memory)
- On-device auto classification (built-in merchant rules + user feedback learning)
  - Local-first encrypted storage (SQLCipher), optional E2EE cloud sync
- Tech: Flutter UI (planned) / Kotlin collector process / WebView MVP preview

## 中文

**账迹（BillTrace）** 是一款 Android 自动记账应用：付款后 2 秒内自动生成交易记录并自动归类，全程零手动、零截图。

### 核心特性

- **三引擎全自动采集**：通知监听 + 银行短信解析 + 无障碍引擎兜底，事件驱动、后台零常驻内存
- **自动归类**：内置商户规则库 + 端上文本分类，用户纠错一次永久生效
- **隐私优先**：本地 SQLCipher 加密存储，解析后立即丢弃原始消息，不上传原文；云同步端到端加密且默认关闭
- **零打扰**：采集静默、反馈环境化（桌面小组件）、纠错异步化

### 当前状态

MVP v0.3.0 —— 可交互高保真原型（WebView 壳加载），三引擎采集、端上分类、SQLCipher 与 Flutter UI 为下一阶段落地内容。详见 [docs/](docs/) 设计文档。

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
