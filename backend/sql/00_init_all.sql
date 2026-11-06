-- ============================================================
-- 视频数据网关 · 数据库一键初始化脚本
-- ============================================================
-- 用途：新环境（别人 clone 本仓库后）只需要执行这一个文件，
--       就能建好库、9 张表、以及跑起来必需的种子数据。
--
-- 执行方式（任选其一）：
--   mysql -h127.0.0.1 -P3307 -uroot -p < backend/sql/00_init_all.sql
--   Navicat：右键连接 → 运行 SQL 文件 → 选中本文件 → 开始
--
-- 特性：
--   * 幂等：每张表都是 DROP TABLE IF EXISTS 再 CREATE，重复执行不会报错
--     （注意：会清空已有数据，生产环境勿用）
--   * 库名固定为 video_gateway，端口 3307（本机 3306 被公司预装实例占用）
--   * 与 application.yml 的默认值一致：root / 123456
--
-- 与增量脚本的关系：
--   本文件由**当前运行中的数据库**导出生成，已包含 01~09 全部增量改动
--   （含 allowed_devices / scope_mode / is_admin / contact 等后加字段）。
--   backend/sql/01~09 保留作为"演进历史"，新环境不要再逐个执行它们。
--
-- 生成时间：2026-11-06
-- ============================================================

CREATE DATABASE IF NOT EXISTS `video_gateway`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `video_gateway`;

SET NAMES utf8mb4;

