package com.example.pwt.memory.application;

import com.example.pwt.task.application.TaskHandler;
import com.example.pwt.task.domain.TaskPayload;
import com.example.pwt.task.domain.TaskType;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code EXTRACT_MEMORY} 任务处理器（设计文档 17.1）。
 *
 * <p>幂等：payload 只携带工作记录 ID，重复执行会再次走 {@code MemoryService#propose} 的去重，
 * 相同内容只累加证据而不产生新记忆，因此重放安全（17.3）。
 *
 * @author pwt
 */
@Component
public class MemoryExtractionTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionTaskHandler.class);

    private final MemoryExtractionService extractionService;

    /**
     * 构造处理器。
     *
     * @param extractionService 提取服务
     */
    public MemoryExtractionTaskHandler(MemoryExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.EXTRACT_MEMORY;
    }

    @Override
    public void handle(AsyncTaskEntity task) {
        UUID workLogId = TaskPayload.requireWorkLogId(task.getPayload());
        int stored = extractionService.extractFromWorkLog(workLogId);
        log.info("提取任务执行完毕 taskId={} workLogId={} candidates={}", task.getId(), workLogId, stored);
    }
}
