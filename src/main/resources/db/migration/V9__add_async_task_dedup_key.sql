-- Personal Work Twin: V9__add_async_task_dedup_key.sql
-- 来源：设计文档 17.3「任务：任务 payload 中携带业务对象 ID，重复执行前检查是否已完成」
--      与 17.1 任务类型 EXTRACT_MEMORY。
--
-- 17.3 只要求“检查”，没有给并发写入提供数据库级约束：同一工作记录被并发提交两次时
-- （例如 Agent 重试与幂等回读同时到达），只靠先查后插会创建两个提取任务，
-- 导致同一条记录被提取两次并生成成倍的候选记忆。沿用 ADR-021 的做法，
-- 用 STORED 生成列 + 唯一索引把“同一用户对同一业务对象同一任务类型只排队一次”落到数据库。
--
-- dedup_ref 限定 ascii_bin，与 pt_work_log.idempotency_key 同规格：它是 UUID/随机串这类
-- 客户端生成的键，不需要容纳非 ASCII；生成列显式 COLLATE utf8mb4_bin 保证二进制比较，
-- 避免 0900_ai_ci 把两个不同的键判为重复。
-- 实测 MySQL 9.3.0：本表三种写法（显式 COLLATE / 不写排序规则 / CHARACTER SET ascii）
-- 均可建表，且唯一索引确实拒绝重复键、NULL 键（未提供 dedup_ref）不参与唯一判定。

ALTER TABLE pt_async_task
    ADD COLUMN dedup_ref varchar(150) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER task_type,
    ADD COLUMN dedup_key varchar(255) COLLATE utf8mb4_bin
        GENERATED ALWAYS AS (
            IF(`dedup_ref` IS NULL,
               NULL,
               CONCAT(`user_id`, '#', `task_type`, '#', `dedup_ref`))
        ) STORED,
    ADD UNIQUE KEY uq_async_task_dedup (dedup_key);
