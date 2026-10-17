"""A minimal MCP server over SSE, standard library only.

This exists to give the Spring AI MCP client in this repo something real to talk to. The
client (spring-ai-starter-mcp-client-webflux) is configured under spring.ai.mcp.client in
application.yml; keeping the server dependency-free means the demo runs anywhere Python 3.9+
is available, with no package install step.

Transport: MCP 2024-11-05 SSE
  GET  /sse                 text/event-stream; first event announces the POST endpoint
  POST /messages?sessionId  JSON-RPC request; the reply is pushed back over the stream
Methods: initialize, notifications/initialized, tools/list, tools/call, ping

Run:
    python mcp-server/mcp_sse_server.py --port 8081

Then start the app with spring.ai.mcp.client.enabled=true (see the README section).
"""

from __future__ import annotations

import argparse
import json
import queue
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# session id -> queue of payloads waiting to be flushed to that SSE client
SESSIONS: dict[str, queue.Queue] = {}
SESSIONS_LOCK = threading.Lock()

TOOLS = [
    {
        "name": "query_cloud_logs",
        "description": (
            "查询云日志（示例 MCP 工具）：按日志主题与关键字检索最近一小时日志，"
            "返回匹配条数与样例日志行。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "logTopic": {"type": "string", "description": "日志主题，例如 application-logs"},
                "keyword": {"type": "string", "description": "检索关键字，例如 timeout"},
            },
            "required": ["logTopic"],
        },
    },
    {
        "name": "get_cluster_events",
        "description": "查询集群最近事件（示例 MCP 工具），用于确认 Pod 重启、驱逐等异常。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "namespace": {"type": "string", "description": "命名空间，例如 production"},
            },
            "required": [],
        },
    },
]


def log(message: str) -> None:
    print(f"[mcp] {message}", flush=True)


def call_tool(name: str, arguments: dict) -> dict:
    """Returns an MCP tool result. Deterministic output keeps demos repeatable."""
    log(f"tools/call name={name} arguments={json.dumps(arguments, ensure_ascii=False)}")

    if name == "query_cloud_logs":
        topic = arguments.get("logTopic", "")
        keyword = arguments.get("keyword", "")
        lines = [
            f"2026-10-17T10:02:11Z {topic} INFO  received request keyword={keyword or '-'}",
            f"2026-10-17T10:02:19Z {topic} WARN  response time 1840ms exceeds threshold 800ms",
            f"2026-10-17T10:02:24Z {topic} ERROR upstream timeout after 3000ms",
        ]
        return {
            "content": [
                {
                    "type": "text",
                    "text": json.dumps(
                        {"total": len(lines), "logTopic": topic, "samples": lines},
                        ensure_ascii=False,
                    ),
                }
            ],
            "isError": False,
        }

    if name == "get_cluster_events":
        namespace = arguments.get("namespace", "default")
        return {
            "content": [
                {
                    "type": "text",
                    "text": json.dumps(
                        {
                            "namespace": namespace,
                            "events": [
                                {"reason": "OOMKilled", "count": 2, "object": "pod/order-service-7d9f"},
                                {"reason": "BackOff", "count": 5, "object": "pod/order-service-7d9f"},
                            ],
                        },
                        ensure_ascii=False,
                    ),
                }
            ],
            "isError": False,
        }

    return {"content": [{"type": "text", "text": f"unknown tool: {name}"}], "isError": True}


class McpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        log(fmt % args)

    # ---------- SSE stream ----------
    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        if urlparse(self.path).path != "/sse":
            self.send_error(404, "not found")
            return

        session_id = uuid.uuid4().hex
        outbox: queue.Queue = queue.Queue()
        with SESSIONS_LOCK:
            SESSIONS[session_id] = outbox
        log(f"SSE connected session={session_id}")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        try:
            # The client must learn where to POST: this is the SSE handshake.
            self._write_sse("endpoint", f"/messages?sessionId={session_id}")
            while True:
                try:
                    payload = outbox.get(timeout=15)
                except queue.Empty:
                    continue  # idle; the client reconnects on its own timeout
                self._write_sse("message", payload)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            # Normal shutdown: the agent closed the stream after its turn finished.
            log(f"SSE disconnected session={session_id}")
        finally:
            with SESSIONS_LOCK:
                SESSIONS.pop(session_id, None)

    def _write_sse(self, event: str, data: str) -> None:
        self.wfile.write(f"event: {event}\ndata: {data}\n\n".encode("utf-8"))
        self.wfile.flush()

    # ---------- JSON-RPC ----------
    def do_POST(self) -> None:  # noqa: N802 - http.server naming
        parsed = urlparse(self.path)
        if parsed.path != "/messages":
            self.send_error(404, "not found")
            return

        session_id = (parse_qs(parsed.query).get("sessionId") or [""])[0]
        with SESSIONS_LOCK:
            outbox = SESSIONS.get(session_id)
        if outbox is None:
            self.send_error(400, "unknown session")
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            request = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self.send_error(400, "invalid json")
            return

        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()

        method = request.get("method")
        request_id = request.get("id")
        log(f"<- {method} id={request_id}")

        # Notifications carry no id and must not be answered.
        if method == "notifications/initialized":
            return

        if method == "initialize":
            params = request.get("params") or {}
            result = {
                # Echo the client's version so either 2024-11-05 or a newer handshake works.
                "protocolVersion": params.get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "biz-demo-mcp", "version": "1.0.0"},
            }
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            params = request.get("params") or {}
            result = call_tool(params.get("name", ""), params.get("arguments") or {})
        else:
            outbox.put(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "error": {"code": -32601, "message": f"method not found: {method}"},
                    }
                )
            )
            return

        outbox.put(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}))


def main() -> None:
    parser = argparse.ArgumentParser(description="Minimal MCP server over SSE")
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), McpHandler)
    server.daemon_threads = True
    log(f"listening on http://{args.host}:{args.port}/sse")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("shutting down")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
