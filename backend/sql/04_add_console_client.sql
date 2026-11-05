-- ============================================================
-- 迁移脚本 04：新增"管理控制台"客户端凭证
-- 目的：把「人在界面上操作」与「第三方系统调用」在凭证层面区分开，
--       台账的"客户端"列就能一眼看出请求来自控制台还是外部系统。
-- ============================================================

USE `video_gateway`;

INSERT INTO `biz_api_client` (`app_key`, `app_secret`, `client_name`, `enabled`, `remark`)
VALUES ('vwg_console', 'console-secret-abcdef0123456789', '管理控制台', 1, 'Web 控制台内置凭证，仅供界面使用');

-- 历史数据回填：把"人手工操作"的记录从 API 纠正为 MANUAL
UPDATE `biz_ledger`
SET `source` = 'MANUAL',
    `result_msg` = REPLACE(`result_msg`, '手工', '')
WHERE `result_msg` LIKE '%手工%';