-- ============================================================
-- 一、建表（9 张）
-- ============================================================
/*!40103 SET TIME_ZONE='+00:00' */;
DROP TABLE IF EXISTS `biz_api_client`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_api_client` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端标识（对外公开，可写进请求头）',
  `app_secret` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端密钥（生产环境应存 BCrypt 密文，本期演示存明文）',
  `client_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端名称，如"XX业务平台"',
  `contact` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系人（对接人姓名/工号）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '0-停用 1-启用（停用后立即失效）',
  `expire_time` datetime DEFAULT NULL COMMENT '凭证失效时间，NULL 表示长期有效',
  `allowed_channels` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '允许访问的通道ID，逗号分隔；NULL 表示不限',
  `allowed_devices` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '允许访问的设备ID，逗号分隔；该设备名下所有通道（含未来新增）都视为已授权；NULL=不限',
  `scope_mode` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ALL' COMMENT 'ALL=不限; SCOPE=按设备/通道授权（列表都空则全拒）',
  `is_admin` tinyint NOT NULL DEFAULT '0' COMMENT '1=管理权限（超管），可调用全部接口（含客户端管理/统计）；与通道范围无关',
  `rate_limit` int NOT NULL DEFAULT '0' COMMENT '每分钟调用上限，0 表示不限',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_key` (`app_key`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='API 客户端';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_channel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_channel` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `device_id` bigint unsigned NOT NULL COMMENT '所属设备',
  `channel_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '通道编码',
  `channel_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '通道名称',
  `stream_app` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'live' COMMENT 'ZLM 应用名',
  `stream_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'ZLM 流 ID（推流标识）',
  `pull_url` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '源拉流地址（可选）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-离线 1-在线 2-推流中',
  `enabled` tinyint NOT NULL DEFAULT '1',
  `last_online_time` datetime DEFAULT NULL COMMENT '最近上线时间',
  `deleted` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stream_key` (`stream_app`,`stream_id`),
  UNIQUE KEY `uk_channel_code` (`channel_code`),
  KEY `idx_device_id` (`device_id`)
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='视频通道';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_device`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_device` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '设备编码（唯一）',
  `device_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '设备名称',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RTMP' COMMENT '接入类型：RTMP/RTSP/GB28181',
  `manufacturer` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '厂商',
  `ip_address` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '设备 IP',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-离线 1-在线',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '0-停用 1-启用',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_code` (`device_code`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='视频设备';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_ledger`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `biz_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务流水号',
  `event_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '事件类型',
  `event_time` datetime(3) NOT NULL COMMENT '事件发生时间',
  `device_id` bigint unsigned DEFAULT NULL,
  `channel_id` bigint unsigned DEFAULT NULL,
  `channel_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '通道编号快照',
  `stream_key` varchar(160) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流标识快照 app/stream',
  `ref_id` bigint unsigned DEFAULT NULL COMMENT '关联会话/播放/录像 ID',
  `client_id` bigint unsigned DEFAULT NULL COMMENT '调用方客户端ID（biz_api_client.id）',
  `source` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ZLM' COMMENT 'ZLM/API/TIMER/MANUAL',
  `client_ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `operator` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作人',
  `result_code` int NOT NULL DEFAULT '0' COMMENT '0-成功 其他-失败码',
  `result_msg` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `detail` json DEFAULT NULL COMMENT '事件明细',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_no` (`biz_no`),
  KEY `idx_event_time` (`event_time`),
  KEY `idx_channel_time` (`channel_id`,`event_time`),
  KEY `idx_type_time` (`event_type`,`event_time`),
  KEY `idx_client_time` (`client_id`,`event_time`),
  KEY `idx_stream_key` (`stream_key`)
) ENGINE=InnoDB AUTO_INCREMENT=611 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务台账（事件流水）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_play_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_play_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `channel_id` bigint unsigned NOT NULL,
  `stream_key` varchar(160) COLLATE utf8mb4_general_ci NOT NULL,
  `client_ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `play_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'flv/hls/webrtc',
  `start_time` datetime NOT NULL,
  `end_time` datetime DEFAULT NULL,
  `duration_sec` int DEFAULT NULL COMMENT '观看时长',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-观看中 2-已结束',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_channel_start` (`channel_id`,`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='播放会话';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_recording`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_recording` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `channel_id` bigint unsigned NOT NULL,
  `stream_key` varchar(160) COLLATE utf8mb4_general_ci NOT NULL,
  `file_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `file_path` varchar(512) COLLATE utf8mb4_general_ci NOT NULL,
  `file_size` bigint DEFAULT NULL COMMENT '字节',
  `duration_sec` int DEFAULT NULL,
  `video_start_time` datetime DEFAULT NULL,
  `video_end_time` datetime DEFAULT NULL,
  `source` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTO',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-录制中 2-已完成 3-异常',
  `file_exists` tinyint NOT NULL DEFAULT '1' COMMENT '0-文件已清理 1-文件存在',
  `purged_time` datetime DEFAULT NULL COMMENT '文件清理时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_channel_start` (`channel_id`,`video_start_time`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='录像台账';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_stream_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stream_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '会话单号',
  `channel_id` bigint unsigned NOT NULL,
  `stream_key` varchar(160) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'app/stream',
  `client_ip` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '推流方 IP',
  `track_count` int DEFAULT '1' COMMENT '媒体轨道数',
  `start_time` datetime NOT NULL COMMENT '推流开始',
  `end_time` datetime DEFAULT NULL COMMENT '推流结束',
  `duration_sec` int DEFAULT NULL COMMENT '持续秒数',
  `end_reason` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'NORMAL/TIMEOUT/SERVER_DOWN/REJECTED',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-进行中 2-正常结束 3-异常结束',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_channel_start` (`channel_id`,`start_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=66 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流会话（推流聚合台账）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_webhook_push_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_webhook_push_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '事件唯一 ID',
  `subscriber_id` bigint unsigned NOT NULL,
  `event_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL,
  `payload` json NOT NULL COMMENT '推送报文',
  `http_status` int DEFAULT NULL,
  `resp_body` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `attempt` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `next_retry_time` datetime DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-待推送 1-成功 2-失败待重试 3-死亡',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_subscriber` (`event_id`,`subscriber_id`),
  KEY `idx_status_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Webhook 推送日志（重试队列）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_webhook_subscriber`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_webhook_subscriber` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '订阅方名称',
  `callback_url` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '回调地址',
  `secret` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'HMAC 签名密钥',
  `event_types` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '订阅事件，逗号分隔，* 表示全部',
  `enabled` tinyint NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Webhook 订阅者';
/*!40101 SET character_set_client = @saved_cs_client */;


-- ============================================================
-- 二、种子数据（跑起来必需 + 演示用）
-- ============================================================

-- 2.1 演示设备（2 台）
INSERT INTO `biz_device` (`device_code`, `device_name`, `device_type`, `manufacturer`, `ip_address`, `status`, `remark`)
VALUES ('DEV-CAM-001', '演示摄像机01', 'RTMP', 'DEMO', '127.0.0.1', 0, '种子数据：可直接推流 rtmp://127.0.0.1/live/cam01'),
       ('DEV-CAM-002', '演示摄像机02', 'RTMP', 'DEMO', '127.0.0.1', 0, '种子数据：可直接推流 rtmp://127.0.0.1/live/cam02');

-- 2.2 演示通道（2 路，用子查询取设备 id，避免写死自增主键）
INSERT INTO `biz_channel` (`device_id`, `channel_code`, `channel_name`, `stream_app`, `stream_id`, `status`, `enabled`)
SELECT d.id, 'CH-CAM-001', '演示摄像机01-主码流', 'live', 'cam01', 0, 1 FROM `biz_device` d WHERE d.device_code = 'DEV-CAM-001';
INSERT INTO `biz_channel` (`device_id`, `channel_code`, `channel_name`, `stream_app`, `stream_id`, `status`, `enabled`)
SELECT d.id, 'CH-CAM-002', '演示摄像机02-主码流', 'live', 'cam02', 0, 1 FROM `biz_device` d WHERE d.device_code = 'DEV-CAM-002';

-- 2.3 API 客户端（两个都必需）
--   vwg_console : Web 控制台内置凭证 —— 必须 ALL + is_admin=1，否则界面里
--                 「API 客户端 / 概览统计」这两页会被 AdminOnlyInterceptor 拦掉
--   vwg_demo_app: 给第三方演示用的普通凭证 —— ALL 范围但无管理权限
INSERT INTO `biz_api_client`
    (`app_key`, `app_secret`, `client_name`, `contact`, `enabled`, `allowed_channels`, `allowed_devices`, `scope_mode`, `is_admin`, `remark`)
VALUES
    ('vwg_console',  'console-secret-abcdef0123456789', '管理控制台',   NULL, 1, NULL, NULL, 'ALL', 1, 'Web 控制台内置凭证，仅供界面使用'),
    ('vwg_demo_app', 'demo-secret-0123456789abcdef',    '演示业务平台', NULL, 1, NULL, NULL, 'ALL', 0, '演示用客户端，密钥请勿用于生产');

-- 2.4 出站 Webhook 订阅者（功能预留，留一条示例便于理解表结构）
INSERT INTO `biz_webhook_subscriber` (`name`, `callback_url`, `secret`, `event_types`, `enabled`)
VALUES ('演示下游系统', 'http://127.0.0.1:9900/callback', 'demo-secret-key', '*', 0);

-- ============================================================
-- 三、自检（执行完跑这几句确认）
-- ============================================================
-- 表数量应为 9
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'video_gateway';
-- 种子数据
SELECT 'device' AS 类型, COUNT(*) AS 条数 FROM `biz_device`
UNION ALL SELECT 'channel', COUNT(*) FROM `biz_channel`
UNION ALL SELECT 'api_client', COUNT(*) FROM `biz_api_client`
UNION ALL SELECT 'webhook_subscriber', COUNT(*) FROM `biz_webhook_subscriber`;
-- 控制台凭证必须存在且是超管
SELECT app_key, scope_mode, is_admin FROM `biz_api_client` WHERE app_key = 'vwg_console';