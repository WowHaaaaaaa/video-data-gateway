# ZLMediaKit 速查手册（本项目精简版）

> 目标：**用最小代价掌握 ZLM**。只写你会用到的；用不到的明确标注"不用学"。
> 版本：master / commit `046aaa3` / 编译于 2024-08-22（本机 `tools/zlm/MediaServer.exe`）
> 官方文档在 GitHub（本机访问不了），本文内容全部来自**实测**。

---

## 一、它是什么（记住这 3 句）

1. **独立的流媒体服务器进程**（`MediaServer.exe`），不是库、不是框架、不是数据库。
2. 它只做四件事：**收流（推+拉）、转协议、分发/录像、事件上报**。
3. 它**不懂**业务：不认识设备、不记账本、不管权限 —— 这三件事是**你的平台**的活。

> 判断口诀：**80/554/1935 端口是 ZLM；8088 是我的平台。**

---

## 二、三分钟上手（4 个动作）

```powershell
$S = 'a8Fk3QzR7mWp2TvN6xLc9Yb4Hs1Jd5Eg'   # = config.ini 里的 [api] secret

# ① 看有哪些流
(Invoke-RestMethod "http://127.0.0.1/index/api/getMediaList?secret=$S").data |
  Select-Object app,stream,schema,originTypeStr,readerCount,aliveSecond | Format-Table

# ② 服务器配置（含所有默认值，比看 config.ini 全）
(Invoke-RestMethod "http://127.0.0.1/index/api/getServerConfig?secret=$S").data[0].http.port

# ③ 推一路流（另开窗口）
scripts\push-test.bat            :: 默认推 live/fakesrc（彩条+正弦音）

# ④ 浏览器播
http://127.0.0.1/player.html
```

**双击即用的工具**：`scripts\zlm-streams.bat`（看流）、`scripts\start-zlm.bat`（启动）、
`scripts\push-test.bat`（假流）、`scripts\push-usb-camera.bat`（真相机）

---

## 三、必须记住的 6 个概念

| 概念 | 含义 | 本机实际值 |
|---|---|---|
| **`app` / `stream`** | 组成"流地址"：`rtmp://host/{app}/{stream}` | `live` / `cam01`、`usbcam` |
| **`vhost`** | 虚拟主机，单机固定 | `__defaultVhost__` |
| **`secret`** | API 密钥，**必须 32 位字母数字** | `a8Fk3QzR7mWp2TvN6xLc9Yb4Hs1Jd5Eg` |
| **`schema`** | 协议封装：rtmp/rtsp/ts/fmp4/hls/webrtc | 一路流会同时产出 5 种 |
| **`originType`** | 流从哪来 | `1`=推流(rtmp_push)、`4`=拉流(pull)、2/3=派生 |
| **`hook`** | 事件回调（ZLM 主动 POST 给平台） | 见第七节 |

### ⚠️ 最容易误读的一点

`getMediaList` 返回的是「**协议实例**」不是「**流**」：

```
1 路流 live/cam01  →  ts / rtsp / fmp4 / hls / rtmp  = 5 条记录
```

所以看到 10 条记录 ≠ 10 路流，**按 `app + stream` 去重才是流数量**。

---

## 四、你会用到的 8 个接口（51 个里只用这些）

调用格式统一：

```
GET http://127.0.0.1/index/api/{接口名}?secret=xxx&其他参数
```

| 接口 | 用途 | 必带参数 |
|---|---|---|
| `getApiList` | 列出 ZLM 支持的全部接口（51 个） | — |
| `getMediaList` | **看有哪些流**（最常用） | — |
| `getMediaInfo` | 看单路流的技术参数 | `vhost` `app` `stream` `schema` |
| `isMediaOnline` | 判断某路流在不在线 | `vhost` `app` `stream` `schema` |
| `getMediaPlayerList` | 谁在看这路流 | `vhost` `app` `stream` |
| `startRecord` / `stopRecord` | 录像开关（`type=1` 为 mp4） | `type` `vhost` `app` `stream` |
| `addStreamProxy` | **让 ZLM 主动去拉一路流** | `vhost` `app` `stream` `url`（要 URL 编码） |
| `delStreamProxy` | 删除拉流代理 | `key=__defaultVhost__/{app}/{stream}` |
| `setServerConfig` | **热改配置**（不用重启） | `hook.enable=0` 之类 |
| `version` / `getStatistic` | 健康检查 / 运行统计 | — |

