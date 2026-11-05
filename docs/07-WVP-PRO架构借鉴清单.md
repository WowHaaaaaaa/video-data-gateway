# WVP-PRO 架构借鉴与照搬清单

> 分析对象：`wvp-GB28181-pro-master` v3.0.0（Spring Boot 3.4.4 / Java 21 / MyBatis / Redis）
> 前篇：《WVP-PRO 能力比对与取舍》（docs/06）讲"取哪些能力"
> 本篇讲"**哪些代码和设计可以直接照搬**"

---

## 一、一句话结论

> **架构同构，可直接照搬的有 5 处；值得学思路但不必照抄代码的有 3 处；明确不碰的有 6 类。**

它的分层（controller / service / dao / bean / media / streamProxy）与我们一致，
差别只在它做了 GB28181 全业务闭环 + 多节点，我们只做网关那一层。

---

## 二、⭐⭐ 可以直接照搬的 5 处

### ① Hook 返回体可以**下指令**（最大发现，我们完全没用）

WVP 的 `HookResultForOnPublish` 继承 `HookResult`（只有 code/msg），额外多了这些字段：

```java
public class HookResultForOnPublish extends HookResult {
    private Boolean enable_audio;      // 是否接收音频
    private Boolean enable_mp4;        // 是否录制 mp4
    private Integer mp4_max_second;    // 录像切片时长（秒）
    private String  mp4_save_path;     // 录像保存路径
    private String  stream_replace;    // 流 ID 替换
    private Integer modify_stamp;      // 时间戳修正（毫秒）
}
```

**这意味着**：`on_publish` 不只是"准入闸门"，还是**一次性的采集策略下发通道**。

> "你允许推流，并且我要求：这路流**不录 mp4**、**不要音频**、切片按 300 秒存到 D 盘。"

**对我们的价值**：现在录像是在推流后由 API 手工开；如果照搬这个机制，
就可以在**通道表里配"是否自动录像"**，推流一进来 ZLM 就自动按策略执行。
这是"网关能管住视频"的更强证明，而且实现成本很低（改一个返回类 + 读通道配置）。

---

### ② Hook 参数用 **typed DTO** 而不是 Map

我们现在的写法：

```java
private void onPublish(Map<String, Object> body) {
    String app = str(body, "app");          // 字符串取值，拼错了运行时才发现
    Integer tracks = intOrNull(body, "tracks");
}
```

WVP 的写法（每个钩子一个参数类，继承 `HookParam`）：

```java
public class OnPublishHookParam extends HookParam {
    private String id;
    private String app;
    private String stream;
    private String ip;
    private String params;
    private int    port;
    private String schema;
    private String vhost;
}
```

配套：`OnPlayHookParam`、`OnStreamChangedHookParam`、`OnRecordMp4HookParam`、
`OnStreamNoneReaderHookParam`、`OnStreamNotFoundHookParam`、`OnRtpServerTimeoutHookParam` 等。

**照搬理由**：字段有 IDE 补全、能一眼看出 ZLM 到底传了什么、后续加字段不会漏解析。
**成本**：每个钩子一个类，共 6 个，纯机械劳动 ~1 小时。

---

### ③ ZLM API 封装的分层方式

WVP 的 `ZLMRESTfulUtils`（32KB）结构：

```java
// 底层：统一发送（GET/POST，带重试与回调）
public String sendPost(MediaServer mediaServer, String api, Map<String,Object> param, RequestCallback callback);
public byte[] sendGetForImg(...);

// 中层：语义化方法，返回统一包装
public ZLMResult<?>   isMediaOnline(MediaServer ms, String app, String stream, String schema);
public ZLMResult<JSONArray>  getMediaList(MediaServer ms, String app, String stream);
public ZLMResult<JSONObject> getMediaInfo(MediaServer ms, String app, String schema, String stream);
public ZLMResult<?>   setServerConfig(MediaServer ms, Map<String,Object> param);
public ZLMResult<FlagData> delStreamProxy(MediaServer ms, String key);
```

**三个要点值得学**：
1. **统一入参都是 `Map<String,Object>`** → 不需要为每个 API 建 DTO
2. **统一返回 `ZLMResult<T>`** → 上层不关心 ZLM 的返回结构差异（我们踩过"data 是数组/对象/没有"的坑）
3. **每个 API 一个语义化方法名** → 业务代码里不出现魔法字符串 `"getMediaList"`

