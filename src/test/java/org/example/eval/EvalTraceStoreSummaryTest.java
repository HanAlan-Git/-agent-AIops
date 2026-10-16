package org.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The summary is what the resume quotes, so the aggregation is asserted directly:
 * success rate, tool accuracy, required-tool recall, latency percentiles, token
 * accounting, cost derivation, and the failure-tag histogram used for Bad Case triage.
 *
 * <p>Costs are checked against a fixed price list so the assertion states the formula
 * rather than depending on the repository configuration.
 */
class EvalTraceStoreSummaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalTraceStore store() {
        return new EvalTraceStore(objectMapper, "target/test-eval-reports", 2.0, 8.0, 0.04, "CNY", "unit-test-price-list");
    }

    @Test
    @DisplayName("空列表不炸，且各比率保持 0")
    void handlesEmptyInput() {
        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of());

        assertThat(summary.getRuns()).isZero();
        assertThat(summary.getSuccessRate()).isZero();
        assertThat(summary.getToolCallAccuracy()).isZero();
        assertThat(summary.getAvgScore()).isZero();
        assertThat(summary.getFailureTagHistogram()).isEmpty();
    }

    @Test
    @DisplayName("成功率 / 工具准确率 / 必调工具召回 / 平均步数按 trace 聚合")
    void aggregatesRates() {
        EvalRunTrace perfect = trace("scenario-a", 1, true, 1000L, 3000L,
                List.of(toolCall("queryLogs", true, 1), toolCall("queryInternalDocs", true, 2)),
                score(100.0, true, ""));
        EvalRunTrace partial = trace("scenario-a", 2, false, 5000L, 5900L,
                List.of(toolCall("queryLogs", true, 1), toolCall("queryPrometheusAlerts", false, 1)),
                score(50.0, false, "TOOL_INVALID_OR_FAILED"));

        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of(perfect, partial));

        assertThat(summary.getRuns()).isEqualTo(2);
        assertThat(summary.getScenarios()).isEqualTo(1);
        assertThat(summary.getRounds()).isEqualTo(2);
        assertThat(summary.getSuccessRate()).isEqualTo(0.5);
        assertThat(summary.getAvgScore()).isEqualTo(75.0);
        assertThat(summary.getToolCalls()).isEqualTo(4);
        assertThat(summary.getToolCallAccuracy()).isEqualTo(0.75);
        assertThat(summary.getAvgToolCalls()).isEqualTo(2.0);
        assertThat(summary.getFailureTagHistogram()).containsEntry("TOOL_INVALID_OR_FAILED", 1);
    }

    @Test
    @DisplayName("延迟统计：平均 / P50 / P95 / 最大，取整到毫秒")
    void aggregatesLatency() {
        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of(
                trace("s", 1, true, 0L, 100L, List.of(), score(90, true, "")),
                trace("s", 2, true, 0L, 200L, List.of(), score(90, true, "")),
                trace("s", 3, true, 0L, 300L, List.of(), score(90, true, "")),
                trace("s", 4, true, 0L, 400L, List.of(), score(90, true, ""))
        ));

        assertThat(summary.getAvgLatencyMs()).isEqualTo(250.0);
        assertThat(summary.getP50LatencyMs()).isEqualTo(200L);
        assertThat(summary.getP95LatencyMs()).isEqualTo(400L);
        assertThat(summary.getMaxLatencyMs()).isEqualTo(400L);
    }

    @Test
    @DisplayName("Token 汇总与成本按配置价目推导，并保留价目来源")
    void aggregatesTokensAndCost() {
        EvalRunTrace first = trace("s", 1, true, 0L, 10L, List.of(), score(90, true, ""));
        first.getUsage().add(5000, 1000);
        EvalRunTrace second = trace("s", 2, true, 0L, 10L, List.of(), score(90, true, ""));
        second.getUsage().add(3000, 800);
        second.getUsage().setModel("deepseek-chat");

        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of(first, second));

        assertThat(summary.getTotalPromptTokens()).isEqualTo(8000L);
        assertThat(summary.getTotalCompletionTokens()).isEqualTo(1800L);
        assertThat(summary.getAvgPromptTokens()).isEqualTo(4000.0);
        assertThat(summary.getTokensReportedRuns()).isEqualTo(2);
        // 8000/1e6*2.0 + 1800/1e6*8.0 = 0.016 + 0.0144 = 0.0304 -> 0.03
        assertThat(summary.getTotalCostCny()).isEqualTo(0.03);
        assertThat(summary.getAvgCostCny()).isEqualTo(0.02);
        assertThat(summary.getPricing()).contains("CNY").contains("unit-test-price-list");
    }

    @Test
    @DisplayName("模型未上报 usage 时 tokensReportedRuns 保持 0，不伪造 Token 数字")
    void doesNotFabricateTokenUsage() {
        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of(
                trace("s", 1, true, 0L, 10L, List.of(), score(90, true, ""))
        ));

        assertThat(summary.getTokensReportedRuns()).isZero();
        assertThat(summary.getTotalPromptTokens()).isZero();
        assertThat(summary.getAvgCostCny()).isZero();
    }

    @Test
    @DisplayName("缺失评分器的 trace 只计入运行数，不拉低平均分")
    void ignoresUnscoredTracesForAverage() {
        EvalTraceStore.EvalBatchSummary summary = store().summarize(List.of(
                trace("s", 1, true, 0L, 10L, List.of(), score(80, true, "")),
                trace("s", 2, true, 0L, 10L, List.of(), null)
        ));

        assertThat(summary.getRuns()).isEqualTo(2);
        assertThat(summary.getScoredRuns()).isEqualTo(1);
        assertThat(summary.getAvgScore()).isEqualTo(80.0);
    }

    private EvalRunTrace trace(String scenarioId, int round, boolean success,
                               long startedAt, long finishedAt,
                               List<EvalRunTrace.ToolCall> calls,
                               EvalRunTrace.EvalScore score) {
        EvalRunTrace trace = new EvalRunTrace();
        trace.setRunId(EvalTraceContext.batchRunId(scenarioId, round));
        trace.setScenarioId(scenarioId);
        trace.setRound(round);
        trace.setSuccess(success);
        trace.setStartedAtEpochMs(startedAt);
        trace.setFinishedAtEpochMs(finishedAt);
        trace.setDurationMs(finishedAt - startedAt);
        trace.setToolCalls(new java.util.ArrayList<>(calls));
        trace.setScore(score);
        return trace;
    }

    private EvalRunTrace.ToolCall toolCall(String name, boolean success, int attempts) {
        EvalRunTrace.ToolCall call = new EvalRunTrace.ToolCall();
        call.setToolName(name);
        call.setSuccess(success);
        call.setDataFound(success);
        call.setAttempts(attempts);
        call.setDurationMs(25);
        return call;
    }

    private EvalRunTrace.EvalScore score(double total, boolean taskSuccess, String failureTag) {
        EvalRunTrace.EvalScore score = new EvalRunTrace.EvalScore();
        score.setScored(true);
        score.setTotal(total);
        score.setTaskSuccess(taskSuccess);
        if (!failureTag.isEmpty()) {
            score.getFailureTags().add(failureTag);
        }
        return score;
    }
}
