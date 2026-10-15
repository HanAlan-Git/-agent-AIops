package org.example.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scorer is the only thing that decides whether an AIOps run was correct, so its
 * behaviour is pinned here: deterministic term coverage, the weighted total, the failure
 * tags that drive Bad Case triage, and the strict definition of task success.
 *
 * <p>Labels come from {@code src/test/resources/eval/fixtures}, which is deliberately
 * outside {@code eval/golden} so the scorer's own fixtures never appear as real scenarios
 * in a batch run. The lookup path ({@code GOLDEN_NOT_FOUND}) is covered separately by
 * scoring a made-up scenario id.
 */
class EvalScoringServiceTest {

    private static final String SCENARIO_ID = "unit-eval-scenario-01";
    private static final String FIXTURE = "eval/fixtures/" + SCENARIO_ID + ".golden.json";

    /** A report covering 根因 / 证据 / 处置 of the fixture diagnosis exactly once each. */
    private static final String FULL_REPORT =
            "根因：导出任务把结果放在无界缓存里，对象未释放造成内存泄漏。"
                    + "证据：堆内存 3.8GB，过去十分钟 15 次 Full GC。"
                    + "处置：给缓存设置上限并定期清理。";

    private EvalScoringService scoringService;
    private EvalGoldenScenario fixture;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(input).as("找不到测试夹具 %s", FIXTURE).isNotNull();
            fixture = objectMapper.readValue(input, EvalGoldenScenario.class);
        }
        scoringService = new EvalScoringService(new EvalGoldenScenarioService(objectMapper));
    }

    @Test
    @DisplayName("夹具本身可用：scenarioId 与诊断项数量正确")
    void fixtureIsUsable() {
        assertThat(fixture.getScenarioId()).isEqualTo(SCENARIO_ID);
        assertThat(fixture.getAcceptableDiagnoses()).hasSize(1);
        assertThat(fixture.getRequiredTools()).hasSize(3);
    }

    @Test
    @DisplayName("查不到标注时打 GOLDEN_NOT_FOUND 且不评分")
    void marksUnscorableWhenLabelsAreMissing() {
        EvalRunTrace trace = trace(false, "", List.of(), "scenario-without-labels");

        EvalRunTrace.EvalScore score = scoringService.score(trace);

        assertThat(score.isScored()).isFalse();
        assertThat(score.getFailureTags()).containsExactly("GOLDEN_NOT_FOUND");
    }

    @Test
    @DisplayName("全覆盖 + 工具成功 => 100 分且 taskSuccess")
    void scoresFullCoverageAsPerfect() {
        EvalRunTrace trace = trace(true, FULL_REPORT, List.of(alertTool(), logsTool(), docsTool()), SCENARIO_ID);

        EvalRunTrace.EvalScore score = scoringService.scoreAgainst(trace, fixture);

        assertThat(score.isScored()).isTrue();
        assertThat(score.getRequiredToolRecall()).isEqualTo(1.0);
        assertThat(score.getToolCallAccuracy()).isEqualTo(1.0);
        assertThat(score.getRootCauseCoverage()).isEqualTo(1.0);
        assertThat(score.getEvidenceCoverage()).isEqualTo(1.0);
        assertThat(score.getActionCoverage()).isEqualTo(1.0);
        assertThat(score.getTotal()).isEqualTo(100.0);
        assertThat(score.isTaskSuccess()).isTrue();
        assertThat(score.getFailureTags()).isEmpty();
    }

    @Test
    @DisplayName("漏调知识库工具 => TOOL_MISSING，任务判定失败")
    void flagsMissingRequiredTool() {
        EvalRunTrace trace = trace(true, FULL_REPORT, List.of(alertTool(), logsTool()), SCENARIO_ID);

        EvalRunTrace.EvalScore score = scoringService.scoreAgainst(trace, fixture);

        assertThat(score.getRequiredToolRecall()).isEqualTo(0.67);
        assertThat(score.getFailureTags()).contains("TOOL_MISSING");
        assertThat(score.isTaskSuccess()).isFalse();
    }

    @Test
    @DisplayName("工具查到空数据且报告缺证据 => RAG_NO_RESULT + EVIDENCE_GAP")
    void flagsEmptyRetrievalAndEvidenceGap() {
        String reportWithoutEvidence = "根因：缓存未释放导致内存泄漏。处置：设置上限并清理。";
        EvalRunTrace trace = trace(true, reportWithoutEvidence,
                List.of(alertTool(), logsTool(), docsTool(false)), SCENARIO_ID);

        EvalRunTrace.EvalScore score = scoringService.scoreAgainst(trace, fixture);

        assertThat(score.isRagResultFound()).isFalse();
        assertThat(score.getFailureTags()).contains("RAG_NO_RESULT", "EVIDENCE_GAP");
        assertThat(score.getEvidenceCoverage()).isEqualTo(0.0);
        assertThat(score.getTotal()).isLessThan(100.0);
        assertThat(score.isTaskSuccess()).isFalse();
    }

    @Test
    @DisplayName("执行失败 => EXECUTION_FAILURE 且拿不到完成分")
    void flagsExecutionFailure() {
        EvalRunTrace trace = trace(false, "", List.of(alertTool()), SCENARIO_ID);

        EvalRunTrace.EvalScore score = scoringService.scoreAgainst(trace, fixture);

        assertThat(score.getFailureTags()).contains("EXECUTION_FAILURE");
        assertThat(score.isTaskSuccess()).isFalse();
        assertThat(score.getTotal()).isLessThan(100.0);
    }

    @Test
    @DisplayName("命中金标准诊断时记录匹配到的 id")
    void recordsMatchedDiagnosisId() {
        EvalRunTrace trace = trace(true, FULL_REPORT, List.of(alertTool(), logsTool(), docsTool()), SCENARIO_ID);

        EvalRunTrace.EvalScore score = scoringService.scoreAgainst(trace, fixture);

        assertThat(score.getMatchedDiagnosisId()).isEqualTo("unit-diagnosis");
        assertThat(score.getRootCauseCoverage()).isEqualTo(1.0);
    }

    private EvalRunTrace trace(boolean success, String report, List<EvalRunTrace.ToolCall> calls, String scenarioId) {
        EvalRunTrace trace = new EvalRunTrace();
        trace.setRunId("eval-unit");
        trace.setScenarioId(scenarioId);
        trace.setSuccess(success);
        trace.setFinalReport(report);
        trace.setToolCalls(new ArrayList<>(calls));
        return trace;
    }

    private EvalRunTrace.ToolCall alertTool() {
        return toolCall("queryPrometheusAlerts", true, true);
    }

    private EvalRunTrace.ToolCall logsTool() {
        return toolCall("queryLogs", true, true);
    }

    private EvalRunTrace.ToolCall docsTool() {
        return docsTool(true);
    }

    private EvalRunTrace.ToolCall docsTool(boolean dataFound) {
        return toolCall("queryInternalDocs", true, dataFound);
    }

    private EvalRunTrace.ToolCall toolCall(String name, boolean success, boolean dataFound) {
        EvalRunTrace.ToolCall call = new EvalRunTrace.ToolCall();
        call.setToolName(name);
        call.setSuccess(success);
        call.setDataFound(dataFound);
        call.setDurationMs(10);
        call.setAttempts(1);
        return call;
    }
}
