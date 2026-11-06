# Apifox 联调与接口自测笔记

> 记录日期：2026-11-02　对象：视频数据网关（李宁）
> 关联文档：`11-接口使用手册.md`（33 个接口逐个说明）、`10-通道级隔离与平台拉流.md`（设计）
> 本文是"我作为调用方怎么把平台用起来"的学习笔记，不是设计文档。

---

## 一、这次搞清了什么（速览）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 外部怎么调平台接口 | 请求头 `api-key: <appKey>`，值不是 appSecret |
| 2 | 怎么测不同的 api-key 权限 | Apifox 建多个环境，切环境 = 换 key，一个接口点 4 次 |
| 3 | 通道级隔离到底管什么 | 5 个列表接口被过滤 + 6 类动作接口被拦 + 越权记台账 2003 |
| 4 | 33 个接口分别干嘛 | 见 `11-接口使用手册.md` |
| 5 | 播放地址怎么验证 | 平台「实时视频」/ ZLM `player.html` / VLC / ffmpeg 四种方式 |
| 6 | 协议能不能换 | 能，6 种；flv/hls/rtsp/rtmp 实测可用，**webrtc 当前不可用** |
| 7 | 报错怎么读 | `code` 是业务码，HTTP 200 也可能是失败 |

---

## 二、必须先记住的概念

### 2.1 appKey vs appSecret

| | appKey | appSecret |
|---|---|---|
| 长什么样 | `vwg_` + 16 位 | 32 位随机串 |
| 用在哪 | **接口鉴权**（`api-key` 请求头的值） | 目前只留作"播放地址签名"的密钥 |
| 能看到几次 | 一直可见（列表里就有） | **只在创建/重置时显示一次** |
| 谁保管 | 平台 + 业务方 | 业务方自己抄走 |

> 一句话：**调接口用 appKey，appSecret 现在不参与鉴权。**

### 2.2 Apifox 的三样东西别搞混

| 名字 | 作用 | 常见错误 |
|---|---|---|
| **前置 URL** | 请求的 host（`http://127.0.0.1:8088`） | 结尾多写 `/`；或用了默认的假域名 `test-cn.your-api-server.com` |
| **环境变量** | 只是"存值"，如 `apiKey = vwg_console` | 以为变量名写成 `Header api-key` 就会发出去 —— **不会** |
| **额外参数 / 全局参数** | 真正随每个请求发出去的 header/query | 配了没保存；或配在某个环境里，切环境后忘了配 |

请求头要发出去，只能靠**额外参数/全局参数**（位置选 Header，值写 `{{apiKey}}`）。

### 2.3 HTTP 200 ≠ 业务成功

平台的统一返回体是 `{code, msg, data, traceId, success}`，**业务失败也是 HTTP 200**：

```json
{ "code": 1000, "msg": "请求参数不合法: Required request body is missing", "success": false }
```

所以 Apifox 右上角那个「校验响应 ✅ 通过」只看状态码，会骗人。必须加断言：

```js
pm.test("业务成功 code=0", () => pm.expect(pm.response.json().code).to.eql(0));
```

### 2.4 `X-Request-From: ui`

控制台（网页）自己调接口时会带这个头，后端据此把台账 `source` 记成 `MANUAL`（人工操作）。
**模拟第三方平台调用时不要加这个头**，这样台账才记 `API`。

### 2.5 play-address 只是"签发地址"

`GET /channels/{id}/play-address?protocol=flv` 返回的是一串 URL，**不是播放器**。
真正出画面要另外三样之一：平台前端（flv.js）、ZLM 的 `player.html`、VLC/ffmpeg。

---

## 三、Apifox 配置全流程（照这个配就行）

### 3.1 导入

```
项目 → 导入数据 → URL 导入 → http://127.0.0.1:8088/v3/api-docs
```

导入后是 **26 个路径 / 33 个方法**，按 `通道管理 / 设备管理 / API 客户端 / 业务台账 …` 自动分组。

### 3.2 建环境（4 个，这是"测不同 key"的核心）

| 环境名 | 前置 URL | 环境变量 | 用途 |
|---|---|---|---|
| `1-console` | `http://127.0.0.1:8088` | `apiKey = vwg_console` | 管理员视角 |
| `2-A系统` | 同上 | `apiKey = vwg_QAsI4vkTLfBAVEJ4` | 正常第三方平台（不限通道） |
| `3-受限(3,9)` | 同上 | `apiKey = vwg_aH41rC4zZ5YD5GJ4` | **只授权通道 3、9** |
| `4-过期凭证` | 同上 | `apiKey = vwg_nbVe4O6uE8cwrmxG` | 测凭证过期 |

