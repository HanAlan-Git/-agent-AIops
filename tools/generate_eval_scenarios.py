"""Generates the offline AIOps evaluation corpus.

The corpus is deliberately deterministic: every scenario injects its own alerts and
logs through EvalScenarioContext, and the matching golden file holds the labels that
are never exposed to an agent. Run this script only when the corpus definition
changes, then review the JSON diff.

Usage:  python tools/generate_eval_scenarios.py
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCENARIO_DIR = ROOT / "src" / "main" / "resources" / "eval" / "scenarios"
GOLDEN_DIR = ROOT / "src" / "main" / "resources" / "eval" / "golden"

ALL_TOOLS = ["queryPrometheusAlerts", "queryLogs", "queryInternalDocs"]
OPTIONAL_TOOLS = ["getAvailableLogTopics", "getCurrentDateTime"]


def alert(name: str, description: str, active_at: str, duration: str = "18m") -> dict:
    return {
        "alertName": name,
        "description": description,
        "state": "firing",
        "activeAt": active_at,
        "duration": duration,
    }


def log(topic, timestamp, level, service, instance, message, metrics, terms=None) -> dict:
    entry = {
        "topic": topic,
        "timestamp": timestamp,
        "level": level,
        "service": service,
        "instance": instance,
        "message": message,
        "metrics": metrics,
    }
    if terms:
        entry["queryAnyTerms"] = terms
    return entry


def golden(scenario_id, diagnoses, required=None, optional=None, notes=None) -> dict:
    payload = {
        "scenarioId": scenario_id,
        "requiredTools": required if required is not None else ALL_TOOLS,
        "optionalTools": optional if optional is not None else OPTIONAL_TOOLS,
        "acceptableDiagnoses": diagnoses,
    }
    if notes:
        payload["notes"] = notes
    return payload


def diagnosis(dx_id, description, root_causes, evidence, actions) -> dict:
    return {
        "id": dx_id,
        "description": description,
        "rootCauseTermGroups": [list(group) for group in root_causes],
        "evidenceTermGroups": [list(group) for group in evidence],
        "actionTermGroups": [list(group) for group in actions],
    }


SCENARIOS: list[tuple[dict, dict]] = []


# ---------------------------------------------------------------- CPU (4)
SCENARIOS.append((
    {
        "scenarioId": "cpu-thread-pool-backlog-01",
        "alerts": [alert(
            "HighCPUUsage",
            "服务 payment-service CPU 使用率持续超过 80%，当前值 92%，实例 pod-payment-service-7d8f9c6b5-x2k4m",
            "2026-10-14T02:30:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 10:52:00", "WARN", "payment-service",
                "pod-payment-service-7d8f9c6b5-x2k4m",
                "CPU 92.0%，worker 线程池 active=200 max=200，任务队列 backlog=1840",
                {"cpu_usage": "92.0", "worker_active": "200", "worker_max": "200",
                 "queue_backlog": "1840", "load_average_1m": "3.82"},
                ["cpu"]),
            log("application-logs", "2026-10-14 10:53:00", "WARN", "payment-service",
                "pod-payment-service-7d8f9c6b5-x2k4m",
                "线程池拒绝策略触发，任务排队 1840，平均等待 2100ms",
                {"rejected": "37", "queue_backlog": "1840", "wait_ms": "2100"},
                ["线程池", "reject"]),
        ],
    },
    golden("cpu-thread-pool-backlog-01", [
        diagnosis(
            "thread-pool-backlog",
            "工作线程池饱和且任务队列积压导致 CPU 持续偏高。",
            [["线程池", "worker pool", "工作线程"], ["队列", "backlog", "积压", "排队"]],
            [["92", "92.0"], ["200", "active=200", "max=200"], ["1840", "backlog"]],
            [["线程池", "worker"], ["队列", "限流", "扩容", "消费者"]]),
        diagnosis(
            "request-backlog-capacity-shortage",
            "请求积压超过当前 worker 处理能力，需先限流再恢复消费能力。",
            [["请求", "任务", "回调"], ["积压", "排队", "backlog"]],
            [["1840", "backlog"], ["200", "active=200"]],
            [["限流", "扩容", "消费", "线程池"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "cpu-runaway-thread-01",
        "alerts": [alert(
            "HighCPUUsage",
            "服务 search-service CPU 使用率 96%，实例 pod-search-service-6f5c4d3b2-q8w7e",
            "2026-10-14T03:05:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 11:04:00", "WARN", "search-service",
                "pod-search-service-6f5c4d3b2-q8w7e",
                "CPU 96%，单个线程 CPU 占用 78%，线程名 es-index-refresh-3 空转",
                {"cpu_usage": "96", "top_thread": "es-index-refresh-3",
                 "thread_cpu": "78", "thread_state": "RUNNABLE"},
                ["cpu", "thread"]),
            log("application-logs", "2026-10-14 11:03:00", "ERROR", "search-service",
                "pod-search-service-6f5c4d3b2-q8w7e",
                "索引刷新循环未休眠：while(true) 中缺少退避，重试间隔 0ms",
                {"error_type": "BusySpinLoop", "retry_interval_ms": "0", "loop_count": "1283902"},
                ["cpu", "循环", "loop"]),
        ],
    },
    golden("cpu-runaway-thread-01", [
        diagnosis(
            "busy-spin-loop",
            "索引刷新线程缺少退避导致忙等待自旋，单线程打满 CPU。",
            [["忙等待", "空转", "自旋", "busy spin"], ["索引", "线程", "循环"]],
            [["96"], ["es-index-refresh-3"], ["0ms", "1283902", "缺少退避"]],
            [["退避", "休眠", "sleep", "限流"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "cpu-gc-pause-storm-01",
        "alerts": [alert(
            "HighCPUUsage",
            "服务 report-service CPU 使用率 88%，伴随接口超时",
            "2026-10-14T03:40:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 11:39:00", "WARN", "report-service",
                "pod-report-service-3a2b1c0d9-e4r5t",
                "CPU 88%，其中 GC 线程占用 61%，Young GC 频率 42 次/分钟",
                {"cpu_usage": "88", "gc_thread_cpu": "61", "young_gc_per_min": "42",
                 "heap_used": "3.4GB", "heap_max": "4GB"},
                ["cpu", "gc"]),
            log("application-logs", "2026-10-14 11:38:00", "WARN", "report-service",
                "pod-report-service-3a2b1c0d9-e4r5t",
                "存活对象过多导致晋升失败：promotion failed，每次 GC 后 Old Gen 占 87%",
                {"gc_cause": "promotion failed", "old_gen": "87%", "full_gc_count": "9"},
                ["gc", "promotion"]),
        ],
    },
    golden("cpu-gc-pause-storm-01", [
        diagnosis(
            "gc-pause-storm",
            "对象晋升失败引发 GC 风暴，GC 线程争抢 CPU 导致接口超时。",
            [["gc", "垃圾回收", "回收"], ["晋升失败", "promotion failed", "old gen", "老年代"]],
            [["88"], ["GC", "gc"], ["87", "promotion failed"]],
            [["gc 参数", "堆", "内存", "扩容", "-Xmx"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "cpu-noisy-neighbor-node-01",
        "alerts": [alert(
            "HighCPUUsage",
            "节点 node-worker-01 上多个 Pod CPU 同时升高",
            "2026-10-14T04:10:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 12:09:00", "WARN", "node-worker-01",
                "node-worker-01",
                "节点 load_average_1m=24.7，vCPU=8，CPU steal 时间占 31%",
                {"load_average_1m": "24.7", "vcpu": "8", "cpu_steal": "31%"},
                ["load", "steal", "节点"]),
            log("system-events", "2026-10-14 12:08:00", "WARN", "kubernetes", "node-worker-01",
                "外部负载测试容器 stress-ng 占用 6 个核，非业务 Pod",
                {"pod": "stress-ng-8f7d", "namespace": "chaos-testing", "cores": "6"},
                ["stress", "chaos", "节点"]),
        ],
    },
    golden("cpu-noisy-neighbor-node-01", [
        diagnosis(
            "noisy-neighbor",
            "同节点上的压测容器抢占 CPU，业务 Pod 受邻居干扰而非自身故障。",
            [["邻居", "noisy", "压测", "stress"], ["节点", "node", "抢占", "steal"]],
            [["24.7", "load"], ["31%", "steal"], ["stress-ng", "chaos-testing"]],
            [["迁移", "驱逐", "evict", "隔离", "节点"]]),
    ]),
))

# ---------------------------------------------------------------- Memory (4)
SCENARIOS.append((
    {
        "scenarioId": "memory-heap-leak-01",
        "alerts": [alert(
            "HighMemoryUsage",
            "服务 order-service JVM 堆内存使用率持续超过 85%，当前 91%，实例 pod-order-service-5c7d8e9f1-m3n2p",
            "2026-10-14T02:45:00Z", "15m")],
        "logs": [
            log("system-metrics", "2026-10-14 10:58:00", "WARN", "order-service",
                "pod-order-service-5c7d8e9f1-m3n2p",
                "JVM 堆内存 3.8GB/4GB，Old Gen 89%，过去 10 分钟发生 15 次 Full GC",
                {"heap_used": "3.8GB", "heap_max": "4GB", "old_gen": "89%", "full_gc_count": "15"},
                ["memory", "heap"]),
            log("application-logs", "2026-10-14 10:57:00", "ERROR", "order-service",
                "pod-order-service-5c7d8e9f1-m3n2p",
                "订单导出任务持续缓存大对象，缓存条目未释放，堆内对象数量持续增长",
                {"cache_entries": "128000", "suspected_leak": "true"},
                ["memory", "cache", "导出"]),
            log("system-events", "2026-10-14 10:56:00", "WARN", "kubernetes", "node-worker-02",
                "Pod 内存接近限制，尚未发生 OOMKilled",
                {"memory_limit": "4GB", "memory_used": "3.8GB"},
                ["memory", "pod"]),
        ],
    },
    golden("memory-heap-leak-01", [
        diagnosis(
            "unbounded-export-cache-leak",
            "导出任务把结果缓存在无上限的本地缓存中，对象无法回收导致堆内存泄漏。",
            [["缓存", "cache", "对象"], ["未释放", "泄漏", "leak", "无上限", "不断增长"]],
            [["3.8GB", "91", "89%"], ["15", "full gc"], ["128000", "cache_entries"]],
            [["清理", "驱逐", "上限", "限额", "导出"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "memory-metaspace-01",
        "alerts": [alert(
            "HighMemoryUsage",
            "服务 gateway-service 容器内存使用率 93%，但 JVM 堆仅 45%",
            "2026-10-14T05:00:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 12:59:00", "WARN", "gateway-service",
                "pod-gateway-service-1a2b3c4d5-f6g7h",
                "容器内存 3.7GB/4GB，JVM 堆仅 1.8GB，Metaspace 使用 780MB 且持续增长",
                {"container_mem": "3.7GB", "heap_used": "1.8GB", "metaspace": "780MB",
                 "loaded_classes": "148932"},
                ["memory", "metaspace"]),
            log("application-logs", "2026-10-14 12:58:00", "WARN", "gateway-service",
                "pod-gateway-service-1a2b3c4d5-f6g7h",
                "动态代理类生成数量异常：CGLIB 每秒新增 120 个类，类加载器未复用",
                {"gclib_per_sec": "120", "classloader_count": "86"},
                ["metaspace", "class", "代理"]),
        ],
    },
    golden("memory-metaspace-01", [
        diagnosis(
            "metaspace-classloader-leak",
            "动态代理类反复生成且类加载器未复用，Metaspace 持续增长导致容器内存超限。",
            [["metaspace", "元空间"], ["类加载器", "classloader", "类", "代理", "cglib"]],
            [["3.7GB", "780MB"], ["148932", "120"], ["heap", "堆"]],
            [["复用", "缓存", "限制", "maxmetaspace", "类加载器"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "memory-oomkill-restartloop-01",
        "alerts": [alert(
            "ServiceUnavailable",
            "服务 inventory-service 副本频繁重启",
            "2026-10-14T05:30:00Z")],
        "logs": [
            log("system-events", "2026-10-14 13:29:00", "ERROR", "kernel", "node-worker-03",
                "OOM Killer 触发：容器 inventory-service 内存 3.98GB 超过限制 4GB，退出码 137",
                {"reason": "OOMKilled", "exit_code": "137", "restart_count": "6",
                 "memory_used": "3.98GB", "memory_limit": "4GB"},
                ["oom", "restart", "kill"]),
            log("application-logs", "2026-10-14 13:28:00", "ERROR", "inventory-service",
                "pod-inventory-service-9z8y7x6w5-v4u3t",
                "库存快照接口一次性加载全量 SKU 到内存：SELECT * 无分页，单次 2.4GB",
                {"sql_rows": "18400000", "heap_alloc": "2.4GB", "pagination": "none"},
                ["oom", "内存", "全量"]),
        ],
    },
    golden("memory-oomkill-restartloop-01", [
        diagnosis(
            "unpaged-full-load-oom",
            "全量加载无分页查询把堆内存推过容器限制，触发 OOMKilled 与重启循环。",
            [["分页", "全量", "一次性加载", "select *"], ["内存", "堆", "oom"]],
            [["137", "OOMKilled"], ["3.98GB", "4GB"], ["18400000", "2.4GB"]],
            [["分页", "流式", "分批", "限制", "内存"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "memory-cache-hit-bloat-01",
        "alerts": [alert(
            "HighMemoryUsage",
            "服务 cart-service 内存使用率 87% 且持续上升",
            "2026-10-14T06:00:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 13:59:00", "WARN", "cart-service",
                "pod-cart-service-2b3c4d5e6-g7h8i",
                "本地缓存 Caffeine 命中率 99%，条目数 4,200,000，未设置 maximumSize",
                {"cache_hit_rate": "99%", "cache_entries": "4200000", "maximum_size": "none"},
                ["memory", "cache"]),
            log("application-logs", "2026-10-14 13:57:00", "WARN", "cart-service",
                "pod-cart-service-2b3c4d5e6-g7h8i",
                "缓存 key 含用户 sessionId，session 失效后条目不会过期",
                {"expire_after_write": "none", "key_pattern": "sessionId:*"},
                ["cache", "session", "过期"]),
        ],
    },
    golden("memory-cache-hit-bloat-01", [
        diagnosis(
            "unbounded-local-cache",
            "本地缓存缺少容量上限与过期策略，session 级 key 长期累积导致内存持续上升。",
            [["缓存", "caffeine"], ["上限", "maximumsize", "过期", "expire", "无界"]],
            [["87"], ["4200000", "4,200,000"], ["99%", "session"]],
            [["上限", "过期", "淘汰", "缓存", "session"]]),
    ]),
))

# ---------------------------------------------------------------- Disk (4)
SCENARIOS.append((
    {
        "scenarioId": "disk-log-growth-01",
        "alerts": [alert(
            "HighDiskUsage",
            "节点 node-worker-01 的 /data 分区使用率 94%",
            "2026-10-14T06:30:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 14:29:00", "WARN", "node-worker-01", "node-worker-01",
                "磁盘 /data 使用率 94%，可用 6GB/100GB，最大目录 /data/logs 占 71GB",
                {"disk_usage": "94%", "disk_available": "6GB", "largest_dir": "/data/logs"},
                ["disk", "磁盘"]),
            log("application-logs", "2026-10-14 14:28:00", "ERROR", "log-agent", "node-worker-01",
                "应用日志未配置轮转：单个文件 app.log 已 41GB，rollingPolicy 缺失",
                {"file": "/data/logs/app.log", "file_size": "41GB", "rolling": "none"},
                ["log", "日志", "轮转"]),
        ],
    },
    golden("disk-log-growth-01", [
        diagnosis(
            "missing-log-rotation",
            "应用日志缺少轮转与清理策略，单文件持续增长占满数据盘。",
            [["日志", "log", "轮转", "rolling"], ["磁盘", "disk", "占满", "增长"]],
            [["94%", "6GB"], ["41GB", "app.log"], ["/data/logs", "71GB"]],
            [["轮转", "清理", "归档", "rollingPolicy", "压缩"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "disk-inode-exhaustion-01",
        "alerts": [alert(
            "HighDiskUsage",
            "服务 file-service 写入失败，节点磁盘使用率仅 42%",
            "2026-10-14T07:00:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 14:59:00", "ERROR", "node-worker-04", "node-worker-04",
                "df -i 显示 inode 使用率 100%，可用 inode 0，磁盘容量使用率仅 42%",
                {"inode_usage": "100%", "inode_available": "0", "disk_usage": "42%"},
                ["inode", "磁盘"]),
            log("application-logs", "2026-10-14 14:58:00", "ERROR", "file-service",
                "pod-file-service-7h6g5f4e3-d2s1a",
                "写入失败：No space left on device；/tmp 下存在 230 万个临时小文件",
                {"error": "No space left on device", "tmp_files": "2300000",
                 "file_pattern": "upload-*.part"},
                ["inode", "临时文件", "space"]),
        ],
    },
    golden("disk-inode-exhaustion-01", [
        diagnosis(
            "inode-exhaustion",
            "海量临时小文件耗尽 inode，容量充足但无法创建新文件。",
            [["inode"], ["临时文件", "小文件", "tmp", "残留"]],
            [["100%"], ["2300000", "2,300,000"], ["42%", "No space left"]],
            [["清理", "临时文件", "删除", "定时任务", "inode"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "disk-milvus-compaction-01",
        "alerts": [alert(
            "HighDiskUsage",
            "向量库节点 milvus-standalone 数据盘使用率 91% 且写入延迟升高",
            "2026-10-14T07:30:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 15:29:00", "WARN", "milvus-standalone", "node-worker-05",
                "Milvus 数据盘使用率 91%，compaction 任务积压 37 个未完成",
                {"disk_usage": "91%", "pending_compactions": "37", "deleted_ratio": "48%"},
                ["disk", "milvus", "compaction"]),
            log("application-logs", "2026-10-14 15:27:00", "WARN", "milvus-standalone",
                "node-worker-05",
                "大量删除标记未回收：deleted entities 占 48%，segment 无法合并",
                {"deleted_ratio": "48%", "segment_count": "412"},
                ["milvus", "segment", "删除"]),
        ],
    },
    golden("disk-milvus-compaction-01", [
        diagnosis(
            "compaction-backlog-disk-growth",
            "删除标记未回收导致 compaction 积压，segment 膨胀占满数据盘。",
            [["compaction", "合并", "回收"], ["删除", "deleted", "标记", "segment"]],
            [["91%"], ["37"], ["48%", "412"]],
            [["compaction", "回收", "清理", "扩容", "分层存储"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "disk-image-cache-01",
        "alerts": [alert(
            "HighDiskUsage",
            "节点 node-worker-06 系统盘使用率 96%，Pod 无法调度",
            "2026-10-14T08:00:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 15:59:00", "WARN", "node-worker-06", "node-worker-06",
                "系统盘使用率 96%，/var/lib/docker 占 78GB，其中镜像层 62GB",
                {"disk_usage": "96%", "docker_dir": "78GB", "image_layers": "62GB"},
                ["disk", "docker"]),
            log("system-events", "2026-10-14 15:58:00", "WARN", "kubernetes", "node-worker-06",
                "kubelet 触发镜像回收失败：imageGCHighThresholdPercent 未配置",
                {"event": "ImageGCFailed", "threshold": "none"},
                ["image", "gc", "节点"]),
        ],
    },
    golden("disk-image-cache-01", [
        diagnosis(
            "image-cache-disk-pressure",
            "节点镜像缓存未配置回收阈值，历史镜像层占满系统盘并阻塞调度。",
            [["镜像", "image", "缓存"], ["回收", "gc", "阈值", "清理"]],
            [["96%"], ["62GB", "78GB"], ["ImageGCFailed", "threshold"]],
            [["回收", "gc", "阈值", "清理", "镜像"]]),
    ]),
))

# ---------------------------------------------------------------- Service unavailable (4)
SCENARIOS.append((
    {
        "scenarioId": "svc-db-pool-exhausted-01",
        "alerts": [alert(
            "ServiceUnavailable",
            "服务 order-service 接口 5xx 比例 62%",
            "2026-10-14T08:30:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 16:29:00", "ERROR", "order-service",
                "pod-order-service-5c7d8e9f1-m3n2p",
                "数据库连接池耗尽：active 50/50，waiting 23，获取连接超时 30000ms",
                {"pool_active": "50", "pool_max": "50", "waiting_threads": "23"},
                ["pool", "连接池", "error"]),
            log("database-slow-query", "2026-10-14 16:28:00", "WARN", "mysql", "mysql-primary-01",
                "慢查询持连接 42s：SELECT * FROM orders WHERE user_id=? AND status IN (...) 未走索引",
                {"query_time_sec": "42", "rows_examined": "1245678", "index_used": "none"},
                ["慢查询", "sql", "index"]),
        ],
    },
    golden("svc-db-pool-exhausted-01", [
        diagnosis(
            "slow-sql-holding-connections",
            "未走索引的慢查询长期占用连接，把连接池耗尽并放大为 5xx。",
            [["索引", "index", "全表扫描", "慢查询"], ["连接池", "pool", "连接"]],
            [["50/50", "active 50"], ["23"], ["30000", "42"]],
            [["索引", "sql 优化", "连接池", "超时", "限流"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "svc-downstream-timeout-01",
        "alerts": [alert(
            "ServiceUnavailable",
            "服务 payment-service 调用下游超时率 71%",
            "2026-10-14T09:00:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 16:59:00", "ERROR", "payment-service",
                "pod-payment-service-7d8f9c6b5-x2k4m",
                "调用风控服务 timeout 3000ms，熔断器状态 CLOSED，无降级逻辑",
                {"downstream": "risk-service", "timeout_ms": "3000", "circuit_state": "CLOSED"},
                ["downstream", "timeout", "熔断"]),
            log("application-logs", "2026-10-14 16:58:00", "ERROR", "risk-service",
                "pod-risk-service-4d3c2b1a0-z9y8x",
                "风控服务 GC 停顿 8s，请求排队 1200",
                {"gc_pause_ms": "8000", "queue": "1200"},
                ["risk", "gc", "队列"]),
        ],
    },
    golden("svc-downstream-timeout-01", [
        diagnosis(
            "downstream-timeout-no-circuit-breaker",
            "下游服务停顿而调用方缺少熔断与降级，超时被放大成全站不可用。",
            [["熔断", "降级", "circuit"], ["下游", "依赖", "downstream"]],
            [["3000", "timeout"], ["71%", "CLOSED"], ["1200", "8000"]],
            [["熔断", "降级", "超时", "重试", "隔离"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "svc-pod-crashloop-01",
        "alerts": [alert(
            "ServiceUnavailable",
            "服务 notification-service 可用副本 1/5",
            "2026-10-14T09:30:00Z")],
        "logs": [
            log("system-events", "2026-10-14 17:29:00", "ERROR", "kubernetes", "node-worker-07",
                "Pod 处于 CrashLoopBackOff：就绪探针失败 12 次，重启间隔指数退避",
                {"reason": "CrashLoopBackOff", "restarts": "12", "readiness": "failed"},
                ["crash", "restart", "探针"]),
            log("application-logs", "2026-10-14 17:28:00", "FATAL", "notification-service",
                "pod-notification-service-8e7d6c5b4-a3s2d",
                "启动时连接配置中心失败，端口 8848 连接被拒绝，进程主动退出",
                {"exit_code": "1", "config_center": "nacos:8848", "error": "Connection refused"},
                ["启动", "配置", "nacos"]),
        ],
    },
    golden("svc-pod-crashloop-01", [
        diagnosis(
            "config-center-unreachable-crashloop",
            "配置中心不可达导致进程启动即退出，就绪探针失败形成崩溃重启循环。",
            [["配置中心", "nacos", "配置"], ["启动", "连接", "不可达", "拒绝"]],
            [["CrashLoopBackOff"], ["12"], ["8848", "Connection refused"]],
            [["配置中心", "探针", "启动", "回滚", "依赖"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "svc-mq-backlog-01",
        "alerts": [alert(
            "ServiceUnavailable",
            "服务 billing-service 出账延迟超过 2 小时",
            "2026-10-14T10:00:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 17:59:00", "WARN", "billing-service",
                "pod-billing-service-6a5b4c3d2-e1f0g",
                "消息队列积压 1,582,300 条，消费速率从 800/s 降到 35/s，消费者数 3",
                {"pending_messages": "1582300", "consume_rate": "35", "consumers": "3"},
                ["mq", "积压", "queue"]),
            log("application-logs", "2026-10-14 17:57:00", "ERROR", "billing-service",
                "pod-billing-service-6a5b4c3d2-e1f0g",
                "消费线程池被单条大消息阻塞：单条处理耗时 28s，线程池大小 3",
                {"thread_pool": "3", "single_msg_ms": "28000", "blocked": "true"},
                ["消费", "线程池", "阻塞"]),
        ],
    },
    golden("svc-mq-backlog-01", [
        diagnosis(
            "blocked-consumer-thread-pool",
            "单条大消息长时间阻塞消费线程池，消费速率骤降导致队列积压。",
            [["消费", "消费者", "线程池"], ["阻塞", "积压", "排队", "降速"]],
            [["1582300", "1,582,300"], ["35"], ["28000", "3"]],
            [["扩容", "并发", "超时", "批量", "消费"]]),
    ]),
))

# ---------------------------------------------------------------- Slow response (4)
SCENARIOS.append((
    {
        "scenarioId": "slow-index-missing-01",
        "alerts": [alert(
            "SlowResponse",
            "服务 user-service P99 响应时间 4.2s，超过阈值 3s",
            "2026-10-14T10:30:00Z")],
        "logs": [
            log("database-slow-query", "2026-10-14 18:29:00", "WARN", "mysql", "mysql-primary-01",
                "慢查询 3.2s：SELECT * FROM users u LEFT JOIN user_profiles p ON u.id=p.user_id 全表扫描",
                {"query_time_sec": "3.2", "rows_examined": "856234", "index_used": "none"},
                ["慢查询", "sql", "index"]),
            log("application-logs", "2026-10-14 18:28:00", "WARN", "user-service",
                "pod-user-service-8e9f0a1b2-k5j6h",
                "接口 /api/v1/users/profile 耗时 4200ms，其中 DB 3800ms，缓存未命中",
                {"uri": "/api/v1/users/profile", "response_time_ms": "4200", "db_time_ms": "3800",
                 "cache_hit": "false"},
                ["slow", "响应"]),
        ],
    },
    golden("slow-index-missing-01", [
        diagnosis(
            "missing-join-index",
            "关联查询缺少索引导致全表扫描，数据库耗时占据接口响应绝大部分。",
            [["索引", "index", "全表扫描"], ["慢查询", "join", "关联"]],
            [["3.2"], ["856234"], ["4200", "3800"]],
            [["索引", "sql", "缓存", "优化", "分页"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "slow-cache-stampede-01",
        "alerts": [alert(
            "SlowResponse",
            "服务 product-service 响应时间从 120ms 突增到 2.8s",
            "2026-10-14T11:00:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 18:59:00", "WARN", "product-service",
                "pod-product-service-2c3d4e5f6-g7h8i",
                "热点 key 过期瞬间 3400 个请求同时回源，缓存命中率从 98% 跌到 12%",
                {"hot_key": "product:detail:10086", "concurrent_load": "3400",
                 "cache_hit_rate": "12%"},
                ["cache", "缓存", "穿透"]),
            log("database-slow-query", "2026-10-14 18:58:00", "WARN", "mysql", "mysql-primary-01",
                "同一 SQL 并发 3400 次，DB CPU 97%，查询排队",
                {"qps": "3400", "db_cpu": "97%", "table": "product"},
                ["慢查询", "qps", "并发"]),
        ],
    },
    golden("slow-cache-stampede-01", [
        diagnosis(
            "cache-stampede-hot-key",
            "热点 key 同时过期造成缓存击穿，请求集体回源压垮数据库。",
            [["缓存", "cache"], ["击穿", "穿透", "stampede", "同时过期", "雪崩"]],
            [["3400", "3,400"], ["12%", "98%"], ["97%", "10086"]],
            [["互斥", "锁", "逻辑过期", "预热", "限流"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "slow-thread-pool-queue-01",
        "alerts": [alert(
            "SlowResponse",
            "服务 api-gateway P95 响应时间 1.9s",
            "2026-10-14T11:30:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 19:29:00", "WARN", "api-gateway",
                "pod-api-gateway-9a8b7c6d5-e4f3g",
                "Tomcat 工作线程全部占用：busy 200/200，accept queue 137，请求排队 1900ms",
                {"tomcat_busy": "200", "tomcat_max": "200", "accept_queue": "137"},
                ["线程", "队列", "排队"]),
            log("application-logs", "2026-10-14 19:27:00", "WARN", "api-gateway",
                "pod-api-gateway-9a8b7c6d5-e4f3g",
                "下游调用平均耗时 1800ms 且同步阻塞，单请求占用线程 1.8s",
                {"downstream_avg_ms": "1800", "blocking": "true"},
                ["下游", "阻塞", "耗时"]),
        ],
    },
    golden("slow-thread-pool-queue-01", [
        diagnosis(
            "blocking-calls-saturate-worker-threads",
            "同步阻塞的下游调用占用全部工作线程，请求在队列中排队形成延迟。",
            [["线程", "thread", "队列", "排队"], ["阻塞", "同步", "下游"]],
            [["200", "busy 200"], ["137"], ["1800", "1900"]],
            [["异步", "超时", "线程池", "扩容", "隔离"]]),
    ]),
))

SCENARIOS.append((
    {
        "scenarioId": "slow-gc-pause-latency-01",
        "alerts": [alert(
            "SlowResponse",
            "服务 recommend-service 接口毛刺 P99 5.6s，平均仅 180ms",
            "2026-10-14T12:00:00Z")],
        "logs": [
            log("system-metrics", "2026-10-14 19:59:00", "WARN", "recommend-service",
                "pod-recommend-service-5f4e3d2c1-b0a987",
                "每 3 分钟出现一次 4-6s 停顿，与 Full GC 时间点完全重合",
                {"full_gc_interval_min": "3", "pause_ms": "5600", "old_gen": "82%"},
                ["gc", "停顿", "pause"]),
            log("application-logs", "2026-10-14 19:58:00", "WARN", "recommend-service",
                "pod-recommend-service-5f4e3d2c1-b0a987",
                "堆内存 3.6GB/4GB，存活对象多，Full GC 后仅回收 6%",
                {"heap_used": "3.6GB", "heap_max": "4GB", "reclaimed": "6%"},
                ["gc", "堆", "回收"]),
        ],
    },
    golden("slow-gc-pause-latency-01", [
        diagnosis(
            "full-gc-pause-spikes",
            "存活对象过多导致 Full GC 停顿周期性拉高尾延迟，平均值掩盖了毛刺。",
            [["gc", "full gc", "垃圾回收"], ["停顿", "pause", "毛刺", "尾延迟"]],
            [["5600", "4-6s"], ["82%", "3.6GB"], ["6%", "3"]],
            [["gc", "堆", "参数", "扩容", "-Xmx"]]),
    ]),
))

# ---------------------------------------------------------------- Negative samples (2)
SCENARIOS.append((
    {
        "scenarioId": "neg-insufficient-evidence-01",
        "alerts": [alert(
            "HighCPUUsage",
            "服务 legacy-service CPU 使用率 71%，未超过 80% 阈值但持续 3 小时",
            "2026-10-14T12:30:00Z", "3h")],
        "logs": [
            log("system-metrics", "2026-10-14 20:29:00", "WARN", "legacy-service",
                "pod-legacy-service-1f2e3d4c5-b6a789",
                "CPU 稳定在 71%，无排队、无 GC 异常、无错误日志",
                {"cpu_usage": "71", "queue_backlog": "0", "gc_count": "2"},
                ["cpu"]),
            log("application-logs", "2026-10-14 20:28:00", "INFO", "legacy-service",
                "pod-legacy-service-1f2e3d4c5-b6a789",
                "日志中未发现异常，请求量与上周同期持平",
                {"error_count": "0", "qps_delta": "0%"},
                ["应用", "日志"]),
        ],
    },
    golden("neg-insufficient-evidence-01", [
        diagnosis(
            "honest-limitation",
            "证据不足以定位根因：CPU 未达阈值且无排队、GC、错误日志佐证；应如实说明无法完成定位并列出下一步采集项。",
            [["证据不足", "无法定位", "无法完成", "不足以判断", "insufficient"],
             ["下一步", "建议补充", "需要采集", "待确认"]],
            [["71", "71%"], ["0", "无异常", "未发现"]],
            [["采集", "补充", "监控", "对比", "验证"]]),
    ], notes="Negative sample: the agent must admit the evidence is insufficient instead of inventing a root cause."),
))

SCENARIOS.append((
    {
        "scenarioId": "neg-tool-param-invalid-01",
        "alerts": [alert(
            "SlowResponse",
            "服务 media-service 上传接口偶发超时",
            "2026-10-14T13:00:00Z")],
        "logs": [
            log("application-logs", "2026-10-14 20:59:00", "WARN", "media-service",
                "pod-media-service-3e2d1c0b9-a8f765",
                "上传接口耗时 2400ms，其中对象存储写入 2100ms",
                {"response_time_ms": "2400", "oss_time_ms": "2100"},
                ["slow", "上传"]),
        ],
    },
    golden("neg-tool-param-invalid-01", [
        diagnosis(
            "invalid-parameter-handling",
            "工具参数非法时必须在入口被拒绝并返回可解释错误，而不是用默认值静默返回无关数据；报告需说明参数被拒这一事实。",
            [["参数", "非法", "不合法", "invalid"], ["拒绝", "拦截", "不支持", "未生效"]],
            [["logTopic", "region", "参数"], ["拒绝", "非法", "不支持"]],
            [["校验", "重试", "修正", "参数"]]),
    ], notes="Negative sample: exercises ToolGuard parameter validation instead of a silent fallback."),
))

# ---------------------------------------------------------------- write
def main() -> None:
    SCENARIO_DIR.mkdir(parents=True, exist_ok=True)
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)

    seen: set[str] = set()
    for scenario, golden_doc in SCENARIOS:
        scenario_id = scenario["scenarioId"]
        if scenario_id in seen:
            raise SystemExit(f"duplicate scenarioId: {scenario_id}")
        if golden_doc["scenarioId"] != scenario_id:
            raise SystemExit(f"golden id mismatch: {scenario_id}")
        seen.add(scenario_id)
        (SCENARIO_DIR / f"{scenario_id}.json").write_text(
            json.dumps(scenario, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (GOLDEN_DIR / f"{scenario_id}.json").write_text(
            json.dumps(golden_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"scenarios written: {len(seen)}")
    for scenario_id in sorted(seen):
        print(f"  - {scenario_id}")


if __name__ == "__main__":
    main()
