# SuperBizAgent 项目从零理解指南

本文以当前源码为准，帮助第一次接触项目的开发者理解 SuperBizAgent 的定位、启动方式、模块职责、请求链路、数据流和扩展方法。

> 重要说明：项目同时保留了一个较早实现的 `RagService`，但当前聊天接口主要通过 `ReactAgent + InternalDocsTools` 完成知识库检索。阅读代码时应以实际 Controller 调用链为准，而不能只根据类名或 README 推断。

---

## 1. 项目解决什么问题

SuperBizAgent 是一个基于 Spring Boot、Spring AI Alibaba、DashScope 和 Milvus 的智能 OnCall/AIOps 应用，主要解决两类问题：

1. **智能问答**
   - 用户通过 Web 页面或 REST API 提问。
   - Agent 根据问题决定是否调用时间、内部文档、Prometheus 告警、日志等工具。
   - 普通对话可以一次性返回，也可以通过 SSE 流式返回。
   - 对话历史在服务端按会话 ID 临时保存。

2. **智能运维分析**
   - 用户点击 AI Ops 后，后端自动读取活动告警。
   - Supervisor Agent 调度 Planner Agent 和 Executor Agent。
   - Planner 负责规划和再规划，Executor 负责执行当前步骤。
   - 最后由 Planner 输出 Markdown 格式的告警分析报告。

可以把整个项目概括为：

```text
用户问题
   |
   v
ChatController
   |
   v
ChatService 创建 ReactAgent
   |
   +--> DashScope 大语言模型
   |
   +--> 时间工具
   +--> 内部文档工具 --> 文本向量化 --> Milvus 相似度检索
   +--> Prometheus 告警工具
   +--> 日志工具或腾讯云 MCP 工具
```

AI Ops 的链路如下：

```text
/api/ai_ops
   |
   v
AiOpsService
   |
   v
Supervisor Agent
   |
   +--> Planner Agent: 规划 / 再规划
   |
   +--> Executor Agent: 执行第一步并返回证据
   |
   v
最终告警分析报告
```

---

## 2. 技术栈和运行时

| 技术 | 当前项目中的作用 |
|---|---|
| Java 17 | 主要开发语言 |
| Spring Boot 3.2.0 | Web 服务、依赖注入、配置管理 |
| Spring AI 1.1.0 | AI 工具和模型抽象 |
| Spring AI Alibaba | DashScope ChatModel、Agent Framework |
| DashScope Java SDK 2.17.0 | 文本 Embedding 和传统 Generation API |
| Milvus SDK 2.6.10 | 连接、建表、写入和向量搜索 |
| Docker Compose | 启动 Milvus、etcd、MinIO、Attu |
| OkHttp | Prometheus 查询和部分 HTTP 客户端配置 |
| Maven | Java 项目构建和启动 |
| 原生 HTML/CSS/JavaScript | Web 前端 |

项目入口是：

```text
org.example.Main
```

启动后默认监听：

```text
http://localhost:9900
```

---

## 3. 目录结构

```text
SuperBizAgent/
├── pom.xml                         Maven 依赖和构建配置
├── README.md                       简版项目说明
├── PROJECT_GUIDE.md                本文档
├── Makefile                        Linux/macOS 下的启动和上传脚本
├── vector-database.yml             Milvus Docker Compose 配置
├── aiops-docs/                     AIOps 知识库样例文档
│   ├── cpu_high_usage.md
│   ├── disk_high_usage.md
│   ├── memory_high_usage.md
│   ├── service_unavailable.md
│   └── slow_response.md
├── src/main/java/org/example/
│   ├── Main.java                   Spring Boot 启动入口
│   ├── controller/                 HTTP 接口层
│   ├── service/                    业务服务层
│   ├── agent/tool/                 Agent 可调用的工具
│   ├── client/                     外部客户端工厂
│   ├── config/                     Spring 配置和属性绑定
│   ├── constant/                   常量
│   ├── dto/                        数据传输对象
│   └── tool/                       独立工具类
└── src/main/resources/
    ├── application.yml             应用配置
    └── static/                     前端页面、脚本、样式
```

### 3.1 Controller 层

| 文件 | 作用 |
|---|---|
| `ChatController.java` | 普通对话、流式对话、清理会话、查看会话、AI Ops |
| `FileUploadController.java` | 上传 `.txt`/`.md` 文件，并触发向量索引 |
| `MilvusCheckController.java` | 检查 Milvus 连接和集合列表 |

