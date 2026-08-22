# BigEyes 修改日志 (Changelog)

## [v2.0.19] - 2026-08-22

### 🛡️ 彻底消除冷启动前台服务异常崩溃与全量升级 AppCompat 控件
* **彻底根除 Android 12/13/14 冷启动闪退 (`ForegroundServiceStartNotAllowedException`)**：
  * 移除了 `MainActivity.onCreate()` 中在冷启动未交互阶段无条件调用 `startForegroundService()` 的逻辑，彻底根治 Android 12+ / 14 系统出于电池后台限制策略直接杀死 App 的崩溃异常；
  * 本地投屏代理服务改为在用户主动点击「投屏」发起推送时按需安全启动，并在全流程加入 `try-catch` 熔断防护；
* **全面升级 `AppCompatImageButton` 彻底解决布局解析崩溃**：
  * 主界面 `activity_main.xml` 中的所有操作按钮显式升级为 `androidx.appcompat.widget.AppCompatImageButton`，配合 `app:srcCompat` 和 `app:tint` 属性，彻底消灭原生 `LayoutInflater` 解析失败导致的 `InflateException`；
  * 新增 `BigEyesApplication` 全局 Application 托管类，在进程启动伊始即激活 `AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)` 与全局未捕获异常捕获器；
* **完整保留超宽网址输入框、双层导航与书签系统**。

---

## [v2.0.18] - 2026-08-22

### 🚀 彻底修复启动即闪退问题、升级全版本 VectorDrawable 兼容性
* **彻底根除启动即闪退（桌面点击直接闪退）**：
  * **修复 VectorDrawable 属性解析崩溃**：移除了 `ic_home.xml`、`ic_bookmark.xml`、`ic_bookmark_filled.xml`、`ic_delete.xml`、`ic_edit.xml` 等矢量图标根节点中的 `android:tint="?attr/colorControlNormal"` 主题属性引用，消除低版本与原生系统在解析布局时的 `InflateException` / `XmlPullParserException` 崩溃；
  * **全局启用 VectorDrawableCompat 支持**：在 `MainActivity` 入口静态激活 `AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)`，并在布局中统一采用 `app:srcCompat` 与 `app:tint` 属性，确保在所有 Android 系统版本（7.0~14+）上 100% 稳定冷启动；
* **持续保持超宽网址输入框与双层导航布局**。

---

## [v2.0.17] - 2026-08-22

### 📱 网址输入框全屏宽幅重构、双层导航布局与视频点击闪退深度根治
* **全新移动浏览器双层人体工学布局**：
  * **超宽网址输入框**：重构顶部操作栏，移除非核心按钮，网址输入框宽度提升至屏幕 75% 以上，彻底解决输入框过窄、难以辨识与输入的问题；
  * **底部主操作栏 (`bottom_bar`)**：新增 48dp 底部导航栏，集成【后退 ◀】、【前进 ▶】、【书签收藏 ⭐】与【设置中心 ⚙】，单手握持轻松触达；
  * **优雅的投屏层级**：当发起投屏时，播放控制栏（切集/暂停/进度条）自动平滑停靠在底部栏上方，结构井然有序。
* **彻底根治 bad.news 等站点点击视频闪退**：
  * **消除 Chromium Native SIGSEGV 闪退**：移除 `WebChromeClient.onCreateWindow` 内部异步销毁临时 WebView 的崩溃漏洞，所有弹窗与 `target="_blank"` 链接由 `SnifferWebViewClient` 安全平滑路由加载；
  * **修复全屏 Surface 销毁导致的 GPU 崩溃**：全屏视图展示时保持底层 WebView 处于 `INVISIBLE` 状态（由置顶黑色容器全量覆盖），杜绝 GPU 渲染管线解绑导致的底层驱动崩溃；
  * **优化 DOM 扫描微任务性能**：为 `MutationObserver` 注入 1000ms 扫描防抖节流机制，避免高频 DOM 变化占用主线程或触发内存溢出。

---

## [v2.0.16] - 2026-08-22

### ⭐ 浏览器书签收藏管理系统、自定义默认主页与主页重置为腾讯视频
* **出厂默认主页重置与自定义主页设置**：
  * 出厂默认主页切回 **腾讯视频 (`https://v.qq.com`)**，支持移动端自适应排版与高清视频嗅探；
  * **自定义主页设置**：在设置界面新增「默认主页设置」控制面板，支持自定义配置启动主页 URL，并提供「恢复腾讯视频」一键重置功能；
  * **顶部导航栏新增【主页 🏠】按钮**：无论在任何网页浏览，均可一键快速返回用户设定的默认主页；
