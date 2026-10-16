package org.example.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists one trace per evaluation run and aggregates a batch into a single
 * machine-readable summary. Cost is derived from configured per-million-token
 * prices, so a summary always states which price list it used.
 */
@Service
public class EvalTraceStore {

    private final ObjectMapper objectMapper;
    private final Path outputDirectory;
    private final Pricing pricing;

    public EvalTraceStore(ObjectMapper objectMapper,
                          @Value("${eval.trace.output-dir:./eval-reports}") String outputDirectory,
                          @Value("${eval.pricing.prompt-cny-per-million:2.0}") double promptCnyPerMillion,
                          @Value("${eval.pricing.completion-cny-per-million:8.0}") double completionCnyPerMillion,
                          @Value("${eval.pricing.prompt-cache-hit-cny-per-million:0.04}") double promptCacheHitCnyPerMillion,
                          @Value("${eval.pricing.currency:CNY}") String currency,
                          @Value("${eval.pricing.source:DeepSeek public price list}") String source) {
        this.objectMapper = objectMapper;
        this.outputDirectory = Path.of(outputDirectory);
        this.pricing = new Pricing(promptCnyPerMillion, completionCnyPerMillion,
                promptCacheHitCnyPerMillion, currency, source);
    }

    @PostConstruct
    void prepareDirectory() throws IOException {
        Files.createDirectories(outputDirectory);
    }

