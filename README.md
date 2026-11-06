# 视频数据网关（Video Data Gateway）

> 应届生考核项目 · ZLMediaKit 接入 + 业务台账 + REST API + Webhook
> 参考架构：WVP-PRO 的分层思路（媒体层 / 业务层解耦）

## 一、这是什么

一个**独立的视频数据网关**：把视频源（RTMP/RTSP/GB28181）统一接入 ZLMediaKit 流媒体服务器，
由本服务负责**管业务、记账、对外开接口、主动推送**。

```
摄像机/ffmpeg ──推流──> ZLMediaKit（媒体面：收流/转协议/分发/录像）
                              │  REST API（网关调它）
                              │  Webhook（它回调网关）
                              ▼
                      视频数据网关（业务面：设备通道管理/业务台账/REST API/Webhook 推送）
                              │
                              ▼
                        MySQL（台账） + 前端页面 + 下游业务系统
```

**关键认知**：视频流不经过本服务，本服务只处理信令与元数据（JSON）。

## 二、技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 17 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 接口文档 | Knife4j 4.5.0（http://127.0.0.1:8088/doc.html） |
| 流媒体 | ZLMediaKit（MediaServer.exe，独立进程） |
| 前端 | Vue3 + Element Plus（本地静态化，无构建） |

## 三、目录结构

```
视频数据网关/                        （外层文件夹名建议改为 video-data-gateway）
├── backend/                         后端（Spring Boot，独立 Maven 工程）
│   ├── pom.xml
│   ├── src/main/java/com/lining/vgw/
│   │   ├── common/                  统一返回体、异常、traceId、api-key 上下文
│   │   ├── config/                  鉴权拦截器、CORS、MyBatis-Plus、OpenAPI
│   │   ├── controller/              对外 REST 接口 + ZLM 回调入口
│   │   ├── service/                 业务逻辑（台账/会话/客户端/钩子处理）
│   │   ├── mapper/                  MyBatis-Plus Mapper
│   │   ├── domain/                  实体
│   │   └── integration/zlm/         ZLMediaKit 客户端与配置
│   ├── src/main/resources/application.yml
│   ├── sql/00_init_all.sql          【一键建库】9 张表 + 种子数据（新环境只跑这个）
│   ├── sql/01~09_*.sql              演进历史（已归档，新环境勿执行）
│   ├── sql/README.md                三种执行方式与坑点
│   └── target/video-data-gateway.jar  构建产物
├── frontend/                        前端（Vue3 + Element Plus，无构建，与后端完全分离）
│   ├── index.html
│   ├── app.js                       导航与 8 个页面
│   ├── style.css
│   └── vendor/                      本地依赖（vue / element-plus / flv.js）
├── tools/                           本地运行时（**不入库**，见下方"获取依赖"）
│   ├── zlm/                         ZLMediaKit + config.ini + www/
│   ├── ffmpeg/                      ffmpeg
│   ├── maven/                       Maven 3.9.16
│   ├── mysql/                       MySQL 8.0 免安装
│   └── maven-conf/settings.xml      阿里云镜像
├── scripts/                         构建、启动、推流、检测、依赖拉取脚本
└── docs/                            设计与过程文档（01~16）
```

> **为什么 tools/ 不在仓库里**：ZLMediaKit / MySQL / Maven / ffmpeg 加起来约 1.6 GB，
> 属于"机器环境"而非"源码"。仓库只放代码，依赖用一条命令重新拉取（见下一节）。
> 录像 mp4、日志、构建产物同样不入库，规则见 `.gitignore`。

## 四、快速开始

### 0. 获取本地依赖（克隆后第一次运行才需要）

```bat
REM 下载 ZLMediaKit / ffmpeg / Maven / MySQL 到 tools\（国内镜像，约 1.6 GB 解压后）
node scripts\fetch-deps.mjs all

REM 下载前端本地依赖到 frontend\vendor\（Vue / Element Plus / flv.js / hls.js）
node scripts\fetch-web-libs.mjs
```

建库建表：**一条命令**（建库 + 9 张表 + 种子数据 + 自检）