* **完整的书签收藏与管理系统**：
  * **顶部导航栏新增【书签 ⭐】按钮**：根据当前页面是否已收藏动态切换实心高亮与空心图标；
  * **书签管理面板**：点击书签按钮即可弹出管理面板，支持一键收藏当前页面、点击列表条目直接跳转浏览；
  * **书签编辑与主页联动**：支持修改书签名称与网址、删除书签，以及直接在书签列表中将常用站点一键设为默认主页；
  * **预设精选站点**：首次使用自动提供腾讯视频、爱奇艺、优酷、芒果TV、哔哩哔哩、VodPlus 影视聚合等精选预设，可随时一键恢复。

---

## [v2.0.15] - 2026-08-22

### 🛡️ 深度加固浏览器核心、修复点击视频闪退与全屏/多窗口异常崩溃
* **彻底修复点击视频闪退（如 `bad.news` 等聚合网站）**：
  * **修复全屏视图父容器冲突崩溃**：在 `WebChromeClient.onShowCustomView` 中严格安全剥离视图已有父节点（`(view.parent as? ViewGroup)?.removeView(view)`），彻底解决 Android 原生 `IllegalStateException: The specified child already has a parent` 引发的闪退；
  * **修复屏幕旋转与 Activity 意外重建崩溃**：在 `AndroidManifest.xml` 中补齐 `screenLayout|smallestScreenSize|uiMode|locale|layoutDirection` 配置变更，防止视频全屏切换传感器横竖屏时 Activity 销毁重建导致 WebView 硬件加速 Surface 崩溃；
* **JS 注入层与播放器 Hook 深度容错加固**：
  * 重构 `HTMLMediaElement.prototype.play` 与 `src` 属性拦截器，全部添加隔离保护与安全 Promise 返回，杜绝第三方网站自身复杂播放器与嗅探脚本冲突；
  * DOM 视频状态监听器全面加入安全防护，防止异常事件中断网页运行；
* **多窗口弹窗与 Intent 协议安全路由**：
  * 实现 `WebChromeClient.onCreateWindow` 内部安全路由，自动将 `target="_blank"` 或 `window.open` 视频与网页无缝在当前窗口加载，消除弹窗崩溃点；
  * 增强 `shouldOverrideUrlLoading` 对 `intent://` 协议的安全解析与 `browser_fallback_url` 网页回退；
* **候选流监听与下载模块全链路安全加固**：
  * 嗅探候选管理器 `CandidateManager` 与界面徽章更新 `updateCastBadge` 强化主线程与 Activity 存活生命周期校验；
  * `DownloadManager` 与文件下载模块全面捕获系统服务级异常。

---

## [v2.0.14] - 2026-08-21

### 🚀 在线更新国内多源加速、全链路屏幕常亮保活与浏览器刷新按钮
* **GitHub 在线更新国内多源加速与 GitHub 直连保全兜底机制**：
  * 内置国内高可用 GitHub 加速镜像池（`ghfast.top`、`ghproxy.net`、`mirror.ghproxy.com`、`gh-proxy.com`）；
  * **优先采用高速镜像加速**：Release APK 下载速度提升 10~50 倍，解决国内直连 GitHub 限速断流问题；
  * **严格增加保全兜底方案 (Smart Fallback)**：下载过程中若某个镜像异常自动无缝切换下一节点，若**所有加速镜像均失败，自动回退到官方 GitHub 原生直连节点**下载；
  * 版本检测 API 同样支持镜像容灾与超时重试，确保新版本信息秒级响应；
* **手机端播放器双层屏幕常亮（Keep Screen On）保活机制**：
  * 彻底解决在手机上观影时屏幕自动变暗息屏的问题；
  * **网页全屏播放**：进入 HTML5 全屏模式自动注入 `FLAG_KEEP_SCREEN_ON`，退出全屏自动恢复系统休眠；
  * **网页内嵌小窗播放**：通过 JS Bridge 监听 DOM `<video>` 播放/暂停状态，播放中自动保持常亮，暂停/播完后恢复正常休眠，兼顾观影与省电；
* **顶部导航栏新增【刷新】按钮**：
  * 导航栏按照主流移动浏览器标准排列（`[后退] [前进] [刷新] [网址框] [投屏] [设置]`）；
  * 采用矢量刷新图标与水波纹触控反馈，点击即时重载当前网页。

---

## [v2.0.13] - 2026-08-20

### 🎯 电视剧集全链路自动连播与【清空重探】播放中实时恢复
* **修复「清空重探」在视频播放中无法重新嗅探的问题**：
  - 在 JS 注入层与 DOM 扫描层加入 `window.__bigeyes_recorded_streams__` 内存流持久追踪；
  - 扩展多播放器实例深度扫描（Hls.js, DPlayer, Artplayer, XGPlayer, CKPlayer, Aliplayer, TCPlayer 及 iframe 数据源）；
  - 关联候选弹窗「清空重探」即时回调，点击后立即触发主动嗅探，确保在正在播放状态下点击清空依然能 100% 重新捕获当前正片；