### 3.2 Service 层

| 文件 | 作用 |
|---|---|
| `ChatService.java` | 创建 DashScope ChatModel，构造系统提示词，配置 ReactAgent |
| `AiOpsService.java` | 创建 Supervisor、Planner、Executor 并执行告警分析 |
| `RagService.java` | 传统 RAG 流式问答实现，目前不是 `/api/chat` 的主调用链 |
| `DocumentChunkService.java` | Markdown 标题、段落和重叠分片 |
| `VectorEmbeddingService.java` | 调用 DashScope Embedding API |
| `VectorIndexService.java` | 文档读取、分片、向量化、写入 Milvus |
| `VectorSearchService.java` | 查询向量生成和 Milvus 相似度搜索 |

### 3.3 Agent 工具

| 文件 | 工具 | 作用 |
|---|---|---|
| `DateTimeTools.java` | `getCurrentDateTime` | 获取当前时间 |
| `InternalDocsTools.java` | `queryInternalDocs` | 查询 Milvus 内部知识库 |
| `QueryMetricsTools.java` | `queryPrometheusAlerts` | 查询 Prometheus 活跃告警 |
| `QueryLogsTools.java` | `getAvailableLogTopics`、`queryLogs` | Mock 日志查询；真实环境日志由 MCP 提供 |

---

## 4. 启动前准备

### 4.1 必需软件

需要准备：

1. JDK 17
2. Maven
3. Docker Desktop 或 Docker Engine
4. 可访问 DashScope 的网络
5. DashScope API Key

当前环境中 Maven 能识别到 Java 17。项目代码使用 Java 17 语法，不能用较低版本 JDK 编译。

### 4.2 配置 API Key

PowerShell：

```powershell
$env:DASHSCOPE_API_KEY = "你的 DashScope API Key"
```

如果希望永久配置，可以在 Windows 系统环境变量中增加：

```text
DASHSCOPE_API_KEY
```

项目会在两个配置项中读取这个值：

```yaml
spring.ai.dashscope.api-key
dashscope.api.key
```

前者用于 Agent 对话，后者用于 Embedding 和传统 RAG。

### 4.3 启动 Milvus

在项目根目录执行：

```powershell
docker compose -f vector-database.yml up -d
```

启动的主要容器：

| 容器 | 作用 | 本机端口 |
|---|---|---|
| `milvus-etcd` | Milvus 元数据存储 | 容器内部使用 |
| `milvus-minio` | 对象存储 | `9000`、`9001` |
| `milvus-standalone` | 向量数据库 | `19530`、`9091` |
| `milvus-attu` | Milvus 管理界面 | `8000` |

常用检查命令：

```powershell
docker ps
docker compose -f vector-database.yml logs standalone
```

Attu 地址：

```text
http://localhost:8000
```

### 4.4 启动 Spring Boot

```powershell
mvn spring-boot:run
```

或者先打包：

```powershell
mvn clean package
java -jar target\super-biz-agent-1.0-SNAPSHOT.jar
```

启动时，`MilvusClientFactory` 会执行以下逻辑：

1. 连接 `localhost:19530`。
2. 检查名为 `biz` 的 Collection 是否存在。
3. 如果不存在，则创建 Collection。
4. 创建向量索引。
5. 将 `MilvusServiceClient` 注册为 Spring Bean。

因此，Milvus 没有启动时，应用很可能在初始化阶段失败，而不是等到第一次查询时才失败。

### 4.5 上传初始知识库

PowerShell 示例：

```powershell
Get-ChildItem .\aiops-docs\*.md | ForEach-Object {
    curl.exe -X POST http://localhost:9900/api/upload `
        -F "file=@$($_.FullName)"
}
```

也可以上传单个文件：

```powershell
curl.exe -X POST http://localhost:9900/api/upload `
    -F "file=@.\aiops-docs\cpu_high_usage.md"
```

上传接口会自动完成：

```text
保存文件 -> 删除旧索引 -> 文档分片 -> 生成 Embedding -> 插入 Milvus
```

---

## 5. 配置文件详解

配置文件：

```text
src/main/resources/application.yml
```

### 5.1 Web 配置

```yaml
server:
  port: 9900
