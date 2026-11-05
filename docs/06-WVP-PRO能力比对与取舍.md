# WVP-PRO 能力比对与取舍

> 分析对象：`wvp-GB28181-pro-master` v3.0.0（源码 19MB / 1671 文件）
> 技术栈：Spring Boot 3.4.4 + Java 21 + MyBatis + Redis + Spring Security + JWT
> 分析目的：确认我们的架构方向是否正确，并挑选"能用得上、不过量"的能力。

---

## 一、像不像？—— 骨架几乎一模一样

| 维度 | WVP-PRO | 本项目 | 一致性 |
|---|---|---|---|
| 架构定位 | 平台层，媒体交给 ZLM | 网关层，媒体交给 ZLM | ✅ 一致 |
| 对接 ZLM 方式 | REST API + Webhook | REST API + Webhook | ✅ 一致 |
| Webhook 事件 | on_publish / on_play / on_stream_changed / on_none_reader / on_record_mp4 / on_server_started 等 9 个 | 6 个（同类型） | ✅ 一致 |
| 推流鉴权 | on_publish 中校验 | on_publish 中校验 | ✅ 一致 |
| 播放鉴权 | on_play 中校验 | 计划中 | ✅ 方向一致 |
| 后端分层 | controller / service / dao / bean | controller / service / mapper / domain | ✅ 一致 |
| 数据库 | MySQL / PostgreSQL / 达梦 | MySQL 8 | ✅ 一致 |

**结论：我们的架构选择是对的，可以直接对领导说"与 WVP-PRO 同构"。**

## 二、它比我们多了什么（差异即取舍点）

| 能力 | 说明 | 我们是否需要 |
|---|---|---|
| **GB28181 SIP 信令** | 设备注册、目录、INVITE、国标级联（`gb28181` 包 + jain-sip） | ❌ 不做（三周做不深，且已声明为二期） |
| JT1078 车载视频 | 交通部标协议（`jt1078` 包） | ❌ 不做 |
| 多 ZLM 节点集群 | `ZLMMediaNodeServerService` 31KB | ❌ 不做（单机场景） |
| Redis 分布式会话 | 多节点共享状态、消息总线 | ❌ 不做（单体不需要） |
| 用户 / 角色 RBAC | Spring Security + 用户表 + 角色表 | ⚠️ 简化：不做用户体系，只做 API 客户端 |
| 前端工程 | `web/` 目录，Vue | ⚠️ 我们做一页轻量台账页 |
| 录像计划 / 云录像 | `storager` 包、录像计划表 | ❌ 不做（只做按需录像） |
| PTZ 云台控制 | `PtzController` | ❌ 不做 |
| 报警 / 分组 / 区域 / 移动位置 | 多个 Controller | ❌ 不做 |
| 视频回放（GB 录像检索回放） | `PlaybackController` | ❌ 不做 |

---

## 三、⭐ 我们取什么（精选 4 项，不多一个）

### ① API 客户端（`api-key` 鉴权）—— **唯一要新增的功能**

**WVP 的做法**：
- 表 `wvp_user_api_key`：`id / userId / app / apiKey / expiredAt / remark / enable`
- 请求头 `api-key: <密钥>`
- 管理接口 `/api/userApiKey/add|userApiKeys|enable|disable|reset|remark|delete`（7 个动作）
- 鉴权链：请求头 → JWT 过滤器 → 解析出 apiKeyId → 查库校验 → 建立认证

**我们采纳的部分**：
| WVP 的设计 | 我们怎么做 | 理由 |
|---|---|---|
| 请求头 `api-key` | ✅ 照抄（对齐行业习惯） | 简单、curl 可测、易讲 |
| 7 个管理动作 | ✅ 采纳：创建/列表/启停/重置/删除 | 语义完整，工作量小 |
| 密钥绑到"用户" | ❌ 改为**独立客户端**（不引入用户体系） | 我们的调用方是"系统"不是"人" |
| api-key 换 JWT | ⚠️ 简化为直接校验 api-key | 不引入 Spring Security + JWT 复杂度 |
| 过期时间 | ✅ 采纳 `expire_time` | 安全底线 |

### ② 播放地址带凭据 + on_play 校验

**WVP 的做法**：播放地址带上 `?callId=xxx`，ZLM 把 params 原样回调，`on_play` 里解析 callId 查会话是否有效，无效返回 401。

**我们的做法**：同样用"URL 参数 + on_play 校验"，但把凭据换成**无状态签名**：
```
播放地址: http://host/live/cam01.live.flv?clientId=xxx&expire=1234567890&sign=<HMAC>
on_play:  解析 params → 校验签名 + 是否过期 → 无效返回非 0（ZLM 拒绝播放）
```
> 为什么不用 WVP 的 callId + 会话：它需要 Redis 存会话，我们无 Redis。
> 无状态签名更轻，且天然支持"防篡改 + 防重放"，答辩更好讲。

### ③ `on_stream_not_found` 钩子（按需拉流）

**WVP 的做法**：有人要看某路流时流不存在 → ZLM 回调 → 平台去把源拉起来。

**价值**：老摄像机不常推流时，不用一直拉，有人看才拉，省资源。
**我们的做法**：本期先不实现，但把钩子地址预留（config.ini 里 `on_stream_not_found=`），
作为"已知可扩展点"写进文档。→ **不写代码，只写进架构说明**。

### ④ 对外接口的"极简风格"

**WVP**：`/api/v1/stream/start`、`/api/v1/stream/stop`、`/api/v1/stream/touch`
**我们**：`/api/v1/channels/{id}/play-address`（语义更清晰），保留对外的客户端管理接口。

---

## 四、明确不取（写进文档，是范围控制的加分项）

| 不取 | 一句话理由 |
|---|---|
| GB28181 SIP 信令 | 独立大工程，媒体层已具备收 PS 流能力，信令层列二期 |
| JT1078 | 车载场景与本项目无关 |
| 多 ZLM 节点 / Redis 集群 | 单机演示场景，架构图保留扩展点即可 |
| 用户 RBAC | 调用方是系统不是人，用 API 客户端即可 |
| 录像计划 / 云录像 / 回放 | 非网关核心职责 |
| PTZ / 报警 / 分组 / 区域 | 业务功能，与"数据网关"定位无关 |

---

## 五、对标话术（答辩用）

> "我们的架构与 WVP-PRO 同构：媒体面交给 ZLMediaKit，业务面自研。
> WVP-PRO 面向的是国标安防的完整业务闭环（设备注册、目录、级联、回放），
> 体量是数年积累。本项目聚焦'视频数据网关'的核心职责 ——
> **统一接入、推流鉴权、业务台账、对外发凭证与服务**，
> 并借鉴了它成熟的 **api-key 鉴权** 与 **on_publish/on_play 双钩子鉴权** 设计，
> 在播放鉴权上用无状态签名替代其 Redis 会话方案，以适应单机无中间件的部署形态。"

---

## 六、落地清单（本项目要做的）

| # | 功能 | 状态 |
|---|---|---|
| 1 | `biz_api_client` 表 | ✅ 已完成 |
| 2 | `biz_ledger.client_id` 字段 | ✅ 已完成 |
| 3 | API 客户端管理接口（创建/列表/启停/重置/删除） | 进行中 |
| 4 | `api-key` 请求头鉴权拦截器 | 进行中 |
| 5 | 台账自动记录 client_id | 进行中 |
| 6 | 播放地址签名 + on_play 验签 | 待做 |
| 7 | 前端台账页 + 客户端管理页 | 待做 |
