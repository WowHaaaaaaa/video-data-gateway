-- ############################################################################
-- ⚠️ 历史归档脚本 —— 新环境请勿执行本文件！
--
--   全新环境请只执行：backend/sql/00_init_all.sql
--   （它已包含本文件及后续所有增量改动，可重复执行）
--
--   本文件保留的原因：它是这个字段/这张表"为什么加进来"的原始记录，
--   设计文档（docs/10、docs/05）会引用它作为演进依据。
-- ############################################################################
-- ============================================================
-- 视频数据网关 · 数据库初始化脚本
-- MySQL 8.0+   执行方式：mysql -uroot -p123456 < sql\01_schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS `video_gateway`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `video_gateway`;

-- ------------------------------------------------------------
-- 1. 设备主数据
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_device`;
CREATE TABLE `biz_device` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `device_code`  VARCHAR(64)     NOT NULL COMMENT '设备编码（唯一）',
    `device_name`  VARCHAR(128)    NOT NULL COMMENT '设备名称',
    `device_type`  VARCHAR(32)     NOT NULL DEFAULT 'RTMP' COMMENT '接入类型：RTMP/RTSP/GB28181',
    `manufacturer` VARCHAR(64)              DEFAULT NULL COMMENT '厂商',
    `ip_address`   VARCHAR(64)              DEFAULT NULL COMMENT '设备 IP',
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0-离线 1-在线',
    `enabled`      TINYINT         NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
    `remark`       VARCHAR(255)             DEFAULT NULL COMMENT '备注',
    `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_code` (`device_code`)
) ENGINE=InnoDB COMMENT='视频设备';

-- ------------------------------------------------------------
-- 2. 通道主数据（1 设备 N 通道）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_channel`;
CREATE TABLE `biz_channel` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属设备',
    `channel_code`     VARCHAR(64)     NOT NULL COMMENT '通道编码',
    `channel_name`     VARCHAR(128)    NOT NULL COMMENT '通道名称',
    `stream_app`       VARCHAR(64)     NOT NULL DEFAULT 'live' COMMENT 'ZLM 应用名',
    `stream_id`        VARCHAR(64)     NOT NULL COMMENT 'ZLM 流 ID（推流标识）',
    `pull_url`         VARCHAR(512)             DEFAULT NULL COMMENT '源拉流地址（可选）',
    `status`           TINYINT         NOT NULL DEFAULT 0 COMMENT '0-离线 1-在线 2-推流中',
    `enabled`          TINYINT         NOT NULL DEFAULT 1,
    `last_online_time` DATETIME                 DEFAULT NULL COMMENT '最近上线时间',
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stream_key` (`stream_app`, `stream_id`),
    UNIQUE KEY `uk_channel_code` (`channel_code`),
    KEY `idx_device_id` (`device_id`)
) ENGINE=InnoDB COMMENT='视频通道';

-- ------------------------------------------------------------
-- 3. 流会话（聚合表：一次推流的完整生命周期）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_stream_session`;
CREATE TABLE `biz_stream_session` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `session_no`   VARCHAR(64)     NOT NULL COMMENT '会话单号',
    `channel_id`   BIGINT UNSIGNED NOT NULL,
    `stream_key`   VARCHAR(160)    NOT NULL COMMENT 'app/stream',
    `client_ip`    VARCHAR(64)              DEFAULT NULL COMMENT '推流方 IP',
    `track_count`  INT                      DEFAULT 1 COMMENT '媒体轨道数',
    `start_time`   DATETIME        NOT NULL COMMENT '推流开始',
    `end_time`     DATETIME                 DEFAULT NULL COMMENT '推流结束',
    `duration_sec` INT                      DEFAULT NULL COMMENT '持续秒数',
    `end_reason`   VARCHAR(32)              DEFAULT NULL COMMENT 'NORMAL/TIMEOUT/SERVER_DOWN/REJECTED',
    `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1-进行中 2-正常结束 3-异常结束',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_no` (`session_no`),
    KEY `idx_channel_start` (`channel_id`, `start_time`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='流会话（推流聚合台账）';

-- ------------------------------------------------------------
-- 4. 业务台账（流水表：所有业务事件）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_ledger`;
CREATE TABLE `biz_ledger` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `biz_no`      VARCHAR(64)     NOT NULL COMMENT '业务流水号',
    `event_type`  VARCHAR(32)     NOT NULL COMMENT '事件类型',
    `event_time`  DATETIME(3)     NOT NULL COMMENT '事件发生时间',
    `device_id`   BIGINT UNSIGNED          DEFAULT NULL,
    `channel_id`  BIGINT UNSIGNED          DEFAULT NULL,
    `channel_code` VARCHAR(64)             DEFAULT NULL COMMENT '通道编号快照（台账写入时固化）',
    `stream_key`  VARCHAR(160)             DEFAULT NULL COMMENT '流标识快照 app/stream',
    `ref_id`      BIGINT UNSIGNED          DEFAULT NULL COMMENT '关联会话/播放/录像 ID',
    `client_id`   BIGINT UNSIGNED          DEFAULT NULL COMMENT '调用方客户端ID（biz_api_client.id）',
    `source`      VARCHAR(32)     NOT NULL DEFAULT 'ZLM' COMMENT 'ZLM/API/TIMER/MANUAL',
    `client_ip`   VARCHAR(64)              DEFAULT NULL,
    `operator`    VARCHAR(64)              DEFAULT NULL COMMENT '操作人',
    `result_code` INT             NOT NULL DEFAULT 0 COMMENT '0-成功 其他-失败码',
    `result_msg`  VARCHAR(255)             DEFAULT NULL,
    `detail`      JSON                     DEFAULT NULL COMMENT '事件明细',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_no` (`biz_no`),
    KEY `idx_event_time` (`event_time`),
    KEY `idx_channel_time` (`channel_id`, `event_time`),
    KEY `idx_type_time` (`event_type`, `event_time`),
    KEY `idx_client_time` (`client_id`, `event_time`)
) ENGINE=InnoDB COMMENT='业务台账（事件流水）';

