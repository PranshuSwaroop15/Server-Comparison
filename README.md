# Java Server Architecture Benchmark
**Single-threaded vs Multi-threaded vs Event-loop (NIO)** under sustained load.  
Target load: **~60,000 requests/min** (≈ **1,000 RPS**) using **Apache JMeter**.

This repo contains three Java servers and a JMeter plan to benchmark them on identical endpoints.

---

## Contents

```
├─ build.gradle / settings.gradle # single Gradle project (Groovy DSL)
├─ single-threaded/ (SingleServer.java) # blocking, 1 worker
├─ multi-threaded/ (MultiServer.java) # blocking, fixed pool
├─ threadloop/ (LoopServer.java) # NIO selector + small CPU offload pool
├─ jmeter/
│ ├─ plans/server-comparison-1krps.jmx # Constant Throughput Timer = 60000.0 (per minute)
│ ├─ results/ # (optional) raw .jtl/.jtl.gz logs; can be gitignored
│ └─ reports/ # HTML dashboards generated from JTLs
```


**Requirements:** Java 17+, Gradle 7+, JMeter 5.6.x

---

## Run the servers

```bash
# macOS/Linux
./gradlew runSingle                 # 8081
./gradlew runMulti -Pthreads=16     # 8082  (tune worker count)
./gradlew runLoop                   # 8083
```

Endpoints (all servers)

/echo?size=NN → returns NN bytes (default 1024)

/cpu?ms=NN → busy-spin NN ms (CPU-bound)

/io-slow?ms=NN → sleep NN ms (IO-bound)

/mixed?cpuMs=A&ioMs=B → A ms CPU + B ms sleep

The servers bind with a large backlog (e.g., 4096) to reduce Connection refused under load.



## JMeter (GUI) quick start

Open jmeter/plans/server-comparison-1krps.jmx in JMeter 5.6.x.

Test Plan → User Defined Variables

host=localhost

port=8081 (use 8082 for multi, 8083 for loop)

path=/echo?size=1024

threads=300, rampup=30, duration=300

Constant Throughput Timer

Throughput = 60000.0 (numeric, per minute)

Calculate throughput based on → All active threads in current thread group

HTTP Request

Implementation HttpClient4

Use KeepAlive = ON

(While debugging) Connect timeout 10000, Response timeout 15000 ms

Add Summary Report (and optionally Response Time Percentiles), then ▶ Start.

Sizing rule of thumb:
threads ≈ target_rps × average_latency_seconds × 1.5
(e.g., 1000 rps @ 200 ms → ~300 threads)


## Troubleshooting

Plan fails to load → Constant Throughput Timer must be a number (e.g., 60000.0), not ${var}.

Throughput far above/below 1k rps → ensure timer mode is All active threads… and size threads per heuristic.

High errors

ConnectException: refused → confirm server up; backlog large; avoid exhausting ports.

SocketTimeoutException: Read timed out → server saturated at current rate; reduce rate or tune worker pool / offload.

GUI re-run hygiene → Run → Clear All before each new run to avoid mixing stats.
