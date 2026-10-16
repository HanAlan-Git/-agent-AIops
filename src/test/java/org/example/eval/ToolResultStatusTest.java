package org.example.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three-state tool result semantics: {@code OK} (data), {@code EMPTY} (ran fine, no
 * match), {@code ERROR} (parameter rejected, exception, dependency down).
 *
 * <p>Why this matters: the two older states were "success" and "not success", and "found
 * nothing" was expressed as "not success". A batch run then counted 423 honest empty searches
 * as failed tool calls and reported a tool accuracy of 0.54 instead of 1.00. These tests exist
 * so that conflation cannot come back.
 */
class ToolResultStatusTest {

    private EvalRunTrace.ToolCall beginAndFinish(String result) {
        EvalScenario scenario = new EvalScenario();
        scenario.setScenarioId("status-semantics");
        EvalTraceContext.start(scenario);
        EvalRunTrace.ToolCall call = EvalTraceContext.beginTool("queryLogs", Map.of("logTopic", "application-logs"));
        EvalTraceContext.finishTool(call, result);
        return call;
    }

    @AfterEach
    void tearDown() {
        EvalTraceContext.clear();
    }

    @Test
    @DisplayName("status=OK：有数据 → 成功且计入数据")
    void okMeansSuccessWithData() {
        EvalRunTrace.ToolCall call = beginAndFinish("""
                {
                  "success" : true,
                  "status" : "OK",
                  "logs" : [ { "message" : "慢查询 3.2s" } ],
                  "total" : 1
                }""");

        assertThat(call.getStatus()).isEqualTo("OK");
        assertThat(call.isSuccess()).isTrue();
        assertThat(call.isDataFound()).isTrue();
    }

    @Test
    @DisplayName("status=EMPTY：执行成功但无数据 → 仍算调用成功，只是没有数据")
    void emptyMeansSuccessWithoutData() {
        EvalRunTrace.ToolCall call = beginAndFinish("""
                {
                  "success" : true,
                  "status" : "EMPTY",
                  "logs" : [ ],
                  "total" : 0,
                  "message" : "未找到匹配的日志"
                }""");

        assertThat(call.getStatus()).isEqualTo("EMPTY");
        assertThat(call.isSuccess()).as("诚实查空不是工具失败").isTrue();
        assertThat(call.isDataFound()).isFalse();
    }

    @Test
    @DisplayName("status=ERROR：参数被拒/异常 → 调用失败")
    void errorMeansFailure() {
        EvalRunTrace.ToolCall call = beginAndFinish("""
                {
                  "success" : false,
                  "status" : "ERROR",
                  "message" : "参数非法：logTopic=media-upload-logs 不受支持"
                }""");

        assertThat(call.getStatus()).isEqualTo("ERROR");
        assertThat(call.isSuccess()).isFalse();
        assertThat(call.isDataFound()).isFalse();
    }

    @Test
    @DisplayName("紧凑 JSON 也能识别 status")
    void recognizesCompactJson() {
        EvalRunTrace.ToolCall call = beginAndFinish("{\"success\":true,\"status\":\"EMPTY\",\"logs\":[],\"total\":0}");

        assertThat(call.getStatus()).isEqualTo("EMPTY");
        assertThat(call.isSuccess()).isTrue();
        assertThat(call.isDataFound()).isFalse();
    }

    @Test
    @DisplayName("老载荷没有 status 字段时，退回启发式但结论一致：查空 ≠ 失败")
    void fallsBackToHeuristicsForLegacyPayloads() {
        EvalRunTrace.ToolCall call = beginAndFinish("""
                {
                  "success" : false,
                  "logs" : [ ],
                  "total" : 0,
                  "message" : "未找到匹配的日志"
                }""");

        assertThat(call.getStatus()).isEqualTo("EMPTY");
        assertThat(call.isSuccess()).as("旧载荷同样不应把查空算成失败").isTrue();
        assertThat(call.isDataFound()).isFalse();
    }

    @Test
    @DisplayName("老载荷的真错误仍被识别为失败")
    void legacyErrorPayloadStillFails() {
        EvalRunTrace.ToolCall call = beginAndFinish("{\"success\":false,\"message\":\"参数非法：region 不受支持\"}");

        assertThat(call.getStatus()).isEqualTo("ERROR");
        assertThat(call.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("null 结果视为执行失败")
    void nullResultIsFailure() {
        EvalRunTrace.ToolCall call = beginAndFinish(null);

        assertThat(call.getStatus()).isEqualTo("ERROR");
        assertThat(call.isSuccess()).isFalse();
    }
}
