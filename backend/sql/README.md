# 数据库脚本说明

## 新环境（克隆仓库后第一次跑）——只执行一个文件

```bash
mysql -h127.0.0.1 -P3307 -uroot -p < backend/sql/00_init_all.sql
```

或者用 Navicat：右键连接 → **运行 SQL 文件** → 选中 `00_init_all.sql` → 开始。

它会一次做完这些事：

1. `CREATE DATABASE IF NOT EXISTS video_gateway`（utf8mb4）
2. 建 9 张表（每张先 `DROP TABLE IF EXISTS`，所以**可重复执行**）
3. 写入跑起来必需的种子数据：2 台演示设备、2 路演示通道、2 个 API 客户端、1 条 Webhook 订阅示例
4. 最后跑几句 `SELECT` 自检，直接告诉你建了几张表、几个客户端

**脚本末尾自带自检输出**，执行完看一眼：

| 检查项 | 期望 |
|---|---|
| 表数量 | 9 |
| device / channel | 2 / 2 |
| api_client | 2 |
| `vwg_console` 的 `scope_mode` / `is_admin` | `ALL` / `1` |

> ⚠️ `vwg_console` 必须是 `ALL` + `is_admin=1`，否则 Web 界面里的
> 「API 客户端」「概览统计」两页会被 `AdminOnlyInterceptor` 拦掉（403）。

## 已有环境（升级）——按增量脚本顺序执行

`01`~`09` 是**演进历史**，记录了每次改表加字段的过程，不要在新环境里逐个执行（会与 `00_init_all.sql` 重复）。

| 文件 | 内容 |
|---|---|
| `01_schema.sql` | 初始 9 张表 + 基础种子数据 |
| `02_add_api_client.sql` | API 客户端表 + 台账关联字段 |
| `03_add_recording_purge.sql` | 录像清理相关字段（`file_exists` / `purged_time`） |
| `04_add_console_client.sql` | 内置控制台凭证 `vwg_console` |
| `05_add_ledger_channel_snapshot.sql` | 台账通道快照（`channel_code` / `stream_key`） |
| `06_add_client_contact.sql` | 客户端联系人字段 |
| `07_add_allowed_devices.sql` | 设备级授权 `allowed_devices` |
| `08_add_scope_mode.sql` | 授权模式 `scope_mode`（ALL / SCOPE） |
| `09_add_is_admin.sql` | 管理权限 `is_admin` |

## `00_init_all.sql` 是怎么来的

它**不是手工拼接** `01`~`09` 得到的——那几个文件互相有覆盖（比如 `01` 已经包含 `06` 的 `contact`，
再执行 `06` 会报「字段已存在」），拼起来会失败。

正确做法是：从**当前跑通的数据库**导出结构（`mysqldump --no-data`），再手工补上种子数据，
这样得到的 `00_init_all.sql` 与线上实际结构 **100% 一致**（含所有后加字段）。

已验证：在一个空库里执行后，与线上库比对 —— **表 9/9、列 114/114、索引 36/36 完全一致**，
且连续执行两次不报错。

## 修改表结构时怎么做

1. 新增一个编号更大的脚本，例如 `10_add_xxx.sql`，只写本次改动（`ALTER TABLE ...`）
2. 在现有库上执行它
3. **同步更新 `00_init_all.sql`**（在新环境重建时会用到），最省事的做法是重新导出：
   ```bash
   mysqldump -h127.0.0.1 -P3307 -uroot -p --no-data --skip-comments --set-gtid-purged=OFF video_gateway
   ```
   把输出的 `CREATE TABLE` 部分替换进 `00_init_all.sql`，种子数据段落保留
4. 在 `docs/05-数据库设计说明.md` 里补充字段说明