我们当前 `ZlmApiClient` 已有 `call() + 语义方法`，方向一致；
**缺的是 ③ 的第 2 点**：把 `data` 类型差异收敛到 `ZlmResult<T>` 里（我们是在使用处各自 `dataList()/dataMap()` 处理）。

---

### ④ 播放鉴权：URL 参数 + 服务端校验（callId 模式）

WVP 的实现极其简单（`MediaServiceImpl`）：

```java
public boolean authenticatePlay(String app, String stream, String callId) {
    if (app == null || stream == null) return false;
    if (RTP_APP.equals(app)) return true;

    StreamAuthorityInfo info = redisCatchStorage.getStreamAuthorityInfo(app, stream);
    if (info == null || info.getCallId() == null) {
        return true;                    // ★ 没有登记鉴权信息的流 → 直接放行
    }
    return info.getCallId().equals(callId);
}
```

配套机制：
1. 平台生成播放地址时，把 `callId` 拼进 URL 的 **params** 参数
2. ZLM 播放时把 `params` 原样塞进 `on_play` 回调
3. 平台解析 `params` 里的 `callId`，查 Redis 会话比对

**⭐ 一个重要设计选择（两种鉴权策略）**

| 策略 | 行为 | 优点 | 缺点 |
|---|---|---|---|
| **默认放行**（WVP 选的） | 未登记鉴权信息的流 → 允许播放 | 兼容性好，老流不受影响 | 新流的"裸地址"仍可播 |
| **默认拒绝**（我们该选的） | 没有合法签名 → 拒绝播放 | 安全闭环彻底 | 所有播放地址都必须经平台签发 |

**我要照搬的是"机制"（URL 参数 + on_play 校验），但策略选"默认拒绝"。**
另外把它的 Redis 会话换成**无状态 HMAC 签名**（无中间件也能防篡改 + 防过期）。

---

### ⑤ 拉流代理模块（完整可照搬的骨架）

`streamProxy` 包共 10 个文件，是一个**完整的小 CRUD 模块**：

```
bean/StreamProxy.java              实体
bean/StreamProxyParam.java         接口入参
controller/StreamProxyController.java
dao/StreamProxyMapper.java
dao/provider/StreamProxyProvider.java
service/IStreamProxyService.java
service/impl/StreamProxyServiceImpl.java     14.6KB 核心
service/IStreamProxyPlayService.java
service/impl/StreamProxyPlayServiceImpl.java
```

**实体关键字段**（`StreamProxy extends CommonGBChannel`）：

```java
private int    id;
private String type;            // default(流媒体直接拉) / ffmpeg(用 ffmpeg 拉)
private String app;
private String stream;
private String mediaServerId;   // 当前拉流所在媒体节点
private String srcUrl;          // ★ 拉流源地址（对应我们 biz_channel.pull_url）
private Integer timeout;        // 超时秒数
// 还有 enable_audio / enable_mp4 等控制项
```

**接口设计**（可直接照搬语义）：

```
GET    /api/proxy/list        列表
GET    /api/proxy/one         详情
POST   /api/proxy/add         新增代理
POST   /api/proxy/update      修改
DELETE /api/proxy/del         删除并停止代理
GET    /api/proxy/start       启动（真正去 ZLM addStreamProxy）
GET    /api/proxy/stop        停止（ZLM delStreamProxy）
```

**对我们的意义**：正好补上"`biz_channel.pull_url` 字段没人用"的缺口。
我们的版本可以更简单 —— 不需要独立表，直接**用通道表的 `pull_url`**，
加两个接口 `POST /channels/{id}/pull` 与 `/unpull` 即可，但**接口语义和状态管理照抄它**。

---

## 三、值得学思路、但不必照抄代码的 3 处

### ① Spring Event 解耦（架构更清晰）

WVP 的 `media/event` 包里有 `MediaArrivalEvent`、`ChannelOnlineEvent`、等等。
钩子监听器只负责"把 ZLM 事件翻译成内部事件"，真正的业务逻辑（更新通道状态、录像、通知）由事件监听器订阅。

