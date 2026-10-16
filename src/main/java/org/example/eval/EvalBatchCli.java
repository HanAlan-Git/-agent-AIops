package org.example.eval;

import org.example.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command line entry point for batch evaluation:
 *
 * <pre>
 * # 完整 classpath 由 pom 里的 exec-maven-plugin 提供
 * mvn -B -ntp exec:java "-Dexec.args=--rounds 3 --parallelism 2"
 * </pre>
 *
 * It starts the application (the evaluation endpoint lives in the same process), drives the
 * corpus through HTTP, aggregates the traces and writes {@code eval-reports/summary-<ts>.json}.
 */
public final class EvalBatchCli {

    private static final Logger logger = LoggerFactory.getLogger(EvalBatchCli.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private EvalBatchCli() {
    }

    public static void main(String[] args) {
        int rounds = intArg(args, "--rounds", 3);
        int parallelism = intArg(args, "--parallelism", 2);
        List<String> requested = listArg(args, "--scenarios");

        ConfigurableApplicationContext context = SpringApplication.run(Main.class, args);
        try {
            EvalBatchRunner runner = context.getBean(EvalBatchRunner.class);
            EvalTraceStore store = context.getBean(EvalTraceStore.class);

            List<String> scenarios = requested.isEmpty() ? runner.discoverScenarios() : requested;
            if (scenarios.isEmpty()) {
                logger.error("未发现任何评测场景，请检查 src/main/resources/eval/scenarios");
                return;
            }
            logger.info("开始批量评测: {} 个场景 × {} 轮，并发 {}", scenarios.size(), rounds, parallelism);

            List<EvalRunTrace> traces = runner.runAll(scenarios, rounds, parallelism);
            if (traces.isEmpty()) {
                logger.error("没有采集到任何 trace，请确认服务已启动且场景执行成功");
                return;
            }

            EvalTraceStore.EvalBatchSummary summary = store.summarize(traces);
            String fileName = "summary-" + TIMESTAMP.format(LocalDateTime.now()) + ".json";
            store.saveSummary(summary, fileName);

            System.out.println();
            System.out.println("================ 评测汇总 ================");
            System.out.printf("场景数           : %d%n", summary.getScenarios());
            System.out.printf("轮次 / 运行数    : %d / %d%n", summary.getRounds(), summary.getRuns());
            System.out.printf("执行成功率       : %.2f  (流程跑完的占比)%n", summary.getSuccessRate());
            System.out.printf("诊断达标率       : %.2f  (%d/%d，必调工具+根因+证据全覆盖且总分>=80)%n",
                    summary.getTaskSuccessRate(), summary.getTaskSuccessRuns(), summary.getRuns());
            System.out.printf("平均总分         : %.2f%n", summary.getAvgScore());
            System.out.printf("工具调用准确率   : %.2f (%d 次调用)%n",
                    summary.getToolCallAccuracy(), summary.getToolCalls());
            System.out.printf("平均工具调用次数 : %.2f%n", summary.getAvgToolCalls());
            System.out.printf("延迟 平均/P50/P95: %.0f / %d / %d ms%n",
                    summary.getAvgLatencyMs(), summary.getP50LatencyMs(), summary.getP95LatencyMs());
            System.out.printf("Token 平均       : prompt %.0f + completion %.0f（%d/%d 次运行有上报）%n",
                    summary.getAvgPromptTokens(), summary.getAvgCompletionTokens(),
                    summary.getTokensReportedRuns(), summary.getRuns());
            System.out.printf("平均/总成本      : %.4f / %.4f %s%n",
                    summary.getAvgCostCny(), summary.getTotalCostCny(), summary.getPricing());
            System.out.println("失败标签分布     : " + summary.getFailureTagHistogram());
            System.out.println("工具守卫分布     : " + summary.getGuardStateHistogram());
            System.out.printf("汇总文件         : %s%n", store.outputDirectory().resolve(fileName).toAbsolutePath());
            System.out.println("=========================================");
        } finally {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }

    private static int intArg(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    logger.warn("参数 {} 不是整数，使用默认值 {}", name, fallback);
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static List<String> listArg(String[] args, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                Arrays.stream(args[i + 1].split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .forEach(values::add);
            }
        }
        return values;
    }
}
