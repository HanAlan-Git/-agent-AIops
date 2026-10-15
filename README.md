# SuperBizAgent

> 基于 Spring Boot + Spring AI Alibaba 的多 Agent 智能运维（AIOps）系统：告警 → 知识检索 → 步骤规划 → 工具调用 → 报告生成，并配套离线评测体系量化效果。

[![CI](https://github.com/HanAlan-Git/HanAlan-Git--agent-AiOps-/actions/workflows/ci.yml/badge.svg)](https://github.com/HanAlan-Git/HanAlan-Git--agent-AiOps-/actions/workflows/ci.yml)

---

## 项目解决什么问题

线上告警排查高度依赖个人经验，知识散落在文档、日志、监控系统里，且每一步的结论都缺少可核对的证据。本项目把这条链路自动化：

- **智能问答**（`/api/chat`、`/api/chat_stream`）：ReAct 单步推理。Agent 自行决定是否调用时间、内部知识库、Prometheus 告警、云日志等工具，支持多轮对话与 SSE 流式输出。
- **智能运维**（`/api/ai_ops`）：Supervisor 调度 Planner 与 Executor 的 Plan-Execute 多步闭环。Planner 以 `decision = PLAN | EXECUTE | FINISH` 推进，Executor 只执行当前第一步并把证据结构化回灌，最终输出固定模板的《告警分析报告》。

关键在于**不编造**：工具只返回真实数据，故障时如实降级，并且有离线评测在金标准上验证「诊断是否正确」。

---

## 核心能力

| 能力 | 说明 |
|---|---|
| 多 Agent 编排 | Supervisor 动态重派 Planner / Executor；`decision` 驱动循环直至 FINISH |
| 工具调用 | 4 类本地工具白名单装配（时间 / 知识库 / Prometheus / 云日志），参数 Schema 由框架生成，预留 MCP 远程工具接入 |
| 入参治理 | `logTopic` / `region` 在工具入口校验，非法参数直接拒绝并回灌可选值，替代静默降级 |
| RAG 知识增强 | Markdown 标题分块 + 段落边界优先 + 800/100 重叠 → `text-embedding-v4`（1024 维）→ Milvus `IVF_FLAT`（L2, nprobe=10）Top-3 |
| 离线评测 | 23 条场景语料 + 金标准覆盖度评分 + 失败标签归因，与运行时数据物理隔离 |
| 可观测 | 每次评测落盘 JSON trace（工具序列 / 参数 / 耗时 / 是否查到数据 / Token / 评分），批量汇总出 `summary-*.json` |
| 会话管理 | 内存态会话，6 轮滑窗裁剪，单会话加锁保证读写安全 |

---

## 技术栈

| 技术 | 版本 | 作用 |
|---|---|---|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架、Web、DI |
| Spring AI | 1.1.0 | 工具调用（`@Tool` / `@ToolParam`）与模型抽象 |
| Spring AI Alibaba | 1.1.0.0-RC2 | Agent Framework（`ReactAgent` / `SupervisorAgent`） |
| DeepSeek | `deepseek-chat` | 对话与 Agent 推理 |
| DashScope | SDK 2.17.0 | 文本向量化 `text-embedding-v4` |
| Milvus | 2.6.10 | 向量检索（Docker Compose 自带 etcd / MinIO / Attu） |
| OkHttp / Jackson / Lombok | - | HTTP、序列化、样板代码 |

---

## 快速开始

### 1. 环境要求

JDK 17、Maven 3.9+、Docker Desktop、DeepSeek 与 DashScope 的 API Key。

### 2. 配置环境变量

项目使用**专用变量名**，避免与本机其它工具的同名变量互相覆盖：

```powershell
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY_AGENT", "<你的 DeepSeek Key>", "User")
[Environment]::SetEnvironmentVariable("DASHSCOPE_API_KEY",      "<你的 DashScope Key>", "User")
# 当前窗口也需要显式赋值（新进程才会读到用户级变量）
$env:DEEPSEEK_API_KEY_AGENT = "<你的 DeepSeek Key>"
$env:DASHSCOPE_API_KEY      = "<你的 DashScope Key>"
```

`application-local.yml` 已从版本库移除并加入 `.gitignore`（历史上曾误提交明文 Key）。如需本地覆盖配置，复制模板：

```powershell
Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
# 填入 Key；该文件不会被提交
```

### 3. 启动向量库

```powershell
docker compose -f vector-database.yml up -d
docker ps --filter "name=milvus" --format "{{.Names}} {{.Status}}"
```

Milvus 管理界面 Attu：<http://localhost:8000>

### 4. 启动应用

```powershell
mvn -B -ntp -DskipTests package
java -jar target\super-biz-agent-1.0-SNAPSHOT.jar
```

默认监听 <http://localhost:9900>。注意应用**启动阶段就会连接 Milvus 并建表**，Milvus 未就绪会直接启动失败。

### 5. 灌入知识库

```powershell
Get-ChildItem .\aiops-docs\*.md | ForEach-Object {
    curl.exe -X POST http://localhost:9900/api/upload -F "file=@$($_.FullName)"
}
curl.exe http://localhost:9900/milvus/health
```

健康检查期望返回 `{"message":"ok","collections":["biz"]}`。

---

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 普通对话，一次性返回，支持工具调用 |
| POST | `/api/chat_stream` | SSE 流式对话 |
| POST | `/api/ai_ops` | 触发多 Agent 告警分析，SSE 流式输出报告 |
| POST | `/api/ai_ops/eval/{scenarioId}?round=N` | 在确定性场景上运行 AIOps，用于离线评测 |
| POST | `/api/chat/clear` | 清空会话历史 |
| GET | `/api/chat/session/{sessionId}` | 查询会话信息 |
| POST | `/api/upload` | 上传 `.txt` / `.md` 并向量化入库 |
| GET | `/milvus/health` | Milvus 连接与集合检查 |

请求示例：

```powershell
curl.exe -X POST http://localhost:9900/api/chat `
  -H "Content-Type: application/json" `
  -d '{\"Id\":\"session-001\",\"Question\":\"CPU 使用率告警应该怎么排查？\"}'
```

---

## 评测体系

这是本项目的重点：**Agent 类系统如果只能说「跑通了」，是无法判断好坏的。**

### 怎么保证评分不打折扣

- **语料**：`src/main/resources/eval/scenarios/` 共 **23 条**，覆盖 CPU / 内存 / 磁盘 / 服务不可用 / 慢响应五类，每类 4~5 条；其中 2 条是负样本——一条考察「证据不足时是否如实回答无法定位」，一条考察「非法工具参数是否被拒绝」。
- **金标准隔离**：标注放在 `eval/golden/`，运行时场景放在 `eval/scenarios/`，**Agent 的工具无论如何读不到金标准**，评分不会自证。
- **确定性评分**（`EvalScoringService`）：不使用 LLM 打分。金标准把答案拆成「根因 / 证据 / 处置」三组同义词组，命中每组至少一个词才算覆盖。

  ```
  总分 = 工具 25（必调工具召回 × 0.6 + 调用准确率 × 0.4）
       + 根因 30 + 证据 25 + 处置 10 + 完成 10
  ```

- **失败标签**：输出 `TOOL_MISSING`、`TOOL_INVALID_OR_FAILED`、`RAG_NO_RESULT`、`ROOT_CAUSE_MISS`、`EVIDENCE_GAP`、`ACTION_GAP`、`EXECUTION_FAILURE`，便于按类型做 Bad Case 归因。

### 已有基线（真实运行数据）

| 场景 | 总分 | 工具调用准确率 | 端到端耗时 | 失败标签 |
|---|---|---|---|---|
| `slow-response-db-query-01` | 99.2 | 0.92 | 17.9 s | `TOOL_INVALID_OR_FAILED` `RAG_NO_RESULT` |
| `slow-response-db-query-01` | 95.8 | 0.58 | 19.8 s | `TOOL_INVALID_OR_FAILED` |

两次运行总分都很高，但**工具调用准确率只有 0.58 / 0.92** —— 高分掩盖了工具层问题：存在非预期工具调用与知识库查空。这正是把「参数校验 + 失败熔断」列为下一步优化的直接依据。原始 trace 位于 `eval-reports/`（已加入 `.gitignore`）。

### 如何跑批量评测

```powershell
# 先小规模验证链路（含 1 条负样本）
mvn -B -ntp exec:java "-Dexec.args=--rounds 1 --scenarios cpu-thread-pool-backlog-01,neg-tool-param-invalid-01"

# 全量：23 场景 × 3 轮
mvn -B -ntp exec:java "-Dexec.args=--rounds 3 --parallelism 2"
```

> CLI 会自行启动应用（评测端点在同进程），请先确认 9900 端口空闲。单次约 18~20 秒，69 次调用串行约 25 分钟。

输出 `eval-reports/summary-<时间戳>.json`，字段包括：成功率、平均总分、工具调用准确率、平均步数、P50/P95/最大延迟、平均 Token、平均/总成本、失败标签与工具守卫分布。

**Token 与成本口径**：`TokenUsageTrackingChatModel` 仅在评测上下文中采集用量；若模型未返回 `usage`，`tokensReported` 保持 `false` 且**不写入任何估算数字**。成本按 `eval.pricing.*` 配置的价目推导，并在汇总里标注价目来源。

---

## 单元测试

```powershell
mvn -B -ntp test
```

**50 个用例**，全部**不依赖 Milvus 与模型 Key**，可在干净 CI 上跑通：

| 测试类 | 用例 | 覆盖内容 |
|---|---|---|
| `ToolParameterValidatorTest` | 26 | 参数校验：合法/非法地域与主题、TopicId 形态、`limit` 夹紧 |
| `EvalScoringServiceTest` | 7 | 评分器：满分路径、缺工具、空检索、执行失败、缺标注 |
| `EvalTraceStoreSummaryTest` | 6 | 汇总聚合：成功率、工具准确率、P50/P95、Token 与成本、不伪造用量 |
| `EvalScenarioCorpusTest` | 4 | 语料完整性：每条场景都有标注、数量与分类、夹具不污染生产语料 |
| `DocumentChunkServiceTest` | 7 | 分块：标题切分、段落边界、重叠、索引连续、空输入 |

CI 见 `.github/workflows/ci.yml`（`mvn -B -ntp verify`）。

### 测试发现的一个真实缺陷

`DocumentChunkService` 会把文档**开头的顶级标题切成独立分片**（实测 `chunk[0] content="# 标题"`，只有标题、没有正文）。这类分片永远不可能被正文检索命中，是 RAG 漏召的一个具体成因。当前测试固化了这一行为并标注为已知缺陷，修复后把断言改成 `hasSize(1)` 即可当回归测试使用。

---

## 目录结构

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/          HTTP 接口层（ChatController 统一入口 + 评测端点）
│   ├── service/             业务层（ChatService / AiOpsService / RAG / 向量服务）
│   ├── agent/tool/          Agent 工具（时间 / 知识库 / Prometheus / 日志）+ 参数校验
│   ├── eval/               评测体系（场景、金标准、评分、trace、批量驱动、CLI）
│   ├── model/              Token 用量采集装饰器
│   ├── client/             Milvus 客户端工厂
│   ├── config/             配置与属性绑定
│   └── dto/ constant/      数据传输对象与常量
├── src/main/resources/
│   ├── application.yml      主配置（eval.* 评测与价目配置）
│   ├── eval/scenarios/      23 条运行时场景
│   ├── eval/golden/         23 条金标准标注（不暴露给 Agent）
│   └── static/              Web 测试界面
├── src/test/java/           50 个单元测试
├── aiops-docs/              运维知识库样例文档
├── vector-database.yml      Milvus Docker Compose
├── PROJECT_GUIDE.md         源码逐模块理解指南
└── .github/workflows/ci.yml CI
```

---

## 已知边界

诚实列出当前实现的局限，便于后续迭代：

1. **RAG 只有单路向量检索**：尚无混合检索与重排；已观察到长尾告警描述的漏召（`RAG_NO_RESULT` 标签）。计划做两阶段检索（召回池 20 → 重排取 3~5）。
2. **工具失败尚无熔断**：目前靠 Prompt 约束「同一工具连续失败 3 次即停止」，尚未落到代码机制；`guardState` 字段已预留。
3. **会话为内存态**：重启即丢失，多实例不共享，无持久化与鉴权限流。
4. **MCP 仅预留**：`spring.ai.mcp.client.enabled` 默认为 `false`，日志工具当前使用 mock 数据源。
5. **长期记忆未实现**：仅 6 轮滑窗短期上下文，尚未落地按 Token 预算裁剪与跨会话召回。
6. **TopicId 形态偏严**：为拦住 `system-metric` 这类自然语言猜测，TopicId 要求「至少两个分隔符且含数字」。纯字母、分隔符少于两个的真实 TopicId 会被误拒——这是有意选择的失败方向（宁可让模型收到可选值后重试，也不要静默返回无关日志）。

---

## 许可证

MIT

---

**作者**：韩震傲