### 用不到、别学

GB28181 那 11 个（openRtpServer 等）、推流代理、级联、多节点、线程负载、文件下载。
**真需要时再查**（都在 `getApiList` 里，随时能列出来）。

---

## 五、播放地址：同一路流，五种播法

以 `live/cam01` 为例：

| 协议 | 地址 | 延迟 | 用途 |
|---|---|---|---|
| **HTTP-FLV** | `http://127.0.0.1/live/cam01.live.flv` | ~1s | **网页首选** |
| HLS | `http://127.0.0.1/live/cam01/hls.m3u8` | 3~10s | 兼容性最好 |
| RTSP | `rtsp://127.0.0.1:554/live/cam01` | ~1s | VLC / 监控软件 |
| RTMP | `rtmp://127.0.0.1/live/cam01` | ~1s | 再推给别的服务器 |
| WebRTC | `http://127.0.0.1/index/api/webrtc?app=live&stream=cam01&type=play` | <0.5s | 超低延迟（需专用页面） |

---

## 六、推流源怎么来（不挑来源）

| 来源 | ffmpeg 参数 | 备注 |
|---|---|---|
| **摄像头** | `-f dshow -i video="RGB Camera"` | 用 `scripts\camera-check.bat` 先测哪个能打开 |
| 电脑屏幕 | `-f gdigrab -i desktop` | 演示/录屏，注意屏幕上的一切都会播出去 |
| 视频文件 | `-re -stream_loop -1 -i xx.mp4` | 循环播放做素材 |
| 测试图案 | `-f lavfi -i testsrc2=size=1280x720:rate=25` | 纯验证，不依赖硬件 |

统一推流地址：`rtmp://127.0.0.1/live/{streamId}`（`streamId` 必须在平台登记过）

---

## 七、Webhook：ZLM 什么时候会来问你

在 `config.ini` 的 `[hook]` 段配置。**本项目已开启 6 个**：

| 事件 | 时机 | 平台做什么 |
|---|---|---|
| `on_publish` | 有人开始推流 | **查通道表 → 放行/拒绝**（推流鉴权） |
| `on_stream_changed` | 流注册/注销 | 断流时结算时长 |
| `on_play` | 有人开始播放 | 记录/验签（未来） |
| `on_stream_none_reader` | 没人看了 | 记播放结束 |
| `on_record_mp4` | 录像切片完成 | 写录像台账 |
| `on_server_started` | ZLM 启动 | 清理遗留"进行中"会话 |

### 关键：返回什么决定 ZLM 怎么做

```json
{"code": 0,  "msg": "success"}    → 放行（继续）
{"code": -1, "msg": "拒绝原因"}    → 拒绝（推流被断、播放被拒）
```

### 三种"问不问平台"的状态（实测验证）

| 场景 | 只有 ZLM（hook 关） | 有平台（hook 开） |
|---|---|---|
| 设备推流 | 直接收，来者不拒 | **先问平台**，查表决定 |
| 平台主动拉流 | ZLM 直接拉 | 平台发起即决策，ZLM 不问 |
| 有人看但流不存在 | 直接失败 | 当前也失败（`on_stream_not_found` 未配置） |

> **拉流也想受管控**怎么办？两种方式：
> ① 平台调 `addStreamProxy` 时先鉴权（决策前移）；
> ② 配置 `on_stream_not_found` 钩子，让 ZLM 在"有人要看但没有流"时来问平台。

---

## 八、6 个坑（都是我们踩过的）

### 坑 1：`config.ini` 带 UTF-8 BOM 会被整体忽略
ZLM 的 ini 解析器不认 BOM → 把文件当空的 → 生成默认配置并回写。
**解决**：写文件必须无 BOM，校验前 3 字节不是 `EF BB BF`。

### 坑 2：`secret` 必须是 32 位字母数字
UUID 格式（带 `-`）非法 → ZLM 启动时**随机生成新密钥并回写配置** → 平台永远对不上。
日志会明确写：`The api.secret is invalid, modified it to: xxx, saved config file`。

### 坑 3：很多接口**必须带 `vhost`**
`startRecord` / `getMediaInfo` / `isMediaOnline` / `openRtpServer` 等，
漏了只会得到 `Required parameter missed: "type","vhost","app","stream"`。单机固定传 `__defaultVhost__`。

### 坑 4：返回结构不统一

