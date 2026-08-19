# BigEyes 修改日志 (Changelog)

## [v2.0.8] - 2026-08-19

### 🛠️ 单元测试与 JVM 兼容性修复
* **`WebViewDownloadHelper` Base64 多重兼容解码**：
  * 在纯 JVM 单元测试与 Android 8.0+ 环境下优先采用 `java.util.Base64` 标准 MIME/Basic 解码器，解决未 Mock 的 Android 框架方法返回空值引发的单元测试断言失败；
  * 自动降级回退至 `android.util.Base64`，完整兼顾低版本 Android 设备；
  * 增加日志输出安全捕获，消除测试环境下的非预期异常；
* **测试与 CI 构建稳定性提升**：
  * 修复单元测试环境下 Looper/Handler 初始化与空指针问题；
  * 补全相关类引用与构建日志输出，确保 GitHub Actions CI 及本地 Gradle 构建与测试全绿通过。

---

## [v2.0.7] - 2026-08-19

### 🎯 深度视频嗅探重构 (双引擎 + 主动 DOM 扫描)
* **实时 JS 媒体播放器 Hook 注入 (`SnifferBridge`)**：
  * 在页面加载时注入轻量嗅探脚本，拦截 `HTMLMediaElement.prototype.play`、`<video>.src` 属性变化、`Hls.js.loadSource`、`window.fetch` 以及 `XMLHttpRequest`；
  * 完美适配 Artplayer、DPlayer、Hls.js、西瓜播放器等现代 SPA 播放组件，视频开播即自动秒级捕获真实流地址并点亮投屏按钮；
* **点击投屏时“按需主动 DOM 扫描”兜底**：
  * 当点击“投屏”按钮但候选池为空时，触发瞬时深度 DOM 扫描（遍历 `<video>`、`<source>`、`<iframe>`、播放器全局实例及页面正则提取）；
  * 彻底解决在各类影视站（如 VODPlus、非凡资源、量子资源）播放时提示“未嗅探到可投屏视频”的问题；
* **扩展流媒体特征识别与嵌套 URL 解包**：
  * 扩展支持 `.m3u8`、`.mp4`、`.flv`、`.webm`、`.ts` 等全媒体流格式；
  * 自动解包 `player.html?url=https%3A%2F%2F...` 嵌套播放器 URL，精准直连源流。

---

## [v2.0.6] - 2026-08-19

### 🧹 架构精简与废弃模块清理
* **彻底移除废弃 PC 服务端**：
  * 清理已废弃的 Python 后端目录 `bigeyes-server/`；
  * 项目完全聚焦于 Android 原生独立客户端，代码库体积大幅瘦身；
  * 更新文档与架构目录说明。

---

## [v2.0.5] - 2026-08-19

### 🔄 在线安装授权链路优化与断点缓存
* **未知应用安装授权智能接续**：
  * 跳转系统设置授予 `REQUEST_INSTALL_PACKAGES` 权限后，在 Activity 的 `onResume` 中自动检测授权结果；
  * 一旦授权成功即自动调起系统安装器继续安装已下载好的 APK，无需用户重新手动点击“检查更新”；
* **本地已下载安装包智能缓存检测**：
  * `downloadAndInstall()` 开始下载前，先通过 HTTP HEAD 比对本地同版本 APK 文件大小与服务端 `Content-Length`；
  * 若本地已存在完整匹配的 APK 文件，直接跳过下载流程并快速调起安装；
* **持久化待安装状态管理**：
  * 将待安装 APK 路径与目标 `UpdateInfo` 进行内存及 `SharedPreferences` 双重持久化保存，避免 Activity 因跳转系统设置被系统回收后丢失文件引用。

---

## [v2.0.4] - 2026-08-19

### 📱 WebView 移动端触控兼容性全面审计与签名固化
* **HTML5 视频沉浸式横屏全屏**：
  * 实现 `WebChromeClient.onShowCustomView` 与 `onHideCustomView`，配合 `fullscreen_custom_content` 挂载容器与 `WindowInsetsControllerCompat`，支持横屏全屏播放、自动隐藏系统状态栏与导航栏、返回键优雅退出全屏。
* **资源管理文件导入与选择器**：
  * 实现 `WebChromeClient.onShowFileChooser` 与 SAF 文件选择器，支持选择 `.json`/`.txt` 等任意规则/订阅配置文件，妥善处理取消与回调生命周期。
* **资源管理文件导出与 Blob 下载桥接**：
  * 注册 `DownloadListener`，构建 `BlobDownloadBridge` 原生 JavaScript 桥接与 `WebViewDownloadHelper`，将前端堆内存中的 Blob 与 Data URL 自动提取并保存至系统 Downloads 目录。
* **触控穿透、输入焦点与防误缩放**：
  * 轻触 WebView 时自动清除地址栏焦点并收起软键盘，无阻碍透传 `MotionEvent` 至 DOM；
  * 锁定移动视口，禁用整页 Pinch Zoom，彻底防止播放器手势操作误触发页面缩放。
* **JS 弹窗与控制台调试支持**：
  * 原生 Material 对话框承接网页 `alert`/`confirm`/`prompt`；
  * 增加 Web 控制台 Logcat 调试输出。
* **统一固化签名证书与在线更新修复**：
  * 创建固定项目密钥库 `bigeyes-release.jks`，统一 `release` 和 `debug` 签名配置；
  * 修复 GitHub Actions Release 工作流，使用固定密钥编译发布，彻底解决在线更新时出现的“已安装了签名冲突的应用”问题。

---

## [v2.0.3] - 2026-08-18

### 🌐 默认导航站更新与版本发布
* **默认首页导航站点调整**：
  * 内置 WebView 浏览器默认首页由腾讯视频调整为影视资源聚合站 `https://vodplus.pages.dev`；
  * 启动时地址栏自动同步回显默认站点 URL，方便用户直接浏览与搜索影视资源。
* **版本发布**：
  * 版本号升至 **`2.0.3`** (`versionCode: 5`)。

---

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