-- ------------------------------------------------------------
-- 5. 播放会话
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_play_session`;
CREATE TABLE `biz_play_session` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `channel_id`   BIGINT UNSIGNED NOT NULL,
    `stream_key`   VARCHAR(160)    NOT NULL,
    `client_ip`    VARCHAR(64)              DEFAULT NULL,
    `play_type`    VARCHAR(32)              DEFAULT NULL COMMENT 'flv/hls/webrtc',
    `start_time`   DATETIME        NOT NULL,
    `end_time`     DATETIME                 DEFAULT NULL,
    `duration_sec` INT                      DEFAULT NULL COMMENT '观看时长',
    `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1-观看中 2-已结束',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_channel_start` (`channel_id`, `start_time`)
) ENGINE=InnoDB COMMENT='播放会话';

-- ------------------------------------------------------------
-- 6. 录像台账
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_recording`;
CREATE TABLE `biz_recording` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `channel_id`       BIGINT UNSIGNED NOT NULL,
    `stream_key`       VARCHAR(160)    NOT NULL,
    `file_name`        VARCHAR(255)             DEFAULT NULL,
    `file_path`        VARCHAR(512)    NOT NULL,
    `file_size`        BIGINT                   DEFAULT NULL COMMENT '字节',
    `duration_sec`     INT                      DEFAULT NULL,
    `video_start_time` DATETIME                 DEFAULT NULL,
    `video_end_time`   DATETIME                 DEFAULT NULL,
    `source`           VARCHAR(16)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTO',
    `status`           TINYINT         NOT NULL DEFAULT 1 COMMENT '1-录制中 2-已完成 3-异常',
    `file_exists`      TINYINT         NOT NULL DEFAULT 1 COMMENT '0-文件已清理 1-文件存在',
    `purged_time`      DATETIME                 DEFAULT NULL COMMENT '文件清理时间',
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_channel_start` (`channel_id`, `video_start_time`)
) ENGINE=InnoDB COMMENT='录像台账';

