-- FlowDesk 工单列表 / 搜索的支撑索引（FD-0007）。
--
-- 目标数据库为 PostgreSQL；本地开发与自动化测试使用 H2 的 PostgreSQL 兼容模式，
-- 本脚本使用的都是两者共有的标准语法（含索引列上的 ASC/DESC）。
--
-- 刻意保持"少而有用"：只加真正能服务本阶段查询计划的索引。
-- 被评估后放弃的候选写在文件末尾，避免后续重复讨论。

-- ① 默认排序：ORDER BY updated_at DESC, id ASC
--
-- 列表接口的默认排序就是它，也是最高频的访问路径（打开工单列表）。
-- 索引列顺序与排序键顺序完全一致，方向也对齐，因此可以直接顺序扫描，
-- 不需要为并列的 updated_at 再排一次序。
CREATE INDEX idx_tickets_updated_at_id ON tickets (updated_at DESC, id ASC);

-- ② 请求人筛选 + 默认排序：WHERE requester_id = ? ORDER BY updated_at DESC, id ASC
--
-- "我提交的工单"是最典型的一个筛选条件，且几乎总是与默认排序一起出现。
-- 把 requester_id 放在最左列，等值条件先收敛，其余列直接提供有序性，
-- 使这条查询可以只走索引定位 + 顺序读取，不必再排序。
CREATE INDEX idx_tickets_requester_updated_at_id ON tickets (requester_id, updated_at DESC, id ASC);

-- 未被采纳的候选（以及原因）：
--
-- * (category, ...) / (priority, ...)：cardinality 太低（分类 5 个取值、优先级 4 个取值），
--   单独等值筛选的选择性很差，优化器通常宁可走 ① 的顺序扫描再过滤；
--   为一个低选择性条件多维护一棵索引，写入放大得不偿失。
--
-- * status：V1 已有 idx_tickets_status_updated_at (status, updated_at)，本阶段状态筛选直接复用，
--   不重复建立同前缀索引。
--
-- * assignee_id：V1 已有 idx_tickets_assignee_status (assignee_id, status)，等值筛选可复用。
--
-- * keyword 包含搜索：LIKE '%...%' 无法使用 B 树索引（前缀不确定），
--   本阶段接受全表扫描；PostgreSQL 侧的 pg_trgm / 全文检索属于后续优化，见 ADR 0004。
--
-- * created_at 排序：V1 的 idx_tickets_created_at (created_at) 已能提供按创建时间定位的能力，
--   本阶段不再叠加 (created_at DESC, id) 复合索引。