**只配一次**的部分：全局参数（或环境级额外参数）

```
位置：Header
参数名：api-key
值：{{apiKey}}
```

放在**全局参数**里最省事 —— 四个环境共用一条，`{{apiKey}}` 会随环境切换自动变值。

### 3.3 验证配置成功（关键一步）

发任意一个请求，切到响应区的 **「实际请求 / Actual Request」** 标签，必须看到：

```
GET /api/v1/channels?page=1&size=10 HTTP/1.1
Host: 127.0.0.1:8088
api-key: vwg_console
```

看不到这一行 → 回到 3.2 检查「额外参数/全局参数」是否保存、是否启用。

### 3.4 参数该填在哪个标签

| 方法 | 放哪 |
|---|---|
| GET | 「Params」= query 参数（`page`/`size`/`channelId`…）；路径里的 `{id}` 在「Path 参数」 |
| POST / PUT（建对象） | 业务数据放 **「Body → JSON」**（`deviceCode`、`channelName`…） |
| POST（动作类：`/pull`、`/record/start`、`/enable`、`/disable`） | **没有 body**，直接发送 |

---

## 四、一个接口一个接口测（推荐顺序）

| 阶段 | 调什么 | 期望 |
|---|---|---|
| A 连通 | `GET /health` | `status=UP`、`zlm=true` |
| A | `GET /stats/overview` | 各计数正常 |
| B 主数据 | `POST /devices` → `GET /devices/{id}` → `PUT` | 建一台设备，记下 `id` |
| B | `POST /channels`（`deviceId` 填上一步的） | 建一路通道，`streamId=apifoxtest` |
| B | `GET /channels/{id}/probe` | `online=false` + 有 `conclusion` |
| C 视频 | 推流：`scripts\push-test.bat apifoxtest` | 通道变「在线」，台账出现 `PUBLISH_START` |
| C | `GET /channels/{id}/probe` | `online=true`、`originName=推流` |
| C | `GET /channels/{id}/play-address?protocol=flv` | 拿到可播放 URL |
| C | `GET /streams` | `online=true`、`pulled=false` |
| D 录像 | `POST /channels/{id}/record/start` → 等 60 秒 → `/stop` | `GET /recordings` 出现一条 |
| E 台账 | `GET /ledger`、`GET /ledger?eventTypes=PUBLISH_START,PUBLISH_STOP` | 能看到上面所有动作的流水 |
| E | `GET /sessions` | 一次推流 = 一条会话，含 `durationSec` |
| F 客户端 | `POST /api-clients`（`allowedChannels:[3]`） | 记下 `appKey` / `appSecret` |
| F | 用新 key 调 `GET /channels` | 只返回 1 条（隔离生效） |
| F | `POST /api-clients/{id}/expire`（传过去时间） | 该 key 立刻 `1001 凭证已过期` |

---

## 五、权限矩阵（2026-11-02 实测）

| 接口 | console | A系统 | 受限(3,9) | 过期凭证 |
|---|---|---|---|---|
| `GET /channels` | total=11 | total=11 | **total=2** | 401 |
| `GET /streams` | 全部 | 全部 | 只有通道 3、9 | 401 |
| `GET /ledger` | total=274 | total=274 | **total=146**（通道3的128 + 通道9的18） | 401 |
| `GET /sessions` | 全部 | 全部 | 只有 3、9 | 401 |
| `GET /recordings` | 全部 | 全部 | 只有 3、9 | 401 |
| `GET /devices` | total=5 | total=5 | **total=5（不隔离）** | 401 |
| `GET /channels/3/play-address` | 200 | 200 | **200（授权）** | 401 |
| `GET /channels/1/play-address` | 200 | 200 | **1001 无权访问通道 CH-CAM-001** | 401 |
| `POST /channels/1/pull` | 200 | 200 | **1001 无权操作通道 CH-CAM-001** | 401 |
| `GET /stats/overview` | 全量 | 全量 | 全量（不隔离） | 401 |

> 台账/通道总数会随时间增长，上表数字是当时的快照，**关键是倍数关系**（受限 = 全量的一部分）。
> **设备列表和总览统计有意不隔离**：设备是登记信息、统计是运营视角，本轮只做通道级 —— 这是主动划的边界，答辩要能解释。

