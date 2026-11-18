package com.example.pwt.work.application;

import com.example.pwt.work.domain.ExtractionState;
import com.example.pwt.work.infrastructure.WorkLogEntity;

/**
 * 创建工作记录的结果。
 *
 * @param entity            新建或既有的记录
 * @param extraction        该记录当前的记忆提取进度。幂等命中与并发重复提交时读取既有任务状态，
 *                          而不是照报“未排队”（设计文档 17.3、10.3）
 * @author pwt
 */
public record WorkLogCreation(WorkLogEntity entity, ExtractionState extraction) {
}