```bat
scripts\init-db.bat
```

脚本会自己找 `tools\mysql` 里的客户端、检查 3307 端口、并在发现库里已有数据时要求确认。
不想用脚本也可以手动执行那一个 SQL 文件：

```bat
mysql -h127.0.0.1 -P3307 -uroot -p --default-character-set=utf8mb4 < backend\sql\00_init_all.sql
```

> ⚠️ 这个 SQL 里每张表都是 `DROP TABLE IF EXISTS` 再建，**只对空库用**。
> `backend\sql\01`~`09` 是演进历史，新环境不用逐个执行。详见 `backend\sql\README.md`。
数据库默认连 `127.0.0.1:3307/video_gateway`，账号口令可用环境变量覆盖：
`VGW_DB_USER` / `VGW_DB_PASSWORD`；ZLM 的 secret 用 `VGW_ZLM_SECRET` 覆盖。

### 1. 启动

```bat
REM 1. 启动 MySQL（3307）
scripts\start-mysql.bat

REM 2. 启动流媒体服务器
scripts\start-zlm.bat

REM 3. 启动网关（含前端）
scripts\start-gateway.bat

REM 4. 打开管理界面（首次要求输入 API Key，演示用 vwg_demo_app）
start http://127.0.0.1:8088/

REM 5. 推一路测试流（另开窗口）
scripts\push-test.bat cam01
```

一键起全套：`scripts\start-all.bat`　｜　重新构建后端：`scripts\build-backend.bat`

**前端**：`frontend/` 目录（Vue3 + Element Plus 本地化，**无构建步骤**，改完刷新浏览器即生效）
菜单：概览 / 视频资源（设备·通道）/ 实时视频 / 业务台账（事件流水·流会话·录像）/ API 客户端 / 接口文档
设计说明见 `docs/09-前端导航与页面设计.md`

## 五、端口占用

| 端口 | 用途 |
|---|---|
| 80 | ZLMediaKit HTTP / FLV / HLS / API |
| 554 | RTSP |
| 1935 | RTMP |
| 443 | ZLM HTTPS（已启用） |
| 9000 | ZLM WebSocket / HLS |
| 10000 | ZLM RTP 收流（GB28181 PS 流） |
| 8088 | 视频数据网关 REST API |
| 3307 | MySQL（本机 3306 被公司预装实例占用，故错开） |

## 六、进度

| 阶段 | 状态 |
|---|---|
| D1 环境就绪（ZLM + ffmpeg + 推流/播放） | ✅ |
| D2 Spring Boot 骨架（统一返回体/异常/日志/接口文档） | ✅ |
| D3 建表 + 设备通道 CRUD | ✅ |
| D4 ZLM Webhook 接入（推流鉴权 + 会话 + 台账） | ✅ |
| D5 台账查询 + 统计看板 | ✅ |
| D6 播放链路 + 播放事件（PLAY_START/PLAY_STOP） | ✅（token 鉴权待做） |
| D7 录像：按需开关 + 录像台账 + 可回放 URL | ✅ |
| **D8 API 客户端管理 + `api-key` 请求头鉴权** | ✅ |
| D9 播放地址签名 + on_play 验签 | 待做 |
| **D10 前端管理界面（5 菜单 / 7 页面）** | ✅ |
| D11 出口 Webhook 推送（签名/幂等/重试） | 待做 |
| D12 演示材料（脚本/录屏/PPT） | 待做 |

> ⚠️ **调用约定**：自 D8 起，所有 `/api/v1/**` 接口需带请求头 `api-key: <appKey>`，
> 唯一放行的是 `/api/v1/health`。种子客户端：`vwg_demo_app`。
> 本地调试可设 `vgw.security.enabled=false` 关闭鉴权。
>
> WVP-PRO 的能力比对与取舍见 `docs/06-WVP-PRO能力比对与取舍.md`

> 详细验证记录与踩坑见 `docs/03-进度日志.md`
>
> Git 用法（提交 / 分支 / PR 协作 / 冲突处理 / 回滚）见 `docs/16-Git版本管理与协作.md`

