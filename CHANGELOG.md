# BigEyes 修改日志 (Changelog)

## [v2.0.2] - 2026-08-18

### 🛠️ 稳定性修复、真机联调辅助与在线自动更新
* **新增 GitHub Releases 在线自动更新机制**：
  * 实现 `UpdateManager`，启动时静默检测与设置页手动“检查新版本”；
  * 解析 GitHub Releases 最新版本与 APK 资产，支持带进度条的流式断点下载并自动调用 `FileProvider` 调起 Android 系统安装器；
  * 集成 GitHub Actions 自动化发布工作流（`.github/workflows/release.yml`），打 Tag 时自动云端编译并发布 Release 产物。
* **修复 WakeLock 动态滑动续期机制**：
  * 将原有的固定 4 小时硬超时改为 **15 分钟动态滑动窗口**，在播放器拉取分片或触发播控操作时自动刷新续期，支持无限时长连续观影；
  * 增加 **30 分钟无活动安全自动关停** 兜底机制，彻底杜绝遗忘投屏服务导致的后台异常耗电。
* **优化内嵌代理线程模型**：
  * 在 `EmbeddedProxyServer` 中引入 **4~16 弹性有界线程池 (`PooledAsyncRunner`)**，消除原 NanoHTTPD 每次请求创建无界 OS 线程的开销与资源争抢。
* **新增设置页真机联调辅助面板**：
  * 提供 **VLC 联调专用地址一键复制**（含手机局域网 IP、内嵌端口及完整 M3U8 代理 URL）；
  * 提供 **最新嗅探候选与防盗链 Header 详情一键复制**（完整 StreamID、原始 URL、Referer、User-Agent、Cookie）；
  * 提供 **局域网 DLNA 设备实时扫描面板**，一键主动探测 Kodi / 电视设备并展示 XML 描述与 AVTransport 控制端点。
* **完善自动化测试与边界用例覆盖**：
  * 新增 M3U8 解析边界用例（多码率 1080p 中等优先选流、无 Key 媒体清单改写、畸形/无尾换行符清单容错、复杂 Query 参数与路径保留）；
  * 新增 SSDP 多电视厂商（三星、小米、极简投影仪）XML 描述解析与命名空间兼容测试；
  * 新增 DiskLRUCache 超大单文件超限淘汰与 10 线程并发高频读写安全测试；
  * 新增 UpdateManager 语义化版本比对单元测试。

---

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
