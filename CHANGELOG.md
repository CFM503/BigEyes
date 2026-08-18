# BigEyes 修改日志 (Changelog)

## [v2.0.1] - 2026-08-18

### 🚀 重大重构：纯手机独立版 (去 PC 依赖)
* **内嵌 HTTP 代理服务器**：
  * 在 Android App 内部集成 `NanoHTTPD` 轻量 Web 服务器，手机直接作为 HLS 流媒体代理网关（绑定手机局域网 IP:8765）；
  * 将原 PC 端 `M3U8Parser`、`StreamFetcher`、`PrefetchManager`、`DiskLRUCache` 逻辑 1:1 迁入 Kotlin 实现；
  * 电视端直接向手机内嵌代理拉取 `.m3u8` 清单与 `.ts` 分片。
* **移动端低功耗预取与缓存调优**：
  * 分片并发预取数从 PC 端的 3~5 降至 **2~3**，降低手机 CPU 与射频负载；
  * 磁盘 LRU 缓存上限调优为 **300 MB**（存放于 `context.cacheDir`），自动淘汰历史分片避免爆盘；
  * 保留指数退避重试机制（500ms / 1000ms / 2000ms）。
* **原生 DLNA 设备发现与播控**：
  * 在 Android 端实现原生轻量 SSDP 扫描器（基于 UDP 组播 M-SEARCH 探测局域网 DLNA MediaRenderer 电视）；
  * 封装 UPnP AVTransport SOAP 协议栈（`SetAVTransportURI` / `Play` / `Pause` / `Stop` / `Seek` / `GetPositionInfo`）。
* **全生命周期前台保活体系**：
  * 增加 `CastingForegroundService` 前台服务与常驻通知栏（适配 Android 14 `FOREGROUND_SERVICE_DATA_SYNC`）；
  * 投屏期间自动申请 `PowerManager.PARTIAL_WAKE_LOCK`（保 CPU 息屏不休眠）与 `WifiManager.WifiLock`（保 WiFi 芯片息屏不降频）；
  * 设置页增加“申请忽略电池优化 / 加入白名单”快捷引导。
* **精简与归档**：
  * 移除 v1 中手机与 PC 之间的 mDNS 发现 (`NsdDiscoveryManager`) 与配对鉴权模块；
  * `bigeyes-server` Python 工程标记为 `v1 归档参考`，保留在仓库中供对比验证。

---

## [v1.0.1] - 2026-08-18

### 初始发布：PC 代理中转投屏系统
* 实现 Android WebView 网页嗅探层（拦截 `.m3u8` 并提取 Referer/UA/Cookie）；
* 实现 PC 本地流代理服务（FastAPI + mDNS 广播 + SSDP 扫描 + DLNA 控制 + 分片并发预取与 LRU 缓存）；
* 实现手机 App 底部悬浮播控条与候选选择对话框。
