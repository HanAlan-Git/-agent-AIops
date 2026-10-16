package org.example.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires the two halves of parameter rejection together end to end:
 * {@code ToolParameterValidator} decides the arguments are unusable, and
 * {@code EvalTraceContext.markToolGuard} must record that verdict on the trace so a batch
 * run can prove the guard actually fired.
 *
 * <p>This matters because the model usually passes valid arguments on its own. When it does,
 * the guard is never exercised and "invalid parameters are rejected" stays an unverified
 * claim. These tests pin the rejection path independently of model behaviour.
 */
class ToolGuardTraceTest {

    @Test
    @DisplayName("非法 logTopic 被拒绝，并在 trace 上标记 PARAM_REJECTED")
    void recordsParameterRejectionOnTrace() {
        EvalScenario scenario = new EvalScenario();
        scenario.setScenarioId("guard-trace-probe");
        EvalTraceContext.start(scenario);
        try {
            EvalRunTrace.ToolCall call = EvalTraceContext.beginTool("queryLogs",
                    java.util.Map.of("logTopic", "media-upload-logs"));

            String rejection = org.example.agent.tool.ToolParameterValidator
                    .validateLogQuery("ap-guangzhou", "media-upload-logs");

            assertThat(rejection).as("非法主题必须被拒绝").isNotNull();
            EvalTraceContext.markToolGuard(call, 1, "PARAM_REJECTED");
            EvalTraceContext.finishTool(call, "{\"success\":false,\"message\":\"" + rejection + "\"}");

            EvalRunTrace trace = EvalTraceContext.current().orElseThrow();
            assertThat(trace.getToolCalls()).hasSize(1);
            EvalRunTrace.ToolCall recorded = trace.getToolCalls().get(0);
            assertThat(recorded.getGuardState()).isEqualTo("PARAM_REJECTED");
            assertThat(recorded.getAttempts()).isEqualTo(1);
            assertThat(recorded.isSuccess()).as("被拒绝的调用不能算成功").isFalse();
        } finally {
            EvalTraceContext.clear();
        }
    }

    @Test
    @DisplayName("非法 region 同样被拒绝并标记 PARAM_REJECTED")
    void recordsRegionRejectionOnTrace() {
        EvalScenario scenario = new EvalScenario();
        scenario.setScenarioId("guard-trace-probe-region");
        EvalTraceContext.start(scenario);
        try {
            EvalRunTrace.ToolCall call = EvalTraceContext.beginTool("queryLogs",
                    java.util.Map.of("region", "ap-hongkong", "logTopic", "application-logs"));

            String rejection = org.example.agent.tool.ToolParameterValidator
                    .validateLogQuery("ap-hongkong", "application-logs");

            assertThat(rejection).isNotNull();
            EvalTraceContext.markToolGuard(call, 1, "PARAM_REJECTED");
            EvalTraceContext.finishTool(call, "{\"success\":false,\"message\":\"" + rejection + "\"}");

            EvalRunTrace trace = EvalTraceContext.current().orElseThrow();
            assertThat(trace.getToolCalls().get(0).getGuardState()).isEqualTo("PARAM_REJECTED");
            assertThat(trace.getToolCalls().get(0).isSuccess()).isFalse();
        } finally {
            EvalTraceContext.clear();
        }
    }

    @Test
    @DisplayName("合法参数不产生 PARAM_REJECTED 标记")
    void validParametersLeaveNoGuardMark() {
        EvalScenario scenario = new EvalScenario();
        scenario.setScenarioId("guard-trace-probe-ok");
        EvalTraceContext.start(scenario);
        try {
            EvalRunTrace.ToolCall call = EvalTraceContext.beginTool("queryLogs",
                    java.util.Map.of("region", "ap-guangzhou", "logTopic", "system-metrics"));

            String rejection = org.example.agent.tool.ToolParameterValidator
                    .validateLogQuery("ap-guangzhou", "system-metrics");
            assertThat(rejection).isNull();
            EvalTraceContext.finishTool(call, "{\"success\":true,\"total\":2}");

            EvalRunTrace trace = EvalTraceContext.current().orElseThrow();
            assertThat(trace.getToolCalls().get(0).getGuardState()).isNull();
        } finally {
            EvalTraceContext.clear();
        }
    }
}
