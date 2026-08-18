# BigEyes 投屏系统

> 影视资源站网页嗅探 + PC 代理/预取加速 + 电视 DLNA 投屏系统

---

## 目录结构

```
bigeyes/
├── bigeyes-server/               # PC 本地服务 (Python + FastAPI)
│   ├── app/
│   │   ├── api/                  # REST API & Stream 代理路由 (/api/cast, /stream/...)
│   │   ├── discovery/            # mDNS 广播服务 (_bigeyes._tcp.local.)
│   │   ├── dlna/                 # SSDP 设备扫描与 DLNA SOAP 控制 (Play/Pause/Seek)
│   │   ├── proxy/                # m3u8 解析改写、防盗链拉流、并发预取与 LRU 缓存
│   │   └── utils/                # 局域网物理网卡 IP 识别
│   ├── tests/                    # 单元与集成测试 (pytest)
│   ├── requirements.txt
│   └── run_server.py             # 启动入口脚本
│
└── bigeyes-app/                  # Android 客户端 (Kotlin + Android Gradle)
    ├── app/src/main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/bigeyes/app/
    │   │   ├── browser/          # WebView 壳浏览器与 shouldInterceptRequest 嗅探器
    │   │   ├── discovery/        # NsdManager mDNS 服务发现客户端
    │   │   ├── model/            # 候选流与设备数据模型
    │   │   ├── network/          # OkHttp 与 PC 服务通信 Client
    │   │   └── ui/               # 候选选择、设备选择、底部播控浮条、设置与调试页
    │   └── res/                  # 布局与样式资源
    ├── build.gradle.kts
    └── settings.gradle.kts
```

---

## 一、PC 本地服务 (bigeyes-server) 使用与验证

### 1. 安装依赖
```bash
cd bigeyes-server
pip install -r requirements.txt
```

### 2. 运行自动化测试
```bash
python -m pytest tests
```
测试涵盖：
- Master Playlist 码率优选与 Media Playlist 分片/AES-128 Key 代理 URL 改写
- 磁盘 LRU 缓存存储与容量淘汰机制
- 完整 Cast 发起、防盗链 Header 透传、流改写、分片下载与预取管线

### 3. 启动服务
```bash
python run_server.py
```
启动后服务监听在 `http://0.0.0.0:8765`，并自动通过 mDNS 广播 `_bigeyes._tcp.local.`，同时在后台定期扫描局域网内的 DLNA 电视设备。

### 4. 验证命令 (curl)

#### ① 服务健康检查
```bash
curl.exe -s http://127.0.0.1:8765/
# 预期输出: {"service":"BigEyes PC Server","version":"1.0.0","lan_ip":"...","port":8765}
```

#### ② 发起投屏
```bash
curl.exe -X POST http://127.0.0.1:8765/api/cast \
  -H "Content-Type: application/json" \
  -d "{\"url\":\"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8\",\"title\":\"测试视频\"}"
# 预期输出: {"status":"ok","stream_id":"xxxx","proxy_url":"http://...:8765/stream/xxxx/index.m3u8",...}
```

#### ③ 获取改写后的 m3u8 清单
```bash
curl.exe -s http://127.0.0.1:8765/stream/<stream_id>/index.m3u8
# 预期输出: 标准 HLS playlist，所有 .ts 和 .key 地址均被替换为经过 PC 代理的本地 URL
```

#### ④ 获取视频分片与预取落盘
```bash
curl.exe -i -s http://127.0.0.1:8765/stream/<stream_id>/seg/0.ts -o nul -w "HTTP Status: %{http_code}\nContent-Type: %{content_type}\nDownloaded: %{size_download} bytes\n"
# 预期输出: HTTP 200, Content-Type: video/mp2t，并在 ~/.bigeyes/cache/ 目录下触发并发预取
```

#### ⑤ 播控与状态查询
```bash
# 查询当前播放状态
curl.exe -s http://127.0.0.1:8765/api/status

# 暂停/继续
curl.exe -X POST http://127.0.0.1:8765/api/control -H "Content-Type: application/json" -d "{\"action\":\"pause\"}"
curl.exe -X POST http://127.0.0.1:8765/api/control -H "Content-Type: application/json" -d "{\"action\":\"play\"}"
```

---

## 二、Android 客户端 (bigeyes-app) 使用与验证

### 1. 编译与安装
使用 Android Studio 打开 `bigeyes-app` 目录，或者使用 Gradle 命令行构建：
```bash
cd bigeyes-app
./gradlew assembleDebug
```
生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 2. 功能验证路径
1. **服务自动连接**：App 启动时通过 `NsdManager` 自动扫描并连接局域网内的 PC 服务（若网络隔离可在“设置”中手动填入 PC IP）。
2. **网页浏览与嗅探**：在地址栏输入影视资源站网址，点击进入视频播放页面播放视频。
3. **投屏按钮点亮**：当 WebView 发出 `.m3u8` 请求时，嗅探层自动提取完整的 URL、Referer、User-Agent、Cookie，右上角投屏按钮右上角展示候选数量徽标 `投屏 (1)`。
4. **一键投屏**：点击投屏按钮：
   - 若只有 1 条候选，直接推送到电视；
   - 若有多条候选（如广告流 + 正片流），弹出选择框供用户确认。
5. **播控与后台播放**：投屏成功后底部弹出悬浮播控条（播放/暂停、进度拖拽、加减 15 秒、停止）。手机可直接锁屏或退出 App，电视端从 PC 代理持续拉流播放。