```

服务端口默认是 `9900`。前端在 `src/main/resources/static/app.js` 中把 API 地址固定为：

```javascript
http://localhost:9900/api
```

如果修改端口，需要同时修改前端的 `apiBaseUrl`，否则页面会继续请求旧端口。

### 5.2 文件上传配置

```yaml
file:
  upload:
    path: ./uploads
    allowed-extensions: txt,md
```

当前只允许上传 `txt` 和 `md`。上传目录默认是项目根目录下的 `uploads`，该目录通常被 `.gitignore` 忽略。

### 5.3 Milvus 配置

```yaml
milvus:
  host: localhost
  port: 19530
  username: ""
  password: ""
  database: default
  timeout: 10000
```

注意：

- `MilvusProperties` 负责绑定配置。
- `MilvusClientFactory` 负责真正创建客户端。
- 当前 Collection 名称不是配置项，而是写死在 `MilvusConstants.MILVUS_COLLECTION_NAME` 中，值为 `biz`。
- 当前数据库名常量也是 `default`。

### 5.4 DashScope 配置

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}
      chat:
        options:
          timeout: 180000

dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:your-api-key-here}
  embedding:
    model: text-embedding-v4
```

代码中有两套模型调用方式：

1. `DashScopeChatModel`
   - 用于 ReactAgent、Supervisor、Planner、Executor。
   - 当前普通聊天使用温度 `0.7`、最大输出 `2000`、Top-P `0.9`。

2. DashScope SDK `Generation`
   - 用于 `RagService` 的传统流式生成。
   - 使用 `rag.model` 配置。

`DashScopeConfig` 会把 HTTP 客户端的连接、读、写和整体调用超时统一设置为配置中的超时时间。

### 5.5 文档分片配置

```yaml
document:
  chunk:
    max-size: 800
    overlap: 100
```

- `max-size`：分片最大字符数。
- `overlap`：相邻分片之间尽量保留的重叠字符数。

### 5.6 RAG 配置

```yaml
rag:
  top-k: 3
  model: qwen3-max
```

- `top-k` 同时影响 `InternalDocsTools` 和 `RagService`。
- `model` 主要供 `RagService` 使用。
- 当前 `/api/chat` 的 Agent 会调用 `InternalDocsTools`，由工具内部调用 `VectorSearchService`。

### 5.7 Prometheus 配置

```yaml
prometheus:
  base-url: http://localhost:9090
  timeout: 10
  mock-enabled: false
```

- `false`：请求 `http://localhost:9090/api/v1/alerts`。
- `true`：使用代码内置的 CPU、内存和慢响应告警。

### 5.8 CLS/MCP 配置

```yaml
cls:
  mock-enabled: false
```

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        ...
```

日志工具有两种来源：

- `cls.mock-enabled: true`：使用 `QueryLogsTools` 内置 Mock 数据。
- `cls.mock-enabled: false`：应用尝试通过 Spring AI MCP Client 注入远程工具，配置中的 SSE 地址指向腾讯云 MCP 服务。

当前 `ChatService` 和 `AiOpsService` 会根据 `QueryLogsTools` Bean 是否存在动态构建工具列表。MCP 提供的工具通过 `ToolCallbackProvider` 统一接入。

---

## 6. Milvus 数据结构

Collection 名称：

```text
biz
```

字段定义来自 `MilvusClientFactory.createBizCollection()`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `VarChar` | 主键，由文件路径和分片序号生成 UUID |
| `vector` | `FloatVector` | 文档分片的 Embedding |
| `content` | `VarChar` | 分片原文，最大长度 8192 |
| `metadata` | `JSON` | 文件路径、扩展名、文件名、分片序号、标题等 |

向量参数：

```text
维度: 1024
索引: IVF_FLAT
距离: L2
nlist: 128
搜索 nprobe: 10
```

这里存在一个必须注意的耦合：

```text
MilvusConstants.VECTOR_DIM = 1024
```

Embedding 模型实际返回的向量维度必须也是 1024，否则写入或搜索时会维度不匹配。更换 Embedding 模型时，要同时确认模型维度，并在必要时删除旧 Collection 后重建。

删除旧索引时，代码根据以下元数据表达式匹配：

```text
metadata["_source"] == "标准化后的文件路径"
```

因此同一个文件再次上传时，会先删除旧分片，再写入新分片。

---

## 7. 文档上传和向量化流程

入口：

```text
FileUploadController.upload()
```

完整流程：

```text
multipart 文件
    |
    v
