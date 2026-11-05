-- ============================================================
-- 迁移脚本 05：台账增加"通道标识快照"
--
-- 为什么需要：台账是审计账，必须记录"事发当时"的通道标识。
--   若只存 channel_id 靠事后 join：
--     · 通道改名 → 历史台账全部变成新名字（历史失真）
--     · 通道删除 → 查不到，前端显示 #id
--     · 通道重名 → 根本分不清是哪个
-- 因此冗余存两份唯一标识：
--   channel_code  通道编号（业务唯一，人看得懂）
--   stream_key    流标识 app/stream（技术唯一，与 ZLM 日志一一对应）
-- ============================================================

USE `video_gateway`;

ALTER TABLE `biz_ledger`
    ADD COLUMN `channel_code` VARCHAR(64)  DEFAULT NULL COMMENT '通道编号快照' AFTER `channel_id`,
    ADD COLUMN `stream_key`   VARCHAR(160) DEFAULT NULL COMMENT '流标识快照 app/stream' AFTER `channel_code`,
    ADD KEY `idx_stream_key` (`stream_key`);

-- 回填历史数据：直接从通道表取（注意这里 JOIN 不过滤 deleted，
-- 这样已被逻辑删除的通道也能补全快照）
UPDATE `biz_ledger` l
    JOIN `biz_channel` c ON c.`id` = l.`channel_id`
SET l.`channel_code` = c.`channel_code`,
    l.`stream_key`   = CONCAT(c.`stream_app`, '/', c.`stream_id`)
WHERE l.`channel_code` IS NULL;