package org.example.eval;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Drives the offline evaluation corpus against a running server: for every scenario it
 * calls {@code POST /api/ai_ops/eval/{scenarioId}} and waits for the stream to finish,
 * then reads the trace the server persisted.
 *
 * <p>Run ids are deterministic ({@code eval-{scenarioId}-r{round}}), which is what lets
 * this driver collect results without adding any API surface to the server.
 */
@Service
public class EvalBatchRunner {

    private static final Logger logger = LoggerFactory.getLogger(EvalBatchRunner.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** How long to wait for a trace file to appear after its SSE stream ended. */
    private static final long TRACE_WAIT_TIMEOUT_MS = 120_000L;
    /** Poll interval while waiting for the trace file. */
    private static final long TRACE_WAIT_POLL_MS = 250L;

    private final EvalTraceStore traceStore;
    private final EvalGoldenScenarioService scenarioService;
    private final String baseUrl;
    private final long timeoutSeconds;
    private final OkHttpClient httpClient;

    public EvalBatchRunner(EvalTraceStore traceStore,
                           EvalGoldenScenarioService scenarioService,
                           @Value("${eval.runner.base-url:http://localhost:9900}") String baseUrl,
                           @Value("${eval.runner.timeout-seconds:600}") long timeoutSeconds) {
        this.traceStore = traceStore;
        this.scenarioService = scenarioService;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        // OkHttp 3 API（DashScope SDK 传递进来的版本）没有 Duration 重载，统一用 TimeUnit
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /** Scenario ids that have both a runtime payload and a golden label file. */
    public List<String> discoverScenarios() {
        return scenarioService.listScenarioIds();
    }

    /**
     * Runs every scenario for the requested number of rounds.
     *
     * @param rounds      repeats per scenario, used to observe variance instead of one lucky run
     * @param parallelism concurrent server-side runs; keep small because the AIOps flow is model bound
     */
    public List<EvalRunTrace> runAll(List<String> scenarioIds, int rounds, int parallelism) {
        List<EvalRunTrace> traces = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(parallelism, 1));
        try {
            for (int round = 1; round <= Math.max(rounds, 1); round++) {
                List<Future<EvalRunTrace>> futures = new ArrayList<>();
                for (String scenarioId : scenarioIds) {
                    final int currentRound = round;
                    futures.add(pool.submit(() -> runOnce(scenarioId, currentRound)));
                }
                for (Future<EvalRunTrace> future : futures) {
                    try {
                        EvalRunTrace trace = future.get();
                        if (trace != null) {
                            traces.add(trace);
                        }
                    } catch (Exception e) {
                        logger.error("评测任务失败", e);
                    }
                }
                logger.info("第 {} 轮完成，累计 trace {} 条", round, traces.size());
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        return traces;
    }

    /**
     * Runs one scenario and waits for its trace to land on disk.
     *
     * <p>The SSE {@code done} event and the trace file are written by two different code paths
     * inside the server: the controller emits {@code done} first and persists the trace later in
     * its {@code finally} block. So reading the file immediately after the stream ends races
     * against the write — observed as {@code Unexpected end-of-input} and, once the write became
     * atomic, as a file that simply did not exist yet. Neither outcome is an error, so the driver
     * polls patiently instead of treating the first miss as a failure.
     */
    private EvalRunTrace runOnce(String scenarioId, int round) {
        if (!consumeStream(scenarioId, round)) {
            logger.warn("场景 {} 第 {} 轮未正常结束", scenarioId, round);
        }
        String runId = EvalTraceContext.batchRunId(scenarioId, round);
        long deadline = System.currentTimeMillis() + TRACE_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Optional<EvalRunTrace> trace = traceStore.load(runId);
                if (trace.isPresent()) {
                    return trace.get();
                }
            } catch (RuntimeException e) {
                // 文件存在但还没写完：等一下再读
                logger.debug("Trace {} 暂不可读，继续等待", runId, e);
            }
            sleep(TRACE_WAIT_POLL_MS);
        }
        logger.error("等待 Trace 落盘超时（{} ms）: {}", TRACE_WAIT_TIMEOUT_MS, runId);
        return null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean consumeStream(String scenarioId, int round) {
        String url = String.format("%s/api/ai_ops/eval/%s?round=%d", baseUrl, scenarioId, round);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("{}", JSON))
                .header("Accept", "text/event-stream")
                .build();

        logger.info("触发评测场景: {}", url);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("评测场景 {} 返回 HTTP {}", scenarioId, response.code());
                return false;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return false;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"type\":\"done\"") || line.contains("\"type\": \"done\"")) {
                        return true;
                    }
                }
            }
            return true;
        } catch (IOException e) {
            logger.error("评测场景 {} 流式读取失败", scenarioId, e);
            return false;
        }
    }
}