校验非空、文件名、扩展名
    |
    v
保存到 ./uploads
    |
    v
VectorIndexService.indexSingleFile()
    |
    +--> 读取完整文本
    +--> 删除当前文件的旧 Milvus 数据
    +--> DocumentChunkService.chunkDocument()
    +--> 每个分片调用 VectorEmbeddingService
    +--> 写入 biz Collection
```

### 7.1 文档分片算法

`DocumentChunkService` 的优先级是：

1. 按 Markdown 标题切章节。
2. 章节长度不超过 `max-size` 时，直接生成一个分片。
3. 章节过长时，按双换行分割成段落。
4. 尽量在段落边界切分。
5. 新分片开头保留上一分片末尾的重叠内容。
6. 如果重叠区域中有中文句号、问号或感叹号，尽量从句子边界开始重叠。

每个 `DocumentChunk` 包含：

```text
content
startIndex
endIndex
chunkIndex
title
```

### 7.2 Embedding 流程

`VectorEmbeddingService` 调用 DashScope `TextEmbedding`：

```text
文本分片
   |
   v
text-embedding-v4
   |
   v
List<Float>
```

服务启动时会检查 API Key。如果仍然是 `your-api-key-here`，会直接抛出异常，导致应用无法正常启动。

### 7.3 索引写入

写入时：

- `id` 使用 `_source + "_" + chunkIndex` 的名字生成 UUID。
- `content` 写入分片文本。
- `vector` 写入浮点向量。
- `metadata` 写入 JSON。

`VectorIndexService.indexDirectory()` 支持批量索引目录，但当前没有公开 HTTP 接口直接调用它，日常上传主要走 `FileUploadController` 的单文件接口。

---

## 8. 智能聊天流程

### 8.1 普通接口

```http
POST /api/chat
Content-Type: application/json
```

请求：

```json
{
  "Id": "session-001",
  "Question": "CPU 使用率告警应该怎么排查？"
}
```

字段大小写兼容：

```text
Id / id / ID
Question / question / QUESTION
```

处理过程：

1. 校验问题不能为空。
2. 根据会话 ID 创建或获取 `SessionInfo`。
3. 读取当前会话历史。
4. `ChatService` 创建 DashScope ChatModel。
5. 拼接系统提示词和历史消息。
6. 创建 `ReactAgent`。
7. Agent 根据用户问题自动决定是否调用工具。
8. 调用 `agent.call(question)`。
9. 把用户问题和最终回答保存到会话历史。
10. 返回统一 JSON。

成功响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "answer": "..."
  }
}
```

业务错误结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": false,
    "errorMessage": "..."
  }
}
```

注意：Controller 捕获异常后也会返回 HTTP 200，但 `data.success` 为 `false`。客户端不能只依赖 HTTP 状态码判断业务成功。

### 8.2 ReactAgent 使用的工具

`ChatService.createReactAgent()` 同时配置两种工具：

```java
.methodTools(...)
.tools(getToolCallbacks())
```

- `methodTools`：本地 Spring Bean 方法工具。
- `tools`：`ToolCallbackProvider` 提供的工具，主要用于 MCP。

当前系统提示词明确告诉模型：

- 时间问题使用 `getCurrentDateTime`。
- 内部流程、最佳实践、技术指南使用 `queryInternalDocs`。
- Prometheus 告警使用 `queryPrometheusAlerts`。
- 腾讯云日志使用 MCP 日志工具。

模型并不是每次都调用工具。它根据问题、工具描述和系统提示词自行判断。

### 8.3 流式接口

```http
POST /api/chat_stream
Content-Type: application/json
Accept: text/event-stream
```

后端使用 `SseEmitter` 和 `agent.stream()`，只把 Agent 模型增量输出的内容发送给前端。

SSE 数据中的 JSON 结构：

```json
{
  "type": "content",
  "data": "文本片段"
}
```

结束：

```json
{
  "type": "done",
  "data": null
}
```

错误：

```json
{
  "type": "error",
  "data": "错误信息"
}
```

前端 `app.js` 会读取响应流，按换行解析 SSE，再将内容增量拼接并用 Markdown 渲染。

### 8.4 会话管理

服务端会话存储在：

```java
ConcurrentHashMap<String, SessionInfo> sessions
```

每个会话最多保存 6 对消息：

```text
用户问题 + 助手回答 = 1 对
MAX_WINDOW_SIZE = 6
```

超出后从最早的消息对开始删除。

服务端会话特点：

- 只存在当前 JVM 内存中。
- 应用重启后全部丢失。
- 没有数据库持久化。
- 使用 `ReentrantLock` 保证单会话读写安全。
- 如果请求不传会话 ID，后端会生成 UUID，但普通 HTTP 响应没有把新 ID 返回给前端，因此调用方最好主动传入稳定的会话 ID。

前端另有一套历史记录：

- 存储在浏览器 `localStorage`。
- 最多保留 50 条。
- 用于页面左侧的历史对话展示。
- 它和后端的 `sessions` 不是同一个持久化系统。

---

## 9. 内部文档检索流程

当前聊天中的知识库检索不是 Controller 直接调用 `RagService`，而是：

```text
ReactAgent
   |
   v