    /**
     * Persists a trace atomically.
     *
     * <p>Writing straight to the target path leaves a window where the file exists but its
     * content is only partially flushed. A batch runner polling for that file can then read a
     * truncated JSON document — which is exactly what happened: the driver failed with
     * {@code Unexpected end-of-input within/between Object entries} while the file on disk was
     * complete a moment later. Writing to a temp file and moving it into place makes readers
     * observe either the old state or the complete new state, never a partial one.
     */
    public Path save(EvalRunTrace trace) {
        try {
            Files.createDirectories(outputDirectory);
            applyCost(trace);
            Path outputFile = outputDirectory.resolve(trace.getRunId() + ".json");
            Path tempFile = Files.createTempFile(outputDirectory, trace.getRunId() + "-", ".json.tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), trace);
                try {
                    Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
            return outputFile;
        } catch (IOException e) {
            throw new IllegalStateException("无法保存评测 Trace", e);
        }
    }

    /**
     * Reads a trace, tolerating the moment before it is fully written.
     *
     * <p>A trace that cannot be parsed yet is retried instead of throwing: during a batch run the
     * server and the driver are separate processes, so "the file is not complete" is a normal
     * transient state rather than an error. Only a persistent failure returns empty, which the
     * caller already handles.
     */
    public Optional<EvalRunTrace> load(String runId) {
        Path file = outputDirectory.resolve(runId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return Optional.of(objectMapper.readValue(file.toFile(), EvalRunTrace.class));
            } catch (JsonProcessingException e) {
                lastFailure = e;
                sleep(100);
            } catch (IOException e) {
                throw new IllegalStateException("无法读取评测 Trace: " + runId, e);
            }
        }
        throw new IllegalStateException("无法读取评测 Trace（重试后仍解析失败）: " + runId, lastFailure);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Pricing pricing() {
        return pricing;
    }

    /** Aggregates traces into the numbers that go on a resume or a dashboard. */
    public EvalBatchSummary summarize(List<EvalRunTrace> traces) {
        EvalBatchSummary summary = new EvalBatchSummary();
        summary.setRuns(traces.size());
        summary.setPricing(pricing.describe());
        summary.setScenarios(traces.stream().map(EvalRunTrace::getScenarioId).distinct().count());
        summary.setRounds(traces.stream().mapToInt(EvalRunTrace::getRound).max().orElse(0));
        if (traces.isEmpty()) {
            return summary;
        }

        long successCount = 0;
        long scoredCount = 0;
        long taskSuccessRuns = 0;
        long toolCalls = 0;
        long accurateToolCalls = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long tokensReportedRuns = 0;
        double totalScore = 0;
        List<Long> durations = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        Map<String, Integer> failureTags = new LinkedHashMap<>();
        Map<String, Integer> guardStates = new LinkedHashMap<>();

        for (EvalRunTrace trace : traces) {
            durations.add(trace.getDurationMs());
            if (trace.isSuccess()) {
                successCount++;
            }
            EvalRunTrace.EvalScore score = trace.getScore();
            if (score != null && score.isScored()) {
                scoredCount++;
                totalScore += score.getTotal();
                if (score.isTaskSuccess()) {
                    taskSuccessRuns++;
                }
                for (String tag : score.getFailureTags()) {
                    failureTags.merge(tag, 1, Integer::sum);
                }
            }
            for (EvalRunTrace.ToolCall call : trace.getToolCalls()) {
                toolCalls++;
                if (call.isSuccess()) {
                    accurateToolCalls++;
                }
                if (call.getGuardState() != null && !call.getGuardState().isBlank()) {
                    guardStates.merge(call.getGuardState(), 1, Integer::sum);
                }
            }
            EvalRunTrace.Usage usage = trace.getUsage();
            if (usage != null) {
                promptTokens += usage.getPromptTokens();
                completionTokens += usage.getCompletionTokens();
                if (usage.isTokensReported()) {
                    tokensReportedRuns++;
                }
            }
        }

        summary.setSuccessRate(round((double) successCount / traces.size()));
        summary.setTaskSuccessRuns(taskSuccessRuns);
        summary.setTaskSuccessRate(round((double) taskSuccessRuns / traces.size()));
        summary.setAvgScore(scoredCount == 0 ? 0 : round(totalScore / scoredCount));
        summary.setScoredRuns(scoredCount);
        summary.setToolCalls(toolCalls);
        summary.setToolCallAccuracy(toolCalls == 0 ? 0 : round((double) accurateToolCalls / toolCalls));
        summary.setAvgToolCalls(round((double) toolCalls / traces.size()));
        summary.setAvgLatencyMs(round(average(durations)));
        summary.setP50LatencyMs(percentile(durations, 0.50));
        summary.setP95LatencyMs(percentile(durations, 0.95));
        summary.setMaxLatencyMs(durations.stream().mapToLong(Long::longValue).max().orElse(0));
        summary.setAvgPromptTokens(round((double) promptTokens / traces.size()));
        summary.setAvgCompletionTokens(round((double) completionTokens / traces.size()));
        summary.setTotalPromptTokens(promptTokens);
        summary.setTotalCompletionTokens(completionTokens);
        summary.setTokensReportedRuns(tokensReportedRuns);
        summary.setAvgCostCny(round(costOf(promptTokens, completionTokens) / traces.size()));
        summary.setTotalCostCny(round(costOf(promptTokens, completionTokens)));
        summary.setFailureTagHistogram(failureTags);
        summary.setGuardStateHistogram(guardStates);
        latencies.clear();
        return summary;
    }

    public void saveSummary(EvalBatchSummary summary, String fileName) {
        try {
            Files.createDirectories(outputDirectory);
            Path file = outputDirectory.resolve(fileName);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存评测汇总", e);
        }
    }

    /** Lists saved traces, newest first. */
    public List<Path> listTraceFiles() {
        if (!Files.exists(outputDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(outputDirectory)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith("summary-"))
                    .sorted(Comparator.comparing((Path path) -> path.toFile().lastModified()).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法枚举评测报告目录", e);
        }
    }

    private void applyCost(EvalRunTrace trace) {
        EvalRunTrace.Usage usage = trace.getUsage();
        if (usage == null) {
            return;
        }
        usage.setPricing(pricing.describe());
        usage.setCostCny(round(costOf(usage.getPromptTokens(), usage.getCompletionTokens())));
    }

    private double costOf(long promptTokens, long completionTokens) {
        return promptTokens / 1_000_000.0 * pricing.promptCnyPerMillion()
                + completionTokens / 1_000_000.0 * pricing.completionCnyPerMillion();
    }

    private double average(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return (double) sum / values.size();
    }

    private long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        index = Math.max(index, 0);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Per-million-token price list used for cost estimation.
     *
     * <p>{@code promptCacheHitCnyPerMillion} is recorded so the price list is complete and
     * reviewable, but the current cost formula does <em>not</em> apply it: cache hits are not
     * distinguishable from the usage metadata we receive, so every prompt token is charged at
     * the (higher) miss rate. That makes the reported cost a conservative upper bound.
     */
    public record Pricing(double promptCnyPerMillion, double completionCnyPerMillion,
                          double promptCacheHitCnyPerMillion, String currency, String source) {

        public String describe() {
            return String.format("%s prompt=%.2f/M completion=%.2f/M (%s)",
                    currency, promptCnyPerMillion, completionCnyPerMillion, source);
        }
    }

    /** Aggregated view over a batch of traces. */
    @Data
    public static class EvalBatchSummary {
        private long scenarios;
        private int rounds;
        private int runs;
        private long scoredRuns;

        /**
         * 通过严格诊断判定（{@code taskSuccess}）的运行数：必调工具全覆盖 + 根因与证据全覆盖 + 总分 ≥ 80。
         *
         * <p>与 {@link #successRate} 的区别很关键：{@code successRate} 只表示"流程跑完了"，
         * 而这个字段才回答"诊断是否达标"。两者混用会把执行成功率说成答对率。
         */
        private long taskSuccessRuns;

        /** {@code taskSuccessRuns / runs}，即诊断达标率。 */
        private double taskSuccessRate;

        private double successRate;
        private double avgScore;
        private long toolCalls;
        private double toolCallAccuracy;
        private double requiredToolRecall;
        private double avgToolCalls;
        private double avgLatencyMs;
        private long p50LatencyMs;
        private long p95LatencyMs;
        private long maxLatencyMs;
        private double avgPromptTokens;
        private double avgCompletionTokens;
        private long totalPromptTokens;
        private long totalCompletionTokens;
        private long tokensReportedRuns;
        private double avgCostCny;
        private double totalCostCny;
        private String pricing;
        private Map<String, Integer> failureTagHistogram = new LinkedHashMap<>();
        private Map<String, Integer> guardStateHistogram = new LinkedHashMap<>();
    }
}