| 接口 | 返回形态 |
|---|---|
| `getMediaList` | `{"code":0,"data":[ ... ]}` 数组在 data 里 |
| `getMediaInfo` | `{"code":0,"aliveSecond":..., "tracks":[...]}` **直接在顶层** |
| `isMediaOnline` | `{"code":0,"online":true}` 扁平 |
| 无流时的 `getMediaList` | `{"code":0}` —— **没有 data 字段就是"零路流"** |

### 坑 5：常见错误码

| code | 含义 | 怎么办 |
|---|---|---|
| `0` | 成功 | — |
| `-100` | secret 错误 | 检查 `config.ini` 的 `[api] secret` |
| `-300` | 缺必填参数 | 补 `vhost` / `app` / `stream` / `schema` |
| `-500` | **找不到这条流** | **先用 `getMediaList` 确认流在不在** |
| `404`（HTML 页） | 接口名不存在 | 用 `getApiList` 查正确名字 |

### 坑 6：`getSnap`（截图）在本版本不可用
传任何源地址都返回 259 字节的 HTML 404 页。
**替代方案**（已验证可用）：
```powershell
ffmpeg -y -i rtmp://127.0.0.1/live/cam01 -frames:v 1 -q:v 3 snap.jpg
```

---

## 九、PowerShell 中的正确用法（否则会静默失败）

| 写法 | 结果 |
|---|---|
| `curl.exe ... \| ConvertFrom-Json` | ❌ JSON 多行时被拆碎，静默返回空 |
| `curl.exe ... \| Out-String \| ConvertFrom-Json` | ✔ 可行 |
| **`Invoke-RestMethod "http://..."`** | ✔✔ **最佳**，自带 JSON 解析 |

```powershell
# 推荐写法（一行版）
$S='a8Fk3QzR7mWp2TvN6xLc9Yb4Hs1Jd5Eg'; (Invoke-RestMethod "http://127.0.0.1/index/api/getMediaList?secret=$S").data | Select app,stream,schema | Format-Table
```

**另外**：`setServerConfig` 支持**热改配置**，演示时想临时关掉鉴权不用重启：
```powershell
Invoke-RestMethod "http://127.0.0.1/index/api/setServerConfig?secret=$S&hook.enable=0"   # 关
Invoke-RestMethod "http://127.0.0.1/index/api/setServerConfig?secret=$S&hook.enable=1"   # 开
```

---

## 十、本机关键配置一览

`tools\zlm\config.ini` 中我们改过的项：

| 配置 | 值 | 为什么 |
|---|---|---|
| `[api] secret` | `a8Fk3QzR7mWp2TvN6xLc9Yb4Hs1Jd5Eg` | 必须 32 位字母数字 |
| `[http] port` | `80` | 播放地址简洁 |
| `[rtp_proxy] port` | `10000` | GB28181 收流（预留） |
| `[hook] enable` | `1` | 开启事件回调 |
| `hook.on_publish` 等 6 项 | `http://127.0.0.1:8088/hook/zlm/*` | 指向我的平台 |
| `hook.stream_changed_schemas` | `rtmp` | **只订阅源协议，避免一次断流记多笔账** |
| `[record] enable_mp4`（protocol 段） | `0` | 关闭全局自动录像，改为按需触发 |
| `[record] fileSecond` | `60` | 切片 60 秒，便于演示录像台账 |
| `hook.on_stream_not_found` | 空 | 按需拉流能力未开启（可扩展点） |

**端口分工**

| 端口 | 用途 |
|---|---|
| 80 | HTTP：API、HTTP-FLV、HLS、静态网页 |
| 554 | RTSP |
| 1935 | RTMP 推流 |
| 443 / 9000 | HTTPS / WebSocket |
| 10000 | GB28181 PS/RTP 收流 |

---

## 十一、明确不用学（省时间）

GB28181 SIP 信令与级联、JT1078 车载、多 ZLM 节点集群、Redis 分布式、
推流代理、录像回放跳转、PTZ、按需拉流（`on_stream_not_found`）、截图 API。

> 遇到需要时，用 `getApiList` 现场查接口名即可。

---

## 十二、一句话总纲

> **ZLM 是能力齐全但不记忆、不设防的搬运工。
> 它的钩子是"请示"，它的接口是"命令"；
> 谁有权推、谁有权拉、谁有权看、账怎么记 —— 全是平台的活。**