InternalDocsTools.queryInternalDocs()
   |
   v
VectorSearchService.searchSimilarDocuments()
   |
   +--> query 文本生成向量
   +--> Milvus Search
   +--> 返回 top-k 个 SearchResult
```

`SearchParam` 使用：

```text
Collection: biz
Vector field: vector
Metric: L2
Top K: rag.top-k
Output fields: id, content, metadata
Search params: {"nprobe":10}
```

工具最终返回 JSON，模型根据 JSON 中的内容生成自然语言回答。

### 9.1 `RagService` 的位置

`RagService` 是另一条较传统的 RAG 实现：

```text
问题
  -> VectorSearchService
  -> 拼接参考资料 Prompt
  -> DashScope Generation.streamCall()
  -> StreamCallback
```

它支持历史消息和流式回调，但当前项目中没有 Controller 直接注入并调用它。因此：

- 想维护当前 Agent 聊天，应优先看 `ChatController`、`ChatService`、`InternalDocsTools`。
- 想复用传统 RAG 流程，才需要继续看 `RagService`。

---

## 10. AI Ops 智能运维流程

入口：

```http
POST /api/ai_ops
```

该接口不要求用户传入问题，后端使用固定任务提示词启动告警分析。

### 10.1 Agent 角色

#### Supervisor Agent

职责：

- 决定调用 Planner、Executor 还是结束。
- 让流程在规划、执行、再规划之间循环。
- 最终确保输出告警分析报告。

#### Planner Agent

职责：

- 读取当前任务和 Executor 反馈。
- 分析告警、日志、内部文档。
- 产出 `PLAN`、`EXECUTE` 或 `FINISH` 决策。
- `FINISH` 时输出完整 Markdown 报告。

输出键：

```text
planner_plan
```

#### Executor Agent

职责：

- 读取 Planner 的最新步骤。
- 只执行第一步。
- 调用需要的工具。
- 把真实结果整理为证据和下一步建议。

输出键：

```text
executor_feedback
```

### 10.2 运行过程

```text
Supervisor
   |
   v
Planner: 先查询当前告警，输出执行计划
   |
   v
Executor: 执行计划中的第一步，查询 Prometheus/日志/文档
   |
   v
Planner: 根据反馈判断是否需要下一步
   |
   +--> EXECUTE: 再交给 Executor
   |
   +--> PLAN: 重新规划
   |
   +--> FINISH: 输出 Markdown 报告
