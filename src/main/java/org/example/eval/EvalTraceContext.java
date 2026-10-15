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
        toolCall.setSuccess(!isExecutionError(result));
        toolCall.setDataFound(!isNoDataResult(result) && toolCall.isSuccess());
        toolCall.setResultSummary(summarize(result));
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

    private static boolean isExecutionError(String result) {
        return result == null
                || result.contains("\"status\": \"error\"")
                || result.contains("\"success\":false")
                || result.contains("\"success\": false")
                || result.contains("\"message\" : \"查询失败")
                || result.contains("\"message\": \"查询失败");
    }

    private static boolean isNoDataResult(String result) {
        return result != null && (result.contains("\"status\": \"no_results\"")
                || result.contains("\"total\" : 0")
                || result.contains("\"total\":0"));
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
