# BigEyes 投屏系统 (v2 纯手机独立版)

> 影视资源站网页嗅探 + 手机本地内嵌代理/预取加速 + 电视 DLNA 投屏系统 (无需 PC)

---

## 架构示意图 (v2 架构)

```
┌────────────────────────────────────────────────────────┐
│               Android App (BigEyes v2)                 │
│                                                        │
│  WebView 壳浏览器 + 嗅探层                              │
│         │ 提取到 {url, referer, user_agent, cookie}     │
│         ▼                                              │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 前台服务 (CastingForegroundService)              │  │
│  │  ├─ 内嵌 HTTP 代理 (NanoHTTPD :8765)             │  │
│  │  │    m3u8改写 / 分片代理 / 2~3并发预取 / LRU缓存 │  │
│  │  ├─ SSDP 扫描 DLNA 电视 + 原生 SOAP 控制         │  │
│  │  ├─ WakeLock (保 CPU 息屏不休眠)                 │  │
│  │  └─ WifiLock (保 WiFi 芯片息屏不降频)            │  │
│  └───────────┬──────────────────────────────────────┘  │
└──────────────┼─────────────────────────────────────────┘
               │ 电视拉流: http://<手机局域网IP>:8765/stream/...
               ▼
       ┌───────────────┐
       │  电视 DLNA 播放端 │
       └───────────────┘
```

**关键原则**：电视始终只向手机本地内嵌代理请求改写后的流，不直连外网源站；手机在投屏期间常驻前台服务，锁屏可持续观影。

---

## 目录结构

```
bigeyes/
├── bigeyes-app/                  # 【v2 核心】Android 客户端 (纯手机独立版)
│   ├── app/src/main/
│   │   ├── java/com/bigeyes/app/
│   │   │   ├── browser/          # WebView 壳浏览器与 shouldInterceptRequest 嗅探
│   │   │   ├── proxy/            # 内嵌 NanoHTTPD 代理、M3U8 解析改写、预取与 LRU 缓存
│   │   │   ├── dlna/             # 原生 SSDP 局域网扫描与 UPnP SOAP 播控 (Play/Pause/Seek)
│   │   │   ├── service/          # CastingForegroundService 前台保活与锁管理
│   │   │   ├── model/            # 数据模型 (StreamSession, Candidate, DlnaDevice)
│   │   │   └── ui/               # 浏览器主界面、底部浮动播控条、设置与抓包调试
│   │   └── res/                  # 布局与样式资源
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── bigeyes-server/               # 【v1 归档参考】原 PC 端 Python 服务 (v2 无须运行)
│   ├── app/
│   └── run_server.py
│
├── CHANGELOG.md                  # 版本发布与修改日志
└── README.md
```

---

## 一、功能特性

1. **一键嗅探**：在内置 WebView 中浏览视频网站播放视频，自动提取真实 `.m3u8` 地址及防盗链上下文（`Referer`、`User-Agent`、`Cookie`）。
2. **内嵌代理与防盗链**：手机内嵌 NanoHTTPD HTTP 服务器，重写 m3u8 清单分片与 AES-128 Key 为本地代理 URL，电视拉流时由手机代取并透传原始请求头。
3. **移动端预取加速**：后台 2~3 并发滑动窗口预取分片，搭配 300MB 磁盘 LRU 缓存，保障弱网与网络抖动下的流畅播放。
4. **原生 DLNA 播控**：内置 SSDP 探测与 UPnP SOAP 控制，支持电视一键开播、暂停/继续、+/-15s 快捷快进快退与进度条拖拽。
5. **息屏保活支持**：通过 Foreground Service、`PARTIAL_WAKE_LOCK`、`WifiLock` 以及电池优化白名单引导，保障锁屏 10 分钟以上观影不中断。

---

## 二、使用指南

### 1. 构建与安装
使用 Android Studio 打开 `bigeyes-app` 目录构建 APK，或通过 Gradle 命令行打包：
```bash
cd bigeyes-app
./gradlew assembleDebug
```
安装生成的 APK 到 Android 手机。

### 2. 手机操作流程
1. **连接 WiFi**：手机与电视保持在同一局域网（同一 WiFi）；
2. **打开 App 刷剧**：在地址栏输入影视资源站网址，进入播放页；
3. **点击投屏**：视频开播后右上角投屏按钮点亮显示候选数量徽标 `投屏 (1)`，点击投屏；
4. **电视自动开播**：若局域网发现多台电视会弹出选择列表，选中后电视自动开播，手机底部弹出播控条；
5. **锁屏观影**：投屏发起后通知栏显示常驻投屏服务，手机可安心锁屏，电视端持续流畅播放。

---

## 三、版本记录

* **v2.0.2 (当前版本)**：稳定性修复与真机联调面板，实现动态滑动 WakeLock 续期、代理弹性有界线程池、VLC/Kodi 调试面板及全套边界单测。
* **v2.0.1**：纯手机独立版重构，移除 PC 依赖，移入内嵌 HTTP 代理、移动端预取/LRU 缓存与原生 DLNA 控制。
* **v1.0.1**：PC 代理中转版初始发布。
