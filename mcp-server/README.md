# 示例 MCP Server（SSE 传输）

本目录提供一个**零依赖**的 MCP Server，用于验证本项目「本地 `@Tool` + 远程 MCP 工具」的双通道装配。
它只依赖 Python 3.9+ 标准库，不需要 `pip install`。

## 为什么仓库里要放这个

`pom.xml` 引入了 `spring-ai-starter-mcp-client-webflux`，`application.yml` 的
`spring.ai.mcp.client` 负责连接。如果没有一个可运行的 Server，这条链路就只能靠"配置写好了"
来声称可用。放一个能跑起来的 Server，任何人都能在两分钟内复现下述证据。

## 启动

```powershell
python mcp-server/mcp_sse_server.py --port 8081
```

启动后监听 `http://127.0.0.1:8081/sse`，暴露两个工具：

| 工具 | 入参 | 说明 |
|---|---|---|
| `query_cloud_logs` | `logTopic`(必填) / `keyword` | 返回样例日志行与条数 |
| `get_cluster_events` | `namespace` | 返回 Pod 的 `OOMKilled` / `BackOff` 事件 |

## 与 Spring 侧的对接

MCP 客户端**默认关闭**：Server 不可用时，客户端会抛
`Client failed to initialize by explicit API call`，让整个应用启动失败。因此改为按 profile 启用：

`application.yml` 末尾的 `mcp` profile 文档段：

```yaml
spring:
  config:
    activate:
      on-profile: mcp
  ai:
    mcp:
      client:
        enabled: true
        type: ASYNC
        sse:
          connections:
            biz-logs:
              url: http://127.0.0.1:8081
              sse-endpoint: /sse
```

启动顺序：**先起本 Server，再带 `mcp` profile 启动应用**。

```powershell
python mcp-server/mcp_sse_server.py --port 8081
java -jar target/super-biz-agent-1.0-SNAPSHOT.jar --spring.profiles.active=local,mcp
```

`ChatService` 通过 `ToolCallbackProvider` 注入 MCP 工具，并在 `createReactAgent()` 里与本地
`@Tool` 方法一起装配：

```java
.methodTools(buildMethodToolsArray())  // 本地工具
.tools(getToolCallbacks())             // MCP 远程工具
```

## CI 守护

`check_mcp_server.py` 会拉起本 Server 并依次校验 `initialize` → `tools/list` → `tools/call`，
任何一步不符就以非 0 退出。CI 里由独立的 `mcp-protocol` job 执行（不需要 JDK、Milvus 或模型 Key）：

```powershell
python mcp-server/check_mcp_server.py
```

本地输出：

```
PASS handshake: /messages?sessionId=…
PASS initialize: serverInfo=biz-demo-mcp
PASS tools/list: ['get_cluster_events', 'query_cloud_logs']
PASS tools/call: namespace=production reasons=['BackOff', 'OOMKilled']
ALL PASS
```

> 说明：这个 job 只守护 **Server 侧协议**。Spring 侧的 MCP 客户端需要 Milvus 与模型 Key 才能启动，
> 因此那段链路仍是人工验证（步骤见下），不在 CI 中。

## 验证链路真的通了

1. 启动本 Server，再启动应用；
2. 应用日志出现 MCP 握手记录：

   ```
   i.m.client.LifecycleInitializer : Server response with Protocol: 2024-11-05,
     Capabilities: ... tools=ToolCapabilities[listChanged=false],
     Info: Implementation[name=biz-demo-mcp, version=1.0.0]
   ```

3. 调用 `/api/chat`，问一个**只有 MCP 工具能回答**的问题（集群事件在本地没有等价工具）：

   ```powershell
   $body = @{ Id="mcp-probe"; Question="查一下 production 命名空间的集群事件，有没有 OOMKilled 或 BackOff？" } | ConvertTo-Json -Compress
   Invoke-RestMethod -Uri "http://127.0.0.1:9900/api/chat" -Method Post `
     -ContentType "application/json; charset=utf-8" `
     -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
   ```

4. 本 Server 控制台出现调用记录，即为端到端打通：

   ```
   [mcp] <- tools/call id=78a38340-2
   [mcp] tools/call name=get_cluster_events arguments={"namespace": "production"}
   ```

## 边界说明

- 只实现了 SSE 传输与 `tools` 能力，**未实现** `prompts` / `resources` / `sampling` / `elicitation`；
  应用日志里的 `No sampling methods found` 就是客户端在探测这两项能力时打出的提示。
- 两个工具返回的是**固定样例数据**，用于演示与评测链路，不连接真实云服务。
- 生产环境应替换为真实数据源，并补充鉴权（MCP 的鉴权取决于具体 Server 实现）。