**好处**：一个事件可以有多个订阅者，互不干扰；加功能不用改钩子代码。
**是否采纳**：中期可以考虑。我们目前钩子里直接调 service，功能少时更直观。
**建议**：写进架构文档作为"二期演进方向"，答辩时讲"当前规模下直接调用更直观，事件总线是扩展点"。

### ② 把 ZLM 抽象成 `MediaServer` 对象（多节点扩展点）

WVP 所有 API 调用都带一个 `MediaServer mediaServer` 参数 —— 这是它支持多节点的根。
我们单机不需要，但**在 `ZlmProperties` 上预留 `mediaServerId` 字段**、架构图里画多节点扩展点，成本几乎为零、显得有前瞻性。

### ③ 统一响应 + 全局异常

它的 `R<T>` / `ResultCode` / `GlobalExceptionHandler` 与我们 `Result` / `ErrorCode` / `GlobalExceptionHandler` **几乎一模一样** —— 说明我们的设计是对的，无需改动，只需在对标时说明"与 WVP 一致"。

---

## 四、明确不照搬的（附理由）

| 不照搬 | 理由 |
|---|---|
| GB28181 SIP 信令（jain-sip + 一整包） | 三周做不深；媒体层已具备收 PS 流能力 |
| JT1078 车载协议 | 与本项目场景无关 |
| 多 ZLM 节点 / Redis 集群 | 单机演示；预留扩展点即可 |
| Spring Security + JWT（`conf/security`） | 调用方是系统不是人；`api-key` 直查库更简单可读 |
| 前端 `web/` 工程（Vue 完整后台） | 我们只做一页轻量台账页 |
| 录像计划 / 云录像 / 存储管理 / PTZ / 报警 / 分组 / 区域 / 级联 | 非网关核心职责 |

---

## 五、对我们的改造清单（按 ROI 排序）

| # | 改造 | 照搬自 | 收益 | 工作量 |
|---|---|---|---|---|
| **1** | **Hook 返回体支持 `enable_mp4` / `enable_audio`** | `HookResultForOnPublish` | **新能力**：通道可配"是否自动录像"，推流准入即下发策略 | 2h |
| **2** | Hook 参数改 typed DTO（6 个类） | `OnPublishHookParam` 等 | 健壮性 + 可读性，为字段增加打基础 | 1h |
| **3** | **播放鉴权：URL 签名 + on_play 验签（默认拒绝）** | `authenticatePlay` 机制 | 补齐"谁有权看"的闭环 | 1 天 |
| **4** | **拉流代理：`/channels/{id}/pull|unpull`** | `streamProxy` 模块 | 补 `pull_url` 缺口，支持老式 RTSP 摄像机 | 0.5 天 |
| **5** | ZLM 返回统一收敛为 `ZlmResult<T>` | `ZLMRESTfulUtils` | 消除 data 类型差异处理 | 2h |
| 6 | （可选）Spring Event 解耦 | `media/event` | 架构演进 | 2h |

---

## 六、答辩素材：一张"对标表"

| 能力 | WVP-PRO | 本项目 | 说明 |
|---|---|---|---|
| 媒体面/业务面分离 | ✅ | ✅ | 同构 |
| ZLM API 封装 | ✅ 32KB utils | ✅ ZlmApiClient | 同构，我们更精简 |
| Webhook 接入 | ✅ 9 个钩子 | ✅ 6 个钩子 | 同构 |
| 推流鉴权 | ✅ | ✅ | 同构 |
| 播放鉴权 | ✅ callId + Redis | ⏳ 计划无状态签名 | **机制同、实现更轻** |
| 准入时下发采集策略 | ✅ enable_mp4/audio | ⏳ 计划照搬 | **照搬** |
| 拉流代理 | ✅ 完整模块 | ⏳ 计划简化为通道字段 | 简化照搬 |
| API 客户端 | ✅ UserApiKey + JWT | ✅ api-key 直查库 | 简化 |
| 国标/JT1078/多节点 | ✅ | ❌ | 范围外 |

**一句话话术**：
> "我们与 WVP-PRO 同构，但做了三处有意识的简化：用无状态签名替代 Redis 会话、
> 用 api-key 直查替代 Spring Security JWT、用通道字段替代独立拉流代理表。
> 同时照搬了它最实用的两个机制 —— **on_publish 准入时下发采集策略**、
> **on_play 播放鉴权**，这两点是'网关能管住视频'的核心。"
