-- ============================================================
-- 迁移脚本 06：API 客户端增加"联系人"字段
-- 用途：凭证发给业务方后，出问题要能找到对接人
-- ============================================================

USE `video_gateway`;

ALTER TABLE `biz_api_client`
    ADD COLUMN `contact` VARCHAR(64) DEFAULT NULL COMMENT '联系人（对接人姓名/工号）' AFTER `client_name`;