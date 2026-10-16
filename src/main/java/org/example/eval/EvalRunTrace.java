package org.example.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次评测运行的完整追踪记录。
 * <p>
 * 每轮场景执行都会生成一个实例，包含时间线、工具调用明细、模型用量以及最终评分，
 * 用于离线评测报告的生成与失败归因分析。
 */
@Data
public class EvalRunTrace {

    /** 本次运行的唯一标识符 */
    private String runId;
    /** 关联的评测场景 ID */
    private String scenarioId;
    /** 当前轮次编号（同一场景可多轮执行） */
    private int round;
    /** 运行开始时间戳（毫秒，epoch） */
    private long startedAtEpochMs;
    /** 运行结束时间戳（毫秒，epoch） */
    private long finishedAtEpochMs;
    /** 运行总耗时（毫秒） */
    private long durationMs;
    /** 运行是否成功完成（无异常中断） */
    private boolean success;
    /** 运行失败时的错误信息，成功时为 null */
    private String error;
    /** Agent 最终输出的诊断报告文本 */
    private String finalReport;
    /** 本次运行中所有工具调用的详细记录 */
    private List<ToolCall> toolCalls = new ArrayList<>();
    /** 模型调用用量统计（token、费用等） */
    private Usage usage = new Usage();
    /** 评分结果，未完成评分时为 null */
    private EvalScore score;

    /**
     * 单次工具调用的追踪信息。
     * 记录工具名称、参数、耗时、结果以及重试/熔断等守卫状态。
     */
    @Data
    public static class ToolCall {
        /** 被调用的工具名称 */
        private String toolName;
        /** 传递给工具的 JSON 格式参数 */
        private String arguments;
        /** 调用发起时间戳（毫秒，epoch） */
        private long startedAtEpochMs;
        /** 调用耗时（毫秒） */
        private long durationMs;
        /** 调用是否成功返回 */
        private boolean success;
        /** 工具返回结果中是否包含有效数据 */
        private boolean dataFound;
        /**
         * 三态结果标识：{@code OK} 有数据、{@code EMPTY} 执行成功但无匹配数据、
         * {@code ERROR} 执行失败（参数被拒 / 异常 / 依赖不可用）。
         *
         * <p>为什么需要它：{@code success} 一个布尔无法同时表达"工具跑通了吗"和
         * "查到东西了吗"。旧实现用 {@code success=false} 表达"查空"，导致评测把
         * 诚实查空统计成工具失败，工具准确率被严重低估。
         */
        private String status;
        /** 对工具返回结果的简要摘要 */
        private String resultSummary;
        /** 实际尝试次数；大于 1 表示触发过重试或熔断 */
        private int attempts;
        /** 守卫状态（如 CLOSED / OPEN / HALF_OPEN），用于熔断器诊断 */
        private String guardState;
    }

    /**
     * 场景运行期间的模型用量统计。
     * 当底层模型调用未暴露 usage 元数据时，token 计数器保持为零。
     */
    @Data
    public static class Usage {
        /** 使用的模型名称 */
        private String model;
        /** 模型调用总次数 */
        private int calls;
        /** 累计 prompt token 数 */
        private long promptTokens;
        /** 累计 completion token 数 */
        private long completionTokens;
        /** prompt + completion 的总 token 数 */
        private long totalTokens;
        /** 是否从模型响应中成功获取到 token 用量信息 */
        private boolean tokensReported;
        /** 定价策略描述（如 "per_1k_tokens"） */
        private String pricing;
        /** 本次运行的估算费用（人民币） */
        private double costCny;

        /**
         * 累加一次模型调用的 token 用量。
         *
         * @param prompt     本次调用的 prompt token 数，负值按零处理
         * @param completion 本次调用的 completion token 数，负值按零处理
         */
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

    /**
     * 评测打分结果。
     * 基于黄金标准场景对 Agent 输出的工具调用准确率、根因覆盖率、证据覆盖率等维度进行量化评分。
     */
    @Data
    public static class EvalScore {
        /** 是否已完成评分 */
        private boolean scored;
        /** 任务是否整体成功（综合判定） */
        private boolean taskSuccess;
        /** 加权总分（0.0 ~ 1.0） */
        private double total;
        /** 必须调用的工具中被正确触达的比率 */
        private double requiredToolRecall;
        /** 工具调用参数与预期的匹配准确率 */
        private double toolCallAccuracy;
        /** 黄金标准中列出的根因被覆盖的比率 */
        private double rootCauseCoverage;
        /** 黄金标准中列出的证据项被引用的比率 */
        private double evidenceCoverage;
        /** 黄金标准中列出的处置建议被覆盖的比率 */
        private double actionCoverage;
        /** RAG 检索是否命中了相关文档 */
        private boolean ragResultFound;
        /** 匹配到的诊断类型 ID（对应黄金标准中的预期诊断） */
        private String matchedDiagnosisId;
        /** 失败标签列表，用于快速归类问题（如 TOOL_MISSING、WRONG_ROOT_CAUSE 等） */
        private List<String> failureTags = new ArrayList<>();
    }
}
