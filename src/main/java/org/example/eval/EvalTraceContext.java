package org.example.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-scoped evaluation trace. It is activated only by the eval endpoint, so
 * production traffic never pays for tracing. Batch runs set a deterministic run id
 * up front which lets the offline runner locate the trace file without an extra
 * round trip.
 */
public final class EvalTraceContext {

    private static final int SUMMARY_LIMIT = 500;

    /** Marker used by tool parameter validation in its rejection payloads. */
    private static final String REJECTION_MARKER = "参数非法";
    private static final ThreadLocal<EvalRunTrace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<UsageRecorder> USAGE = new ThreadLocal<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EvalTraceContext() {
    }

    /** Deterministic id used by the batch runner: eval-{scenarioId}-r{round}. */
    public static String batchRunId(String scenarioId, int round) {
        return "eval-" + scenarioId + "-r" + round;
    }

    public static void start(EvalScenario scenario) {
        start(scenario, null, 0);
    }

    public static void start(EvalScenario scenario, String runId, int round) {
        EvalRunTrace trace = new EvalRunTrace();
        trace.setRunId(runId == null || runId.isBlank() ? "eval-" + UUID.randomUUID() : runId);
        trace.setScenarioId(scenario.getScenarioId());
        trace.setRound(round);
        trace.setStartedAtEpochMs(System.currentTimeMillis());
        CURRENT.set(trace);
        USAGE.set(new UsageRecorder());
    }

    public static Optional<EvalRunTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Records one model invocation. No-op outside an evaluation run, which keeps the
     * chat path free of evaluation bookkeeping.
     */
    public static void recordUsage(int promptTokens, int completionTokens, String model) {
        EvalRunTrace trace = CURRENT.get();
        UsageRecorder recorder = USAGE.get();
        if (trace == null || recorder == null) {
            return;
        }
        recorder.add(promptTokens, completionTokens, model);
    }

    public static EvalRunTrace.ToolCall beginTool(String toolName, Map<String, ?> arguments) {
        EvalRunTrace trace = CURRENT.get();
        if (trace == null) {
            return null;
        }

        EvalRunTrace.ToolCall toolCall = new EvalRunTrace.ToolCall();
        toolCall.setToolName(toolName);
        toolCall.setArguments(serialize(arguments));
        toolCall.setStartedAtEpochMs(System.currentTimeMillis());
        toolCall.setAttempts(1);
        trace.getToolCalls().add(toolCall);
        return toolCall;
    }

    public static void finishTool(EvalRunTrace.ToolCall toolCall, String result) {
        if (toolCall == null) {
            return;
        }
        toolCall.setDurationMs(System.currentTimeMillis() - toolCall.getStartedAtEpochMs());

        // 优先读工具显式声明的三态结果；读不到才退回字符串启发式
        String declared = declaredStatus(result);
        if (declared != null) {
            toolCall.setStatus(declared);
            toolCall.setSuccess(!"ERROR".equals(declared));
            toolCall.setDataFound("OK".equals(declared));
        } else {
            // 顺序很重要：先判"无数据"再判"错误"。
            // 旧载荷在查空时同时带有 success=false 和 logs=[]，若先判错误就会把它误分类成失败。
            // 而错误响应从不包含 logs/total 字段，所以优先判无数据不会吃掉真错误。
            boolean noData = isNoDataResult(result);
            boolean failed = !noData && isExecutionError(result);
            toolCall.setSuccess(!failed);
            toolCall.setDataFound(!failed && !noData);
            toolCall.setStatus(failed ? "ERROR" : (noData ? "EMPTY" : "OK"));
        }
        toolCall.setResultSummary(summarize(result));
    }

    /**
     * Reads an explicit {@code status} field from a tool payload.
     *
     * <p>This is what separates "ran fine, found nothing" from "failed": both used to be
     * expressed as {@code success=false}, which made an honest empty search count as a failed
     * tool call and deflated the tool-accuracy metric.
     *
     * @return {@code OK} / {@code EMPTY} / {@code ERROR}, or {@code null} when the payload
     *         does not declare one (in which case the caller falls back to heuristics).
     */
    private static String declaredStatus(String result) {
        if (result == null) {
            return null;
        }
        for (String candidate : new String[]{"OK", "EMPTY", "ERROR"}) {
            if (result.contains("\"status\" : \"" + candidate + "\"")
                    || result.contains("\"status\":\"" + candidate + "\"")
                    || result.contains("\"status\": \"" + candidate + "\"")) {
                return candidate;
            }
        }
        return null;
    }

    /** Marks how many attempts the guarded call needed, and the guard verdict when it refused to run. */
    public static void markToolGuard(EvalRunTrace.ToolCall toolCall, int attempts, String guardState) {
        if (toolCall == null) {
            return;
        }
        toolCall.setAttempts(Math.max(attempts, 1));
        toolCall.setGuardState(guardState);
    }

    public static void completeSuccess(String finalReport) {
        complete(true, finalReport, null);
    }

    public static void completeFailure(String error) {
        complete(false, null, error);
    }

    private static void complete(boolean success, String finalReport, String error) {
        EvalRunTrace trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        trace.setSuccess(success);
        trace.setFinalReport(finalReport);
        trace.setError(error);
        trace.setFinishedAtEpochMs(System.currentTimeMillis());
        trace.setDurationMs(trace.getFinishedAtEpochMs() - trace.getStartedAtEpochMs());
        UsageRecorder recorder = USAGE.get();
        if (recorder != null) {
            trace.setUsage(recorder.snapshot());
        }
    }

    public static void clear() {
        CURRENT.remove();
        USAGE.remove();
    }

    /** Accumulates token counters for the current evaluation thread. */
    private static final class UsageRecorder {

        private final EvalRunTrace.Usage usage = new EvalRunTrace.Usage();

        void add(int promptTokens, int completionTokens, String model) {
            usage.add(promptTokens, completionTokens);
            if (model != null && !model.isBlank() && usage.getModel() == null) {
                usage.setModel(model);
            }
        }

        String modelName() {
            return usage.getModel();
        }

        EvalRunTrace.Usage snapshot() {
            return usage;
        }
    }

    private static String serialize(Map<String, ?> arguments) {
        try {
            return OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            return "{\"traceSerializationError\":true}";
        }
    }

    /**
     * Classifies a tool result as an execution error.
     *
     * <p>Results are plain JSON strings, so the verdict is derived from the payload. Both the
     * compact and the pretty-printed shape must be recognised: tools serialise with Jackson's
     * default pretty printer ({@code "success" : false}), while other paths emit compact JSON.
     * The rejection marker matters because a guard refusal is a failed call — counting it as a
     * success would let rejected invocations inflate the tool-accuracy metric.
     */
    private static boolean isExecutionError(String result) {
        if (result == null) {
            return true;
        }
        return result.contains("\"status\": \"error\"")
                || result.contains("\"success\":false")
                || result.contains("\"success\": false")
                || result.contains("\"success\" : false")
                || result.contains(REJECTION_MARKER)
                || result.contains("\"message\" : \"查询失败")
                || result.contains("\"message\": \"查询失败");
    }

    private static boolean isNoDataResult(String result) {
        return result != null && (result.contains("\"status\": \"no_results\"")
                || result.contains("\"total\" : 0")
                || result.contains("\"total\":0")
                || result.contains("\"logs\" : [ ]")
                || result.contains("\"logs\":[]"));
    }

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SUMMARY_LIMIT
                ? normalized
                : normalized.substring(0, SUMMARY_LIMIT) + "...";
    }
}
