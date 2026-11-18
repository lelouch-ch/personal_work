-- Personal Work Twin: V10__add_context_project.sql
-- 来源：设计文档 11.3 第 3 步与第 9 步、11.6 返回结构。
--
-- 设计文档 8.3 的 pt_context_access_log 没有 project_id，但 build_context 的输入含
-- project_id，且 15.2 要求能回答“某次上下文包含哪些内容”。缺这一列时，事后无法区分
-- 同一次任务里项目内与项目外的召回，也就无法解释为什么某个项目背景没进上下文，
-- 因此补一列而不是把 project_id 混进 request_id。
-- 与 pt_audit_log 同理不设外键：本表在 14.3 的多租户白名单内，允许跨用户查询与迟到写入。

ALTER TABLE pt_context_access_log
    ADD COLUMN project_id char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER agent_client_id;

-- build_context 按 project_id 过滤候选记忆时走 pt_memory_evidence → pt_work_log 两跳。
-- 现有 idx_memory_evidence_memory 只覆盖正向（记忆→证据），反向按工作记录捞证据无可用索引，
-- 会退化为全表扫描。
-- pt_work_log 侧不加索引：V3 的 idx_work_log_user_project (user_id, project_id, occurred_at)
-- 已覆盖同一前缀，再加一条只是多一份写放大。实测重复建同名索引报 1061 Duplicate key name。
CREATE INDEX idx_memory_evidence_work_log ON pt_memory_evidence (work_log_id);

-- 记忆与项目的关联：8.3 的 pt_memory 没有 project_id，按项目过滤只能经证据两跳，
-- 而 Owner 手动提交、以及提取时未带证据的记忆没有证据行，永远归属不到项目。
-- 这类记忆在按项目过滤时会静默消失，漏召回比多召回更难发现，因此把项目归属落到列上。
-- 允许为空：无来源工作记录的记忆本来就不属于任何项目，查询侧对空值按“项目未知”放行，
-- 不能因为归属未知就把它排除在上下文之外。
ALTER TABLE pt_memory
    ADD COLUMN project_id char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER source_id,
    ADD KEY idx_memory_user_project (user_id, project_id);
