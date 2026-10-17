"""CI check for the example MCP server.

Runs the server as a child process and asserts the three calls the Spring AI client makes:
initialize -> tools/list -> tools/call. Exits non-zero on any mismatch, so a broken
MCP server fails CI instead of only being noticed during a manual demo.

Deliberately avoids capturing the child's stdout/stderr (redirects to DEVNULL and asserts
over HTTP only): piped child stdio is restricted in some sandboxes, and the assertions do
not need the server log.

Usage:
    python mcp-server/check_mcp_server.py [--port 8091]
"""

from __future__ import annotations

import argparse
import json
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
SERVER = BASE_DIR / "mcp_sse_server.py"

EXPECTED_TOOLS = {"query_cloud_logs", "get_cluster_events"}


def wait_for_port(host: str, port: int, timeout: float = 15.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    base = f"http://{args.host}:{args.port}"
    process = subprocess.Popen(  # noqa: S603 - fixed args, local script
        [sys.executable, str(SERVER), "--port", str(args.port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    events: list[tuple[str, str]] = []
    try:
        if not wait_for_port(args.host, args.port):
            print("FAIL: server did not start listening")
            return 1

        def read_sse() -> None:
            try:
                with urllib.request.urlopen(base + "/sse", timeout=30) as response:
                    event = None
                    for raw in response:
                        line = raw.decode("utf-8").rstrip("\n")
                        if line.startswith("event:"):
                            event = line.split(":", 1)[1].strip()
                        elif line.startswith("data:"):
                            events.append((event, line.split(":", 1)[1].strip()))
            except Exception:  # noqa: BLE001 - stream ends when the check finishes
                pass

        thread = threading.Thread(target=read_sse, daemon=True)
        thread.start()

        deadline = time.time() + 10
        while time.time() < deadline and not any(e == "endpoint" for e, _ in events):
            time.sleep(0.05)
        endpoint = [d for e, d in events if e == "endpoint"]
        if not endpoint:
            print("FAIL: no endpoint event in the SSE handshake")
            return 1
        print(f"PASS handshake: {endpoint[0]}")
        url = base + endpoint[0]

        def post(payload: dict, expect_id: bool = True) -> dict | None:
            request = urllib.request.Request(
                url,
                data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                if response.status != 202:
                    raise AssertionError(f"POST returned {response.status}")
            if not expect_id:
                return None
            deadline = time.time() + 8
            while time.time() < deadline:
                for event, data in list(events):
                    if event == "message":
                        message = json.loads(data)
                        if message.get("id") == payload.get("id"):
                            return message
                time.sleep(0.05)
            raise AssertionError(f"no response for id={payload.get('id')}")

        init = post(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "ci-check", "version": "1"},
                },
            }
        )
        server_info = init["result"]["serverInfo"]["name"]
        print(f"PASS initialize: serverInfo={server_info}")

        post({"jsonrpc": "2.0", "method": "notifications/initialized"}, expect_id=False)

        listed = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in listed["result"]["tools"]}
        if not EXPECTED_TOOLS.issubset(names):
            print(f"FAIL: tools/list missing entries; got {sorted(names)}")
            return 1
        print(f"PASS tools/list: {sorted(names)}")

        called = post(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "get_cluster_events",
                    "arguments": {"namespace": "production"},
                },
            }
        )
        payload = json.loads(called["result"]["content"][0]["text"])
        reasons = {event["reason"] for event in payload["events"]}
        if "OOMKilled" not in reasons:
            print(f"FAIL: unexpected tool payload {payload}")
            return 1
        print(f"PASS tools/call: namespace={payload['namespace']} reasons={sorted(reasons)}")

        print("ALL PASS")
        return 0
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()


if __name__ == "__main__":
    raise SystemExit(main())