### 四个 key 的基线（负向测试用）

| 场景 | 返回 |
|---|---|
| 不带头 | HTTP 401 `{"code":1001,"msg":"缺少请求头 api-key"}` |
| 假 key | `{"code":1001,"msg":"无效的 api-key"}` |
| 已停用（`vwg_demo_app`） | `{"code":1001,"msg":"该客户端已被停用"}` |
| 已过期（`vwg_nbVe4O6uE8cwrmxG`） | `{"code":1001,"msg":"该客户端凭证已过期"}` |
| 免鉴权 | `GET /api/v1/health` 不带 key 也能通 |

---

## 六、播放地址怎么验证 & 6 种协议实测

### 6.1 四种验证方式

| 方式 | 怎么做 | 适合 |
|---|---|---|
| 平台「实时视频」页 | http://127.0.0.1:8088 → 实时视频 → 播放 | 验证端到端链路（前端写死用 flv） |
| ZLM 自带播放页 | http://127.0.0.1/player.html，填 `app=live`、`stream=usbcam` | 同一路流切协议对比 |
| VLC / PotPlayer | 媒体 → 打开网络串流 → 粘地址 | 第三方交叉验证（排除平台因素） |
| ffmpeg 命令行 | `tools\ffmpeg\bin\ffmpeg.exe -i <地址> -t 2 -f null -` | 确认流里真的有 h264 数据 |

**不要把 flv 地址粘到浏览器地址栏** —— 浏览器不解码 FLV，只会当文件下载。

### 6.2 六种协议实测（`live/usbcam`）

| `protocol=` | 地址 | 实测 | 播放方 |
|---|---|---|---|
| `flv`（默认） | `http://127.0.0.1/live/usbcam.live.flv` | ✅ 200 `video/x-flv`，首字节 `46 4C 56`（"FLV"） | 浏览器+flv.js、VLC |
| `hls` | `http://127.0.0.1/live/usbcam/hls.m3u8` | ✅ 200，内容 `#EXTM3U` 开头 | Safari/手机、hls.js、VLC；延迟 10~30s |
| `rtsp` | `rtsp://127.0.0.1:554/live/usbcam` | ✅ 554 端口通 | VLC、ffmpeg、OBS |
| `rtmp` | `rtmp://127.0.0.1/live/usbcam` | ✅ 1935 端口通 | VLC、ffmpeg、OBS |
| `ws-flv` | `ws://127.0.0.1/live/usbcam.live.flv` | ⚠️ 需 WebSocket 客户端 | flv.js socket 模式 |
| `webrtc` | `http://127.0.0.1/index/api/webrtc?...` | ❌ **本套 ZLM 不带 WebRTC** | 暂无 |

ffmpeg 实测输出（证明流是通的）：

```
Input #0, flv, from 'http://127.0.0.1/live/usbcam.live.flv':
  Stream #0:0: Video: h264 (Constrained Baseline), yuv420p, 1920x1080, 30 fps
  frame= 40  time=00:00:01.96
```

### 6.3 为什么 webrtc 不可用

查 `getApiList` 与 `getServerConfig`：**没有 webrtc 相关 API，也没有 `rtc` 配置段** —— 现在跑的 ZLM（commit `046aaa3`，2024-08-22 构建）是精简编译，不含 WebRTC 模块。

后果：`play-address?protocol=webrtc` 仍会返回地址（按规范拼的），但连不上。**接口支持 ≠ 媒体服务器支持**，这是最容易误判的地方。
好消息：前端「实时视频」页写死用 flv，界面上不会踩这个坑。

### 6.4 一个隐藏限制：`127.0.0.1`

地址的 host 来自配置 `vgw.zlm.base-url`。现在是 `127.0.0.1` → **只有本机能播**，别人电脑打开是他自己的 127.0.0.1。
要给别人看，得把 `vgw.zlm.base-url`、ZLM hook 地址都改成局域网 IP（如 `10.7.25.138`），签发出来就是 `http://10.7.25.138/live/usbcam.live.flv`。

---

## 七、这次遇到的 7 个坑（都处理了）

