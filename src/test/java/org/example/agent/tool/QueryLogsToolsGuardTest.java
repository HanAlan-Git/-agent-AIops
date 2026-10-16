package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.eval.EvalRunTrace;
import org.example.eval.EvalScenario;
import org.example.eval.EvalScenarioContext;
import org.example.eval.EvalTraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@link QueryLogsTools} so rejection is proven on the whole path: tool entry
 * validation, the guard marker written to the trace, and the failure flag a batch run reads back.
 *
 * <p>Why this test exists: the model normally passes valid arguments on its own, so the guard is
 * never exercised in a live run and "invalid parameters are rejected" would stay an unverified
 * claim. Here the invalid argument is supplied deterministically.
 *
 * <p>No mocking: validation runs before any data lookup, so the rejection path needs neither an
 * embedding service nor Milvus.
 *
 * <p>Note on the accepted cases: a valid query may still return zero logs depending on the mock
 * data, so those tests assert "no rejection marker" rather than "data found". Only the guard
 * behaviour is under test here.
 */
class QueryLogsToolsGuardTest {

    /** Jackson's pretty printer writes {@code "key" : value}, so match with flexible spacing. */
    private static final String REJECTION_MARKER = "参数非法";

    private QueryLogsTools newTools() {
        QueryLogsTools tools = new QueryLogsTools();
        ReflectionTestUtils.setField(tools, "mockEnabled", true);
        ReflectionTestUtils.setField(tools, "objectMapper", new ObjectMapper());
        return tools;
    }

    private void startEvalContext(String scenarioId) {
        EvalScenario scenario = new EvalScenario();
        scenario.setScenarioId(scenarioId);
        EvalScenarioContext.set(scenario);
        EvalTraceContext.start(scenario);
    }

    private void clearContext() {
        EvalTraceContext.clear();
        EvalScenarioContext.clear();
    }

    private EvalRunTrace.ToolCall firstCall() {
        EvalRunTrace trace = EvalTraceContext.current().orElseThrow();
        assertThat(trace.getToolCalls()).hasSize(1);
        return trace.getToolCalls().get(0);
    }

    @Test
    @DisplayName("非法 logTopic（不在已知主题、非 TopicId 形态）被拒绝并标记 PARAM_REJECTED")
    void rejectsUnknownTopicAndMarksTrace() {
        startEvalContext("guard-unknown-topic");
        try {
            String result = newTools().queryLogs("ap-guangzhou", "media-upload-logs", "level:ERROR", 20);

            assertThat(result).contains(REJECTION_MARKER).contains("media-upload-logs");
            assertThat(result).containsPattern("\"success\"\\s*:\\s*false");

            EvalRunTrace.ToolCall recorded = firstCall();
            assertThat(recorded.getGuardState()).isEqualTo("PARAM_REJECTED");
            assertThat(recorded.isSuccess()).as("被拒绝的调用不能计入工具准确率").isFalse();
            assertThat(recorded.isDataFound()).isFalse();
        } finally {
            clearContext();
        }
    }

    @Test
    @DisplayName("非法 region（如 ap-hongkong）被拒绝并标记 PARAM_REJECTED")
    void rejectsUnsupportedRegionAndMarksTrace() {
        startEvalContext("guard-unknown-region");
        try {
            String result = newTools().queryLogs("ap-hongkong", "application-logs", "level:ERROR", 20);

            assertThat(result).contains(REJECTION_MARKER).contains("ap-hongkong").contains("ap-guangzhou");
            assertThat(result).containsPattern("\"success\"\\s*:\\s*false");

            EvalRunTrace.ToolCall recorded = firstCall();
            assertThat(recorded.getGuardState()).isEqualTo("PARAM_REJECTED");
            assertThat(recorded.isSuccess()).isFalse();
        } finally {
            clearContext();
        }
    }

    @Test
    @DisplayName("合法参数不被拦截（不产生 PARAM_REJECTED，也无拒绝文案）")
    void acceptsValidParametersWithoutGuardMark() {
        startEvalContext("guard-valid-parameters");
        try {
            String result = newTools().queryLogs("ap-guangzhou", "system-metrics", "cpu_usage:>80", 5);

            assertThat(result).doesNotContain(REJECTION_MARKER);
            assertThat(firstCall().getGuardState()).isNull();
        } finally {
            clearContext();
        }
    }

    @Test
    @DisplayName("limit 超上限被夹紧到 100，而不是被拒绝")
    void clampsOversizedLimit() {
        startEvalContext("guard-limit-clamp");
        try {
            String result = newTools().queryLogs("ap-guangzhou", "system-metrics", "cpu_usage:>80", 5000);

            assertThat(result).doesNotContain(REJECTION_MARKER);
            assertThat(firstCall().getGuardState()).isNull();
        } finally {
            clearContext();
        }
    }
}
