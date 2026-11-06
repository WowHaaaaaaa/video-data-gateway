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
-- 迁移脚本 02：新增 API 客户端表 + 台账增加调用方维度
-- 适用：已部署过 01_schema.sql 的库（幂等性有限，重复执行 ALTER 会报错，属正常）
-- 说明：01_schema.sql 已同步包含本脚本内容，全新环境只需执行 01 即可
-- ============================================================

USE `video_gateway`;

-- ------------------------------------------------------------
-- 9. API 客户端（对外发凭证，业务平台凭此调用本平台）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `biz_api_client` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `app_key`          VARCHAR(64)     NOT NULL COMMENT '客户端标识（对外公开，可写进请求头）',
    `app_secret`       VARCHAR(128)    NOT NULL COMMENT '客户端密钥（生产环境应存 BCrypt 密文，本期演示存明文）',
    `client_name`      VARCHAR(128)    NOT NULL COMMENT '客户端名称，如"XX业务平台"',
    `enabled`          TINYINT         NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用（停用后立即失效）',
    `expire_time`      DATETIME                 DEFAULT NULL COMMENT '凭证失效时间，NULL 表示长期有效',
    `allowed_channels` VARCHAR(512)             DEFAULT NULL COMMENT '允许访问的通道ID，逗号分隔；NULL 表示不限',
    `rate_limit`       INT             NOT NULL DEFAULT 0 COMMENT '每分钟调用上限，0 表示不限',
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    `remark`           VARCHAR(255)             DEFAULT NULL,
    `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 唯一索引：app_key 就是客户端身份，绝不能重复
    UNIQUE KEY `uk_app_key` (`app_key`)
) ENGINE=InnoDB COMMENT='API 客户端';

-- ------------------------------------------------------------
-- 台账增加"谁调的"维度
-- 为什么加在台账而不是只加在播放表：
--   台账是流水账，每一次对外调用都要留痕；只有带上 client_id，
--   才能回答"本月 A 系统调了多少次、B 系统调了多少次"。
-- ------------------------------------------------------------
ALTER TABLE `biz_ledger`
    ADD COLUMN `client_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '调用方客户端ID（biz_api_client.id）' AFTER `ref_id`,
    -- 联合普通索引：先按客户端、再按时间查，这是最典型的查询姿势
    ADD KEY `idx_client_time` (`client_id`, `event_time`);

-- ------------------------------------------------------------
-- 演示用客户端（生产环境密钥必须随机生成 + 加密存储）
-- ------------------------------------------------------------
INSERT INTO `biz_api_client` (`app_key`, `app_secret`, `client_name`, `enabled`, `remark`)
VALUES ('vwg_demo_app', 'demo-secret-0123456789abcdef', '演示业务平台', 1, '演示用客户端，密钥请勿用于生产');