```

`AiOpsService.extractFinalReport()` 从最终状态中读取：

```text
state.value("planner_plan")
```

并要求它是 `AssistantMessage`，再提取文本作为最终报告。

### 10.3 报告模板

Planner 的系统提示词要求最终报告包括：

1. 活跃告警清单
2. 每个告警的根因分析
3. 告警详情和症状
4. 日志证据
5. 已执行的排查步骤
6. 处理建议和预期效果
7. 总体结论
8. 关键发现
9. 后续建议
10. 风险评估

报告必须从 Markdown 标题开始，不能以 JSON 包裹。

---

## 11. 工具详解

### 11.1 时间工具

文件：

```text
agent/tool/DateTimeTools.java
```

方法：

```text
getCurrentDateTime()
```

使用当前请求的时区上下文返回时间。如果用户只是问当前时间，Agent 应调用它，而不是让模型凭记忆回答。

### 11.2 内部文档工具

文件：

```text
agent/tool/InternalDocsTools.java
```

输入：

```text
query
```

输出是搜索结果 JSON，包含：

```text
id
content
score
metadata
```

### 11.3 Prometheus 工具

文件：

```text
agent/tool/QueryMetricsTools.java
```

真实模式请求：

```text
GET {prometheus.base-url}/api/v1/alerts
```

处理逻辑：

- 读取 Prometheus 返回的告警列表。
- 相同 `alertname` 只保留第一个。
- 提取描述、状态、首次活动时间和持续时长。
- 返回简化 JSON。

Mock 模式内置三类告警：

- `HighCPUUsage`
- `HighMemoryUsage`
- `SlowResponse`

### 11.4 日志工具

文件：

```text
agent/tool/QueryLogsTools.java
```

先调用：

```text
getAvailableLogTopics()
```

可用主题：

| 主题 | 说明 |
|---|---|
| `system-metrics` | CPU、内存、磁盘 |
| `application-logs` | 应用错误、慢请求、下游依赖 |
| `database-slow-query` | 数据库慢查询 |
| `system-events` | Pod 重启、OOM Kill、容器崩溃 |

再调用：

```text
queryLogs(region, logTopic, query, limit)
```

Mock 模式会根据主题和关键词返回预设日志，例如：

- CPU 高：`payment-service` CPU 使用率和线程信息。
- 内存高：JVM 堆、Full GC、Old Gen 信息。
- 慢响应：数据库查询耗时、接口耗时、缓存命中情况。
- 服务异常：连接池耗尽、HTTP 500、OOM、Pod 重启。

真实模式下，这个本地 Bean 的 `queryLogs` 不负责访问真实 CLS，而是由 MCP 工具接管。MCP 配置错误、SSE Endpoint 无效或认证失效时，日志查询会失败。

---

## 12. REST API 清单

### 12.1 普通聊天

```text
POST /api/chat
```

```json
{
  "Id": "session-001",
  "Question": "什么是 HighCPUUsage 告警？"
}
```

### 12.2 流式聊天

```text
POST /api/chat_stream
```

请求体与普通聊天相同，响应为 SSE。

### 12.3 清理会话

```text
POST /api/chat/clear
```

```json
{
  "Id": "session-001"
}
```

成功时返回：

```json
{
  "code": 200,
  "message": "success",
  "data": "会话历史已清空"
}
```

### 12.4 查询会话信息

```text
GET /api/chat/session/{sessionId}
```

返回会话创建时间和消息对数。

### 12.5 AI Ops

```text
POST /api/ai_ops
```

响应为 SSE，前端把多个 `content` 片段拼接成完整报告。

### 12.6 上传文件

```text
POST /api/upload
Content-Type: multipart/form-data
```

表单字段：

```text
file
```

### 12.7 Milvus 健康检查

```text
GET /milvus/health
```

正常示例：

```json
{
  "message": "ok",
  "collections": [
    "biz"
  ]
}
```

---

## 13. 前端是如何工作的

前端文件：

```text
src/main/resources/static/index.html
src/main/resources/static/app.js
src/main/resources/static/styles.css
```

### 13.1 页面功能

- 新建对话
- 浏览器本地保存历史对话
- 快速模式：调用 `/api/chat`
- 流式模式：调用 `/api/chat_stream`
- 上传 `.txt`/`.md`
- 点击 AI Ops 调用 `/api/ai_ops`
- 使用 `marked` 渲染 Markdown
- 使用 `highlight.js` 高亮代码

### 13.2 前端消息保存

发送用户消息时，前端先将用户消息加入当前页面历史。

普通模式下：

1. 添加“正在思考”占位消息。
2. 请求 `/api/chat`。
3. 删除占位消息。
4. 将后端回答加入页面。

流式模式下：

1. 创建空的助手消息。
2. 读取 `ReadableStream`。
3. 按 SSE 行解析。
4. 收到 `content` 就追加到完整回答。
5. 实时 Markdown 渲染。
6. 收到 `done` 后结束。

### 13.3 前端和后端历史的差异

前端历史存储在浏览器，后端上下文存储在 JVM：

```text
前端 localStorage: 页面显示和历史列表
后端 ConcurrentHashMap: 模型上下文
```

如果应用重启，前端仍可能显示历史对话，但后端已经没有相应上下文。这时继续发送同一个会话 ID，后端会创建一个新的空会话。

---

## 14. 常见问题排查

### 14.1 应用启动时报 Milvus 连接失败

检查：

```powershell
docker ps
curl.exe http://localhost:9091/healthz
curl.exe http://localhost:9900/milvus/health
```

确认：

- Milvus 容器处于 `Up` 状态。
- 本机 `19530` 端口没有被其他程序占用。
- `application.yml` 中 host/port 与 Docker 映射一致。
- Docker Desktop 已启动。

### 14.2 API Key 未配置

典型表现：

```text
API Key 未正确配置
```

PowerShell 重新设置：

```powershell
$env:DASHSCOPE_API_KEY = "你的 Key"
mvn spring-boot:run
```

注意：在一个 PowerShell 窗口设置的临时环境变量，不一定会影响另一个已经打开的窗口。

### 14.3 上传成功但检索不到

按以下顺序检查：

1. 上传接口是否返回 `code: 200`。
2. 服务日志中是否出现“向量索引创建成功”。
3. 是否调用了正确的 Embedding 模型。
4. 向量维度是否仍为 1024。
5. `GET /milvus/health` 是否能看到 `biz`。
6. Attu 中是否存在数据。
7. 查询内容是否和文档有语义关联。

### 14.4 更换 Embedding 模型后报维度错误

Milvus Collection 的向量维度创建后不能直接修改。处理步骤：

1. 确认新模型的向量维度。
2. 修改 `MilvusConstants.VECTOR_DIM`。
3. 删除旧的 `biz` Collection。
4. 重启应用自动建表。
5. 重新上传所有知识库文档。

项目中已有一个独立工具：

```text
src/main/java/org/example/tool/DropCollection.java
```

它可以删除本地 Milvus 中的 `biz` Collection。执行前应确认不会误删重要数据。

### 14.5 AI Ops 查询不到日志

检查：

- `cls.mock-enabled` 是否符合当前环境预期。
- 如果使用 Mock，日志主题是否是支持的四种主题。
- 如果使用 MCP，SSE URL 是否真实有效。
- MCP 服务是否需要额外认证。
- `spring.ai.mcp.client.enabled` 是否开启。
- 日志中是否打印出了可用的 ToolCallback。

### 14.6 前端页面能打开但请求失败

检查：

1. 后端是否监听 `9900`。
2. `app.js` 的 `apiBaseUrl` 是否匹配后端端口。
3. 浏览器开发者工具 Network 面板中的请求地址。
4. 是否被跨域策略拦截。当前后端已允许所有来源的 CORS。
5. CDN 上的 `marked` 或 `highlight.js` 是否加载失败。即使 CDN 失败，文本请求仍可能可用，但 Markdown 渲染会降级。

### 14.7 Makefile 在 Windows 下不能直接用

`Makefile` 使用了 `nohup`、`curl`、`grep`、`sed`、`pkill` 等 Unix 命令。Windows PowerShell 下建议直接使用：

```powershell
docker compose -f vector-database.yml up -d
mvn spring-boot:run
```

上传文件使用 `curl.exe`，不要依赖 PowerShell 的 `curl` 别名行为。

---

## 15. 修改项目时应该从哪里入手

### 想修改聊天行为

优先查看：

```text
ChatController.java
ChatService.java
```

常见修改：

- 系统提示词：`ChatService.buildSystemPrompt()`
- 模型温度、最大 Token：`ChatService.createStandardChatModel()`
- 会话窗口：`ChatController.MAX_WINDOW_SIZE`
- SSE 超时：`chatStream()` 中的 `SseEmitter`

### 想新增 Agent 工具

步骤：

1. 在 `agent/tool` 下创建 Spring `@Component`。
2. 给公开方法加 `@Tool`。
3. 给参数添加 `@ToolParam` 描述。
4. 在 `ChatService` 和 `AiOpsService` 的 `buildMethodToolsArray()` 中加入 Bean。
5. 在系统提示词中说明什么时候调用该工具。
6. 如果是远程 MCP 工具，则配置 MCP Client，并确认 `ToolCallbackProvider` 能发现它。

### 想修改知识库分片

查看：

```text
DocumentChunkService.java
DocumentChunkConfig.java
application.yml
```

可以调整：

- 标题识别规则。
- 段落边界。
- 分片最大长度。
- 重叠策略。

### 想修改 Milvus Schema

查看：

```text
MilvusClientFactory.java
MilvusConstants.java
VectorIndexService.java
VectorSearchService.java
```

修改 Schema 后必须考虑旧 Collection 的兼容性，通常需要删除并重建 Collection，再重新导入文档。

### 想修改 AI Ops 流程

查看：

```text
AiOpsService.java
```

关键方法：

```text
executeAiOpsAnalysis()
buildPlannerAgent()
buildExecutorAgent()
buildPlannerPrompt()
buildExecutorPrompt()
buildSupervisorSystemPrompt()
extractFinalReport()
```

### 想修改前端交互

查看：

```text
index.html
app.js
styles.css
```

请求方法集中在 `app.js`：

```text
sendQuickMessage()
sendStreamMessage()
sendAIOpsRequest()
handleFileSelect()
```

---

## 16. 当前实现的边界和风险点

以下内容是理解项目时必须知道的现实边界：

1. **会话是内存状态**
   - 重启服务会丢失后端上下文。
   - 多实例部署时不同实例之间不会共享会话。

2. **上传接口对索引失败采取“上传仍成功”策略**
   - 文件保存成功后，即使向量索引失败，接口仍可能返回成功。
   - 需要结合日志确认知识库是否真的写入。

3. **向量维度写死为 1024**
   - 更换 Embedding 模型必须同步重建 Collection。

4. **Milvus 客户端启动时强依赖数据库**
   - 应用启动阶段就会连接 Milvus 并建表。

5. **README 和实际启动命令存在细节差异**
   - Docker Compose 推荐使用 `docker compose -f vector-database.yml up -d`。
   - `Makefile` 更适合 Unix 环境，Windows PowerShell 需要手工执行等价命令。

6. **`RagService` 不是当前聊天接口的主链路**
   - 当前 `/api/chat` 使用 ReactAgent。
   - 传统 Generation 流式 RAG 代码仍然存在，但没有被当前 Controller 直接使用。

7. **日志真实查询依赖 MCP 配置**
   - `QueryLogsTools` 的本地实现主要用于 Mock。
   - 生产环境需要确认远程 MCP 服务、区域参数、权限和 SSE Endpoint。

8. **错误响应风格不完全统一**
   - 部分异常通过 HTTP 200 返回业务错误。
   - 客户端应同时检查 HTTP 状态和 JSON 中的 `code/success/errorMessage`。

9. **前端历史和后端历史相互独立**
   - 浏览器可能保留历史展示，但后端上下文已经失效。

10. **缺少持久化、鉴权和生产级限流**
    - 当前更像一个功能验证和内部使用项目。
    - 生产化时需要增加认证、权限、审计、会话存储、限流、脱敏和监控。

---

## 17. 推荐的阅读顺序

第一次阅读源码时，建议按下面顺序：

1. `README.md`
2. `application.yml`
3. `Main.java`
4. `ChatController.java`
5. `ChatService.java`
6. `InternalDocsTools.java`
7. `VectorSearchService.java`
8. `VectorIndexService.java`
9. `DocumentChunkService.java`
10. `VectorEmbeddingService.java`
11. `MilvusClientFactory.java`
12. `AiOpsService.java`
13. `QueryMetricsTools.java`
14. `QueryLogsTools.java`
15. `src/main/resources/static/app.js`

理解主链路时，先不要从所有配置类和前端 CSS 开始。先跟踪一个具体场景：

```text
上传 cpu_high_usage.md
    -> 分片
    -> 向量化
    -> 写入 Milvus
    -> 用户提问“CPU 告警如何排查”
    -> Agent 调用 queryInternalDocs
    -> Milvus 返回相关片段
    -> DashScope 生成回答
```

再跟踪第二个场景：

```text
点击 AI Ops
    -> 查询 Prometheus 告警
    -> 查询日志
    -> Planner/Executor 循环
    -> 输出告警分析报告
```

掌握这两条链路后，项目中大多数类的作用都会变得直观。

---

## 18. 一句话总结

SuperBizAgent 的核心不是单纯的聊天页面，而是一个把 **大模型 Agent、内部知识库检索、Prometheus 告警、日志工具和多 Agent 运维编排** 串在一起的 Spring Boot 应用：

```text
文档通过 Embedding 写入 Milvus，
用户问题交给 ReactAgent 决定是否调用工具，
AI Ops 再通过 Supervisor 调度 Planner 和 Executor，
最终把真实监控证据整理成可读的运维结论。
```
