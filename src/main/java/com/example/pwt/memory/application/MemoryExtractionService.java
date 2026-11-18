package com.example.pwt.memory.application;

import com.example.pwt.common.api.SourceType;
import com.example.pwt.common.error.BizException;
import com.example.pwt.common.error.ErrorCode;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.llm.LlmContract.ChatRequest;
import com.example.pwt.llm.LlmContract.ChatResult;
import com.example.pwt.llm.LlmProvider;
import com.example.pwt.llm.LlmTaskType;
import com.example.pwt.llm.PromptTemplates;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.memory.domain.RiskLevel;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 记忆提取：把工作记录交给 LLM，产出候选记忆（设计文档 6.1 的 D～G 步）。
 *
 * <p>调用前先做一次敏感检测：命中凭证的内容不送给模型，避免把秘密发给外部服务（15.1 第 1 条）。
 * 模型返回后再检测一次（12.3）。两次检测都只给出类别与风险，不回显原文。
 *
 * <p>落库统一走 {@link MemoryService#propose}，因此提取结果与 Agent 直接提交的候选
 * 走完全相同的去重、冲突与审核规则，不存在第二条更宽松的入库通道。
 *
 * @author pwt
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条工作记录最多产出的候选数，超出部分丢弃，避免一次异常输出灌满审核队列。 */
    private static final int MAX_CANDIDATES = 5;

    private final LlmProvider llmProvider;

    private final WorkLogMapper workLogMapper;

    private final MemoryService memoryService;

    /**
     * 构造服务。
     *
     * @param llmProvider   LLM Provider
     * @param workLogMapper 工作记录 Mapper
     * @param memoryService 记忆服务
     */
    public MemoryExtractionService(LlmProvider llmProvider, WorkLogMapper workLogMapper,
                                   MemoryService memoryService) {
        this.llmProvider = llmProvider;
        this.workLogMapper = workLogMapper;
        this.memoryService = memoryService;
    }

    /**
     * 提取一条工作记录。
     *
     * @param workLogId 工作记录 ID
     * @return 生成的候选记忆条数
     */
    public int extractFromWorkLog(UUID workLogId) {
        WorkLogEntity workLog = workLogMapper.selectById(workLogId.toString());
        if (workLog == null) {
            log.info("工作记录已不存在, 跳过提取 workLogId={}", workLogId);
            return 0;
        }
        String sourceText = text(workLog.getTitle()) + " " + text(workLog.getSummary()) + " "
            + text(workLog.getContent());
        RiskLevel preRisk = MemoryService.serverRisk(sourceText);
        if (preRisk == RiskLevel.L3) {
            log.info("工作记录命中 L3 凭证, 不送外部模型 workLogId={}", workLogId);
            return 0;
        }

        ChatResult result = llmProvider.chat(ChatRequest.json(LlmTaskType.MEMORY_EXTRACTION,
            PromptTemplates.MEMORY_EXTRACTION,
            PromptTemplates.extractionInput(workLog.getId(), workLog.getTitle(), workLog.getContent())));
        String promptVersion = result.promptVersion() == null
            ? PromptTemplates.MEMORY_EXTRACTION_VERSION : result.promptVersion();

        List<Candidate> candidates = parseCandidates(result.content());
        int stored = 0;
        for (Candidate candidate : candidates) {
            if (MemoryService.serverRisk(candidate.content(), candidate.evidence()) == RiskLevel.L3) {
                log.info("模型输出命中 L3 凭证, 丢弃该候选 workLogId={}", workLogId);
                continue;
            }
            memoryService.proposeExtracted(actorOf(workLog), candidate.memoryType(), candidate.content(),
                    candidate.summary(), candidate.evidence(), workLogId, candidate.confidence(),
                    extractionMetadata(result, promptVersion, workLog, llmProvider.providerCode()));
            stored++;
        }
        log.info("记忆提取完成 workLogId={} 模型返回={} 入库={}", workLogId, candidates.size(), stored);
        return stored;
    }

    /**
     * 解析模型返回的候选数组。
     *
     * <p>模型输出属于不可信输入：只取声明的字段、逐条校验类型与长度，
     * 解析失败的单条跳过而不是让整批任务失败，避免一条格式瑕疵触发无限重试。
     *
     * @param content 模型输出
     * @return 候选列表
     */
    static List<Candidate> parseCandidates(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode array = readJsonArray(content);
        if (array == null) {
            throw new BizException(ErrorCode.LLM_PROVIDER_ERROR,
                "LLM 提取输出不是合法 JSON 数组");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isObject() || candidates.size() >= MAX_CANDIDATES) {
                continue;
            }
            String text = node.path("content").asText("").strip();
            String type = node.path("memory_type").asText("").strip();
            if (text.isEmpty() || text.length() > 10000) {
                continue;
            }
            try {
                candidates.add(new Candidate(MemoryType.fromValue(type), text,
                    nullable(node, "summary", 1000), nullable(node, "evidence", 5000),
                    node.path("confidence").isNumber() ? node.path("confidence").asDouble() : null));
            } catch (IllegalArgumentException e) {
                log.info("丢弃记忆类型非法的候选 memoryType={}", type);
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 容忍模型给 JSON 套上 markdown 代码块或前后缀说明。
     *
     * @param content 原始输出
     * @return 数组节点，无法定位时返回 {@code null}
     */
    private static JsonNode readJsonArray(String content) {
        String trimmed = content.strip();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed.substring(start, end + 1));
            return node.isArray() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String nullable(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static Map<String, Object> extractionMetadata(ChatResult result, String promptVersion,
                                                          WorkLogEntity workLog, String providerCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("extractor", "memory_extraction");
        metadata.put("extractorVersion", promptVersion);
        metadata.put("llmProvider", providerCode);
        // Map.copyOf 不接受 null 值：供应商未回显 model 时也必须能落库
        metadata.put("model", result.model() == null ? "unknown" : result.model());
        metadata.put("sourceType", SourceType.SYSTEM.getValue());
        metadata.put("sourceWorkLogId", workLog.getId());
        return Map.copyOf(metadata);
    }

    /**
     * 提取任务所属主体：以工作记录所有者的系统主体落库，
     * 使候选记忆的 user_id 与来源记录一致，来源标记为 system。
     *
     * @param workLog 来源工作记录
     * @return 主体
     */
    static ActorContext actorOf(WorkLogEntity workLog) {
        return ActorContext.system(UUID.fromString(workLog.getUserId()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * 模型返回的单条候选。
     *
     * @param memoryType 类型
     * @param content    正文
     * @param summary    摘要
     * @param evidence   原文摘录
     * @param confidence 置信度
     * @author pwt
     */
    public record Candidate(MemoryType memoryType, String content, String summary, String evidence,
                            Double confidence) {
    }
}
