-- 08: 区分「不限」与「一个都不授权（= 全部拒绝）」
-- 之前语义：两个列表都空 = 不限。但这让"清空勾选"变得危险 —— 用户以为是"没权限"，
-- 实际是"全部放开"。新增 scope_mode 明确表达意图：
--   ALL    = 不限（可访问全部通道，管理控制台用这个）
--   SCOPE  = 按 allowed_devices / allowed_channels 授权；两个都空 = 一个都不授权 = 全拒
ALTER TABLE biz_api_client
  ADD COLUMN scope_mode VARCHAR(16) NOT NULL DEFAULT 'ALL'
  COMMENT 'ALL=不限; SCOPE=按设备/通道授权（列表都空则全拒）'
  AFTER allowed_devices;

-- 存量数据回填：已经配过设备/通道的客户端属于 SCOPE，其余保持 ALL（不限）
UPDATE biz_api_client
   SET scope_mode = 'SCOPE'
 WHERE (allowed_channels IS NOT NULL AND allowed_channels <> '')
    OR (allowed_devices  IS NOT NULL AND allowed_devices  <> '');