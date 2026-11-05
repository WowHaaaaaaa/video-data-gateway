-- 07: API 客户端支持「设备级授权」
-- 语义：allowed_devices 里列出的设备，其名下所有通道（含以后新增的）都视为已授权；
--       allowed_channels 是单通道授权（与设备授权取并集）。
--       两者都为空 = 不限（管理控制台就是这种）。
ALTER TABLE biz_api_client
  ADD COLUMN allowed_devices VARCHAR(512) DEFAULT NULL
  COMMENT '允许访问的设备ID，逗号分隔；该设备名下所有通道（含未来新增）都视为已授权；NULL=不限'
  AFTER allowed_channels;