-- ------------------------------------------------------------
-- 7. Webhook 订阅者（下游）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_webhook_subscriber`;
CREATE TABLE `biz_webhook_subscriber` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(64)     NOT NULL COMMENT '订阅方名称',
    `callback_url` VARCHAR(512)    NOT NULL COMMENT '回调地址',
    `secret`       VARCHAR(128)    NOT NULL COMMENT 'HMAC 签名密钥',
    `event_types`  VARCHAR(255)    NOT NULL COMMENT '订阅事件，逗号分隔，* 表示全部',
    `enabled`      TINYINT         NOT NULL DEFAULT 1,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='Webhook 订阅者';

-- ------------------------------------------------------------
-- 8. Webhook 推送日志（兼重试队列，唯一索引保证幂等）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_webhook_push_log`;
CREATE TABLE `biz_webhook_push_log` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_id`       VARCHAR(64)     NOT NULL COMMENT '事件唯一 ID',
    `subscriber_id`  BIGINT UNSIGNED NOT NULL,
    `event_type`     VARCHAR(32)     NOT NULL,
    `payload`        JSON            NOT NULL COMMENT '推送报文',
    `http_status`    INT                      DEFAULT NULL,
    `resp_body`      VARCHAR(1024)            DEFAULT NULL,
    `attempt`        INT             NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_time` DATETIME                DEFAULT NULL,
    `status`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待推送 1-成功 2-失败待重试 3-死亡',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_subscriber` (`event_id`, `subscriber_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB COMMENT='Webhook 推送日志（重试队列）';

-- ------------------------------------------------------------
-- 9. API 客户端（对外发凭证，业务平台凭此调用本平台）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `biz_api_client`;
CREATE TABLE `biz_api_client` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `app_key`          VARCHAR(64)     NOT NULL COMMENT '客户端标识（对外公开）',
    `app_secret`       VARCHAR(128)    NOT NULL COMMENT '客户端密钥（生产应存 BCrypt 密文）',
    `client_name`      VARCHAR(128)    NOT NULL COMMENT '客户端名称',
    `contact`          VARCHAR(64)              DEFAULT NULL COMMENT '联系人（对接人）',
    `enabled`          TINYINT         NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
    `expire_time`      DATETIME                 DEFAULT NULL COMMENT '凭证失效时间，NULL 长期有效',
    `allowed_channels` VARCHAR(512)             DEFAULT NULL COMMENT '允许访问的通道ID，逗号分隔；NULL=不限',
    `rate_limit`       INT             NOT NULL DEFAULT 0 COMMENT '每分钟上限，0=不限',
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    `remark`           VARCHAR(255)             DEFAULT NULL,
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key`)
) ENGINE=InnoDB COMMENT='API 客户端';

-- ============================================================
-- 演示种子数据
-- ============================================================
INSERT INTO `biz_device` (`device_code`, `device_name`, `device_type`, `manufacturer`, `ip_address`, `status`)
VALUES ('DEV-CAM-001', '演示摄像机01', 'RTMP', 'DEMO', '127.0.0.1', 0),
       ('DEV-CAM-002', '演示摄像机02', 'RTMP', 'DEMO', '127.0.0.1', 0);

INSERT INTO `biz_channel` (`device_id`, `channel_code`, `channel_name`, `stream_app`, `stream_id`)
VALUES (1, 'CH-CAM-001', '演示摄像机01-主码流', 'live', 'cam01'),
       (2, 'CH-CAM-002', '演示摄像机02-主码流', 'live', 'cam02');

INSERT INTO `biz_webhook_subscriber` (`name`, `callback_url`, `secret`, `event_types`, `enabled`)
VALUES ('演示下游系统', 'http://127.0.0.1:9900/callback', 'demo-secret-key', '*', 1);

INSERT INTO `biz_api_client` (`app_key`, `app_secret`, `client_name`, `enabled`, `remark`)
VALUES ('vwg_demo_app', 'demo-secret-0123456789abcdef', '演示业务平台', 1, '演示用客户端，密钥请勿用于生产'),
       ('vwg_console', 'console-secret-abcdef0123456789', '管理控制台', 1, 'Web 控制台内置凭证，仅供界面使用');