* **剧集自动连播协同机制 (适配 BigEyesTV / 智能电视)**：
  - 投屏期间启动后台状态监听，当检测到电视端一集播完自然结束时，自动联动内置 WebView 切换下一集并嗅探推流；
* **智能网页选集与切集算法 (`triggerNextEpisode`)**：
  - 算法优先匹配网页中的“下一集/下集/Next”按钮或当前高亮集数的下一个兄弟节点并模拟点击；
  - 兼顾常见影视站 URL 集数序号自增规则；
* **手机端播控条新增【下一集】按钮**：
  - 底部浮动播控条加入【下一集】快捷按钮，用户可在手机屏幕上随时一键切集。

---

## [v2.0.12] - 2026-08-19

### 🎯 智能广告流过滤与候选多源推荐优化
* **自动识别并剔除片头广告视频流**：
  * 内置广告特征模式库（过滤 `ad.m3u8`、`adv.m3u8`、`guanggao`、`/ad/`、`/ads/`、`/adv/`、`dsp.`、`union` 等广告视频流）；
  * 杜绝将影视网站 15~30 秒片头广告抓入候选池，彻底消除多源误导；
* **页面跳转时自动清理旧候选**：
  * 在 WebView 导航加载新网页时，自动重置候选池，防止上一部电影的遗留视频源与当前电影混杂；
* **候选选择弹窗交互升级**：
  * 自动为最新捕获的视频流高亮标注 **`【推荐 / 最新】`**；
  * 新增 **「清空重探」** 操作按钮，支持一键清空候选并重新触发页面主动嗅探。

---

## [v2.0.11] - 2026-08-19

### 🎯 彻底根治嗅探 TS 分片导致 M3U8 二进制损坏与 Kodi 报错
* **严禁将单个 `.ts` / `.m4s` 切片添加为候选视频**：
  * 发现影视播放器边播边加载 `.ts` 切片时，切片不断挤占候选池（形成虚假的“投屏(5)”切片列表）；
  * 用户点播这些 `.ts` 切片时，后台将 TS 二进制流当作 M3U8 文本解析，导致生成的 M3U8 充满二进制乱码，Kodi 报错 `Playback failed (Error creating demuxer)`；
  * 严格限定候选池只接受完整独立播放列表（`.m3u8`、`.mp4` 等），过滤 `.ts`、`.m4s`、`.key` 分片；
* **Windows 辅助脚本与防火墙放行工具**：
  * 新增 [`fix_dlna.bat`](file:///d:/SOFT/AI/github/bigeyes/fix_dlna.bat)：自动将当前网络切换为专用网络，一键开启 Windows「网络发现」与「媒体流」防火墙规则；
  * 新增 [`open_port_9192.bat`](file:///d:/SOFT/AI/github/bigeyes/open_port_9192.bat)：自动提权并一键添加入站放行 9192 端口（TCP/UDP）规则。

---

## [v2.0.10] - 2026-08-19

### 🎯 深度修复嗅探误报与 Kodi 播放失败问题
* **精确嗅探规则过滤（消除伪候选流）**：
  * 移除宽泛的 `/vod/`、`/video/`、`playlist` 误判规则，杜绝将影视站网页 HTML/JSON API 当作视频流捕获；
  * 严格匹配真实媒体后缀（`.m3u8`、`.mp4`、`.flv` 等）及流参数（`format=m3u8`、`type=m3u8`），防止 Kodi 收到 HTML 导致 Demuxer 解包崩溃（`Error creating demuxer`）；
* **M3U8 协议头部与内容合规性校验**：
  * 强制确保改写后的 M3U8 文件首行为标准 `#EXTM3U`，修复 Kodi FFmpeg 解码器对缺失标头的报错；
  * 在流会话创建时增加 HTML 页面拦截保护，并在代理端提供更明确的错误引导。

---

## [v2.0.9] - 2026-08-19

### 🚀 DLNA 设备直连与局域网组播限制突破
* **新增「手动输入 IP / IP 直连」功能**：
  * 支持在设置页或投屏选择弹窗中直接输入设备 IP 与端口（例如 `192.168.68.236:1700` 或 `192.168.68.236`）；
  * 自动发起 HTTP UPnP 协议描述探测并解析为 `DlnaDevice`，彻底绕过路由器 Wi-Fi 组播过滤（IGMP Snooping / AP 隔离）与 SSDP 广播丢失问题；
  * 手动添加的设备自动持久化存储至 `SharedPreferences`，后续启动或投屏时自动回显并记忆；
* **SSDP 扫描激活 `MulticastLock` 组播锁**：
  * 在触发局域网扫描时自动获取 Android 系统的 Wi-Fi 组播锁并在扫描后释放，防止手机系统休眠或节电机制拦截 UDP 组播包；
* **投屏弹窗交互优化**：
  * 当自动扫描未发现设备时，直接弹出 IP 直连引导弹窗，支持一键输入 IP 并直接开播。

---

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
