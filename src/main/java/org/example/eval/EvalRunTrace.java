package org.example.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EvalRunTrace {

    private String runId;
    private String scenarioId;
    private int round;
    private long startedAtEpochMs;
    private long finishedAtEpochMs;
    private long durationMs;
    private boolean success;
    private String error;
    private String finalReport;
    private List<ToolCall> toolCalls = new ArrayList<>();
    private Usage usage = new Usage();
    private EvalScore score;

    @Data
    public static class ToolCall {
        private String toolName;
        private String arguments;
        private long startedAtEpochMs;
        private long durationMs;
        private boolean success;
        private boolean dataFound;
        private String resultSummary;
        /** Set when a retry was required before the call succeeded, or when the guard opened the circuit. */
        private int attempts;
        private String guardState;
    }

    /**
     * Model usage observed while the scenario ran. Token counters stay at zero when
     * the underlying model invocation did not expose usage metadata.
     */
    @Data
    public static class Usage {
        private String model;
        private int calls;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private boolean tokensReported;
        private String pricing;
        private double costCny;

        public void add(int prompt, int completion) {
            this.calls++;
            this.promptTokens += Math.max(prompt, 0);
            this.completionTokens += Math.max(completion, 0);
            this.totalTokens = this.promptTokens + this.completionTokens;
            if (prompt > 0 || completion > 0) {
                this.tokensReported = true;
            }
        }
    }

    @Data
    public static class EvalScore {
        private boolean scored;
        private boolean taskSuccess;
        private double total;
        private double requiredToolRecall;
        private double toolCallAccuracy;
        private double rootCauseCoverage;
        private double evidenceCoverage;
        private double actionCoverage;
        private boolean ragResultFound;
        private String matchedDiagnosisId;
        private List<String> failureTags = new ArrayList<>();
    }
}
