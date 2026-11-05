-- ============================================================
-- 迁移脚本 03：录像台账增加"文件是否已清理"标记
-- 设计取舍：台账记录永不删除（append-only），只清理磁盘文件。
--           这样既释放存储，又保留"曾录过这段录像"的审计事实。
-- ============================================================

USE `video_gateway`;

ALTER TABLE `biz_recording`
    ADD COLUMN `file_exists` TINYINT NOT NULL DEFAULT 1 COMMENT '0-文件已清理 1-文件存在' AFTER `status`,
    ADD COLUMN `purged_time` DATETIME DEFAULT NULL COMMENT '文件清理时间' AFTER `file_exists`;