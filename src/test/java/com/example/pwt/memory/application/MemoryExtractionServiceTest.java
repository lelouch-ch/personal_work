package com.example.pwt.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.pwt.common.error.BizException;
import com.example.pwt.identity.domain.ActorContext;
import com.example.pwt.llm.LlmContract.ChatRequest;
import com.example.pwt.llm.LlmContract.ChatResult;
import com.example.pwt.llm.LlmProvider;
import com.example.pwt.llm.PromptTemplates;
import com.example.pwt.memory.domain.MemoryType;
import com.example.pwt.task.domain.TaskPayload;
import com.example.pwt.task.infrastructure.AsyncTaskEntity;
import com.example.pwt.work.infrastructure.WorkLogEntity;
import com.example.pwt.work.infrastructure.WorkLogMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 记忆提取测试（设计文档 6.1、12.3、15.1）。
 *
 * @author pwt
 */
class MemoryExtractionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private static final UUID WORK_LOG_ID = UUID.randomUUID();

    @Test
    void shouldParseCandidatesFromJsonArray() {
        List<MemoryExtractionService.Candidate> candidates = MemoryExtractionService.parseCandidates("""
            [{"content":"项目使用 Spring Boot 3.2","summary":"技术栈","memory_type":"fact",
              "confidence":0.9,"evidence":"使用 Spring Boot 3.2"},
             {"content":"偏好先给可运行方案","memory_type":"preference","confidence":0.7}]""");

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).memoryType()).isEqualTo(MemoryType.FACT);
        assertThat(candidates.get(0).confidence()).isEqualTo(0.9d);
        assertThat(candidates.get(1).summary()).isNull();
    }

    @Test
    void shouldTolerateMarkdownFenceAndProse() {
        List<MemoryExtractionService.Candidate> candidates = MemoryExtractionService.parseCandidates(
            "```json\n[{\"content\":\"内容\",\"memory_type\":\"experience\"}]\n```");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).memoryType()).isEqualTo(MemoryType.EXPERIENCE);
    }

    @Test
    void shouldDropCandidateWithUnknownTypeOrBlankContent() {
        List<MemoryExtractionService.Candidate> candidates = MemoryExtractionService.parseCandidates("""
            [{"content":"合法","memory_type":"fact"},
             {"content":"类型错","memory_type":"rumour"},
             {"content":"   ","memory_type":"fact"},
             "不是对象"]""");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).content()).isEqualTo("合法");
    }

    @Test
    void shouldCapCandidatesAtFive() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            json.append(i > 0 ? "," : "").append("{\"content\":\"c").append(i)
                .append("\",\"memory_type\":\"fact\"}");
        }
        json.append(']');

        assertThat(MemoryExtractionService.parseCandidates(json.toString())).hasSize(5);
    }

    @Test
    void emptyArrayIsValidBusinessResult() {
        assertThat(MemoryExtractionService.parseCandidates("[]")).isEmpty();
    }

    @Test
    void unparsableOutputShouldFailTaskForRetry() {
        assertThatThrownBy(() -> MemoryExtractionService.parseCandidates("模型讲了段话，没有 JSON"))
            .isInstanceOf(BizException.class);
    }

    @Test
    void shouldProposeCandidatesUnderOwnerScopedSystemActor() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        WorkLogEntity log = workLog("排查 Feign 上传问题", "正文内容");
        MemoryExtractionService service = service(memoryService, new StubProvider(
            "[{\"content\":\"Feign 上传要用 RequestPart\",\"memory_type\":\"experience\","
                + "\"confidence\":0.9,\"evidence\":\"上传要用 RequestPart\"}]"), log);

        int stored = service.extractFromWorkLog(WORK_LOG_ID);

        assertThat(stored).isEqualTo(1);
        ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
        Mockito.verify(memoryService).proposeExtracted(actor.capture(), Mockito.eq(MemoryType.EXPERIENCE),
            Mockito.anyString(), Mockito.isNull(), Mockito.anyString(), Mockito.eq(WORK_LOG_ID),
            Mockito.anyDouble(), Mockito.anyMap());
        // 以记录所有者的系统主体落库，user_id 与来源一致
        assertThat(actor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(actor.getValue().isSystem()).isTrue();
    }

    @Test
    void shouldRecordExtractionProvenance() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        WorkLogEntity log = workLog("标题", "正文");
        MemoryExtractionService service = service(memoryService,
            new StubProvider("[{\"content\":\"结论\",\"memory_type\":\"fact\"}]"), log);

        service.extractFromWorkLog(WORK_LOG_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(memoryService).proposeExtracted(Mockito.any(), Mockito.any(), Mockito.anyString(),
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), meta.capture());
        // 设计文档 6.6、12.3：模型与提示词版本必须可追溯
        assertThat(meta.getValue()).containsEntry("extractorVersion", PromptTemplates.MEMORY_EXTRACTION_VERSION)
            .containsEntry("model", "stub-model").containsEntry("llmProvider", "stub");
    }

    @Test
    void shouldNotSendL3WorkLogToExternalModel() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        WorkLogEntity log = workLog("排查记录", "配置里写着 password = SuperSecret123");
        StubProvider provider = new StubProvider("[{\"content\":\"x\",\"memory_type\":\"fact\"}]");

        assertThat(service(memoryService, provider, log).extractFromWorkLog(WORK_LOG_ID)).isZero();
        assertThat(provider.calls).isZero();
        Mockito.verifyNoInteractions(memoryService);
    }

    @Test
    void shouldDropModelCandidateThatLeakedCredential() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        WorkLogEntity log = workLog("排查记录", "正文");
        StubProvider provider = new StubProvider("[{\"content\":\"口令是 pwt_0123456789abcdef0123456789abcdef\","
            + "\"memory_type\":\"fact\"},{\"content\":\"正常经验\",\"memory_type\":\"experience\"}]");

        int stored = service(memoryService, provider, log).extractFromWorkLog(WORK_LOG_ID);

        assertThat(stored).isEqualTo(1);
        Mockito.verify(memoryService).proposeExtracted(Mockito.any(), Mockito.eq(MemoryType.EXPERIENCE),
            Mockito.eq("正常经验"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.anyMap());
    }

    @Test
    void shouldSkipSilentlyWhenWorkLogGone() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        MemoryExtractionService service = service(memoryService, new StubProvider("[]"), null);

        assertThat(service.extractFromWorkLog(UUID.randomUUID())).isZero();
        Mockito.verifyNoInteractions(memoryService);
    }

    @Test
    void handlerShouldReadWorkLogIdFromPayload() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setPayload(TaskPayload.ofWorkLog(WORK_LOG_ID));
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        MemoryExtractionService extraction = Mockito.mock(MemoryExtractionService.class);
        Mockito.when(extraction.extractFromWorkLog(WORK_LOG_ID)).thenReturn(2);

        new MemoryExtractionTaskHandler(extraction).handle(task);

        Mockito.verify(extraction).extractFromWorkLog(WORK_LOG_ID);
    }

    @Test
    void handlerShouldRejectPayloadWithoutWorkLogId() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setPayload(Map.of());
        MemoryExtractionTaskHandler handler =
            new MemoryExtractionTaskHandler(Mockito.mock(MemoryExtractionService.class));

        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(BizException.class);
        task.setPayload(Map.of(TaskPayload.WORK_LOG_ID, "not-a-uuid"));
        assertThatThrownBy(() -> handler.handle(task)).isInstanceOf(BizException.class);
    }

    /**
     * 组装被测服务。
     *
     * @param memoryService 记忆服务 mock
     * @param provider      固定输出的 Provider
     * @param log           {@code selectById} 要返回的记录，为空表示查不到
     * @return 被测服务
     */
    private static MemoryExtractionService service(MemoryService memoryService, LlmProvider provider,
                                                   WorkLogEntity log) {
        WorkLogMapper mapper = Mockito.mock(WorkLogMapper.class);
        Mockito.when(mapper.selectById(Mockito.anyString())).thenReturn(log);
        return new MemoryExtractionService(provider, mapper, memoryService);
    }

    private static WorkLogEntity workLog(String title, String content) {
        WorkLogEntity entity = new WorkLogEntity();
        entity.setId(WORK_LOG_ID.toString());
        entity.setUserId(USER_ID.toString());
        entity.setTitle(title);
        entity.setContent(content);
        return entity;
    }

    /** 只返回固定输出的 Provider，顺便统计被调用次数。 */
    private static final class StubProvider implements LlmProvider {

        private final String output;

        private int calls;

        private StubProvider(String output) {
            this.output = output;
        }

        @Override
        public String providerCode() {
            return "stub";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            calls++;
            return new ChatResult(output, "stub-model", null, Map.of());
        }
    }
}