| # | 现象 | 原因 | 处理 |
|---|---|---|---|
| 1 | `Required request body is missing` | 把 body 字段填到了「Params / Query」 | 改填「Body → JSON」 |
| 2 | 分页接口在文档里看不到 `page`/`size` | `PageQuery` 是 POJO，没加 `@ParameterObject`，springdoc 渲染成一个叫 `query` 的必填对象参数 | 6 个分页接口全部加 `@ParameterObject`，并给 `PageQuery` 补 `@Schema` 说明/示例 |
| 3 | `$.data.pullUrl 不允许为 null` | OpenAPI 3.0 里属性没声明 `nullable:true`，出现 null 就算"不符合定义" | `spring.jackson.default-property-inclusion: non_null`，空值字段直接不输出 |
| 4 | 校验显示"通过"但返回 `code=1000` | 校验只看 HTTP 状态码，业务失败也是 200 | 加断言 `pm.response.json().code === 0` |
| 5 | 表格「客户端名称」和「联系人」之间一大片空白 | Element Plus `fit=true` 会把剩余宽度全塞给弹性列（`min-width` 列） | 表格改 `:fit="false"`，列宽就是设定值；操作按钮全部平铺 |
| 6 | 拉流报 `This stream already exists` | ZLM `retry_count` 默认 -1（无限重试），拉不通时代理留在服务器上，流却没注册 | 显式 `retry_count=3` + `timeout_sec=5`；下发前用 `getProxyInfo` 清残留；新增「探测」接口 |
| 7 | `addStreamProxy` 报"媒体服务器不可达: Read timed out" | ZLM 下发拉流时会阻塞地连源地址，我们 10s 读超时先回来了，但代理可能已注册成功 | 超时后先查代理：在 → 按"已下发未上线"处理；不在 → 才报失败 |

---

## 八、这次动过的代码

| 文件 | 改动 | 要重启后端？ |
|---|---|---|
| `dto/*`（6 个 Controller 的分页方法） | 加 `@ParameterObject` | 是 |
| `common/PageQuery.java` | 加 `@Schema` 说明/示例 | 是 |
| `application.yml` | `spring.jackson.default-property-inclusion: non_null` | 是 |
| `domain/Channel.java` + `ChannelController` | 新增非表字段 `deviceName`，批量补齐 | 是 |
| `frontend/app.js` | API 客户端表 `:fit="false"` + 列宽 + 操作平铺；通道表加「所属设备」列 + 【探测】按钮；通道下拉显示流标识 | 否（刷新页面即可） |

---

## 九、还剩什么没做

1. **部署配置**：把 `vgw.zlm.base-url`、`vgw.hook-base-url`、ZLM 侧 hook 地址从 `127.0.0.1` 换成局域网 IP —— 不然只有本机能看
2. **WebRTC**：要么换带 webrtc 的 ZLM 构建，要么让 `play-address` 遇到 webrtc 时返回 `supported:false`
3. **DTO 默认值说明**：`deviceType` 默认 `RTMP`、`enabled` 默认 `1`，文档里看不出来
4. **播放地址签名 + `on_play` 验签**：隔离从"接口层"走到"流层"，地址被转手也管得住
5. **出口 Webhook**：把事件推给订阅方（HMAC 签名 + 幂等 + 重试 + 推送日志表已预留）

---

## 附：术语小抄

| 名词 | 一句话解释 |
|---|---|
| **api-key** | 平台发给业务方的调用凭证，放在请求头里，值是 appKey |
| **appKey / appSecret** | 凭证的"账号/密码"；调接口只用 appKey |
| **HTTP-FLV** | 把 FLV 封装用 HTTP 长连接边下边播，延迟 1~3 秒，Web 端首选（配 flv.js） |
| **WS-FLV** | 同上，但走 WebSocket |
| **HLS** | 苹果的切片协议（`.m3u8` + 一堆 `.ts`），兼容性最好但延迟 10~30 秒 |
| **RTSP** | 安防摄像头最通用的实时流协议，浏览器不能直接播 |
| **RTMP** | 老牌推流协议，OBS 默认就是它 |
| **WebRTC** | 浏览器原生超低延迟（<500ms），适合对讲/双向 |
| **VLC** | 免费开源跨平台播放器，能直接播 rtsp/rtmp/http-flv，工程师排查流常用 |
| **PotPlayer** | Windows 上的免费播放器，解码强、能播网络流、能调延迟 |
| **ZLM / ZLMediaKit** | 本项目用的流媒体服务器，负责收流、转协议、分发、录像切片 |
| **台账（ledger）** | 所有业务动作的流水账，谁能查、什么结果都留痕，是"业务台账"需求的落点 |