# benchmark-guice — Guice JIT Binding Contention

Benchmarks Guice `injector.getInstance()` contention under concurrent access. Demonstrates the performance impact of JIT (Just-In-Time) binding resolution and compares mitigation strategies.

## Background

Guice's `InjectorImpl.getJustInTimeBinding()` acquires a **synchronized lock** on `InjectorJitBindingData` every time it resolves a JIT binding. This means every call to `injector.getInstance()` for a class without an explicit pre-built binding hits this global lock.

## Strategies Compared

| Strategy | Description | Lock Behavior |
|---|---|---|
| `getInstance_jit` | Direct `injector.getInstance()` with JIT binding | Acquires synchronized lock on every call |
| `getInstance_explicit` | `injector.getInstance()` with explicit module binding | Binding resolved at injector creation; no JIT lock at runtime |
| `cachedProvider` | Provider cached in `ConcurrentHashMap`, then `provider.get()` | First call resolves and caches; subsequent calls lock-free |
| `directProvider` | Pre-resolved provider at setup time | Provider resolved once during startup; no Guice overhead at runtime |

## How to Run

```bash
# Build
mvn package -pl benchmark-guice

# Run all benchmarks (outputs results to benchmark-guice/results.json)
java -jar benchmark-guice/target/benchmarks.jar -rf json -rff benchmark-guice/results.json
```

## Environment

| Property | Value |
|---|---|
| JMH version | 1.37 |
| JVM | OpenJDK 64-Bit Server VM |
| Guice version | 7.0.0 |
| Mode | Throughput (`thrpt`) |
| Unit | ops/ms |
| Warmup | 3 iterations × 1 s |
| Measurement | 5 iterations × 1 s |
| Forks | 1 |

## Results

> Mode: throughput (`thrpt`) · Unit: ops/ms · Higher is better

| Threads | `getInstance_jit` | `getInstance_explicit` | `cachedProvider` | `directProvider` |
|---:|---:|---:|---:|---:|
| 1 | 26,722 | 29,430 | 40,416 | 44,212 |
| 2 | 15,149 | 40,028 | 28,814 | 39,272 |
| 4 | 8,238 | 106,912 | 66,368 | 57,090 |
| 8 | 7,435 | 102,625 | 53,063 | 147,574 |
| 16 | 7,828 | 82,352 | 27,440 | 62,436 |
| 32 | 8,258 | 79,064 | 24,464 | 99,258 |

## Analysis

### `getInstance_jit` — severe degradation under contention

At 1 thread, JIT binding achieves ~27,000 ops/ms. As soon as a second thread joins, throughput **drops 43%** to ~15,000 ops/ms. By 4 threads it collapses to ~8,200 ops/ms and **flatlines regardless of thread count**. This confirms the production finding: the synchronized lock on `InjectorJitBindingData` serializes all JIT resolutions.

**Key observation:** At 32 threads, JIT binding is **9.6× slower** than explicit binding and **12× slower** than directProvider.

### `getInstance_explicit` — scales well, no JIT lock

Explicit bindings avoid the JIT lock entirely since bindings are resolved at injector creation. Throughput scales from ~29,000 ops/ms (1 thread) to a peak of **107,000 ops/ms (4 threads)** — a 3.6× improvement. Performance remains strong at higher thread counts (~79,000 ops/ms at 32 threads).

**Recommendation:** This is the best long-term fix — explicitly bind all classes in Guice modules.

### `cachedProvider` — good single-thread, moderate scaling

The cached provider pattern shows excellent single-thread performance (~40,000 ops/ms) but experiences some contention at higher thread counts, likely due to `ConcurrentHashMap` overhead on the hot path. At 32 threads it delivers ~24,500 ops/ms — still **3× faster than JIT binding**.

**Recommendation:** Good incremental fix for existing code without requiring module changes.

### `directProvider` — best overall throughput

Pre-resolving providers at startup eliminates all Guice overhead from the hot path. This strategy shows the best peak throughput: **147,500 ops/ms at 8 threads** — nearly **20× faster than JIT** at the same thread count. Scales well across all thread counts.

**Recommendation:** Ideal for singleton services or when providers can be resolved eagerly at startup.

### Summary

| Strategy | 1-thread | 32-threads | Speedup vs JIT (32t) | Best For |
|---|---:|---:|---:|---|
| `getInstance_jit` | 26,722 | 8,258 | 1.0× (baseline) | Never use in hot paths |
| `getInstance_explicit` | 29,430 | 79,064 | **9.6×** | New code, module refactoring |
| `cachedProvider` | 40,416 | 24,464 | **3.0×** | Incremental fix for existing code |
| `directProvider` | 44,212 | 99,258 | **12.0×** | Singletons, startup-resolved deps |

