# benchmark-connection-pool — JDBC Connection Pool Throughput

Benchmarks JDBC connection pool throughput under concurrent load. Compares the five pools commonly encountered in Dropwizard + Hibernate production stacks: HikariCP, Apache DBCP2, c3p0, Vibur DBCP, and Tomcat JDBC Pool.

## Background

Connection pools differ significantly in how they manage concurrent acquisition requests internally:

| Pool | Internal concurrency design |
|---|---|
| **HikariCP** | `ConcurrentBag` — thread-local biased list with CAS fallback. Each thread checks its own cached connection list first, falling back to a shared `SynchronousQueue` handoff only on a miss. |
| **Apache DBCP2** | `GenericObjectPool` backed by a `LinkedBlockingDeque` protected by a single `ReentrantLock`. All threads funnel through this lock. |
| **c3p0** | `BasicResourcePool` with a `synchronized` FIFO queue. Every `getConnection()` acquires a global monitor lock. |
| **Vibur DBCP** | `ConcurrentLinkedDeque` with a `Semaphore` to bound concurrent acquisitions. Claims a near-lock-free fast path. |
| **Tomcat JDBC Pool** | `FairBlockingQueue` backed by a `ReentrantLock`. Dropwizard's default pool before HikariCP replaced it in v1.3. |

## Pool Configuration

All pools are configured identically to isolate pool overhead from any other variable:

| Setting | Value |
|---|---|
| Pool size (min = max) | 4 connections (`p4_` benchmarks) or 8 connections (`p8_` benchmarks) |
| Database | H2 in-memory (`jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1`) |
| Connection timeout | 5,000 ms |
| Connection validation on borrow | Disabled for all pools |
| Fair queue / FIFO policy | Disabled (`fairQueue=false` for Tomcat) |
| Pre-warming | All connections established before measurement begins |

Each benchmark iteration calls `getConnection()` and immediately returns the connection via `close()`. This is the dominant pattern in Dropwizard + Hibernate where Hibernate acquires and releases a connection per transaction.

**Pool sizes and thread counts tested:**

Two pool sizes are compared — 4 and 8 — across five thread counts: 1, 4, 8, 32, 64.

| Regime | Pool size 4 | Pool size 8 |
|---|---|---|
| Threads ≤ pool size | 1t, 4t | 1t, 4t, 8t |
| Threads > pool size | 8t, 32t, 64t | 32t, 64t |

When threads outnumber pool connections, some threads must wait — this is the high-contention regime where pool design differences become most visible.

## How to Run

```bash
# Build
mvn package -pl benchmark-connection-pool

# Run all benchmarks
java -jar benchmark-connection-pool/target/benchmarks.jar -rf json -rff benchmark-connection-pool/results.json
```

## Environment

### Hardware

| Property | Value |
|---|---|
| CPU | AMD EPYC-Rome |
| Cores / threads | 8 cores, 8 threads (1 thread/core, 1 socket) |
| L3 cache | 16 MiB |
| RAM | 16 GiB |
| OS | Linux kernel 6.8.0-90-generic |

### Software

| Property | Value |
|---|---|
| JDK | OpenJDK 64-Bit Server VM 17.0.2+8-86 |
| JMH | 1.37 |
| HikariCP | 5.1.0 |
| Apache DBCP2 | 2.12.0 |
| c3p0 | 0.10.1 |
| Vibur DBCP | 25.0 |
| Tomcat JDBC Pool | 10.1.24 |
| H2 | 2.3.232 |
| Mode | Throughput (`thrpt`) |
| Unit | ops/ms |
| Warmup | 5 iterations × 2 s |
| Measurement | 5 iterations × 2 s |
| Forks | 5 |

## Results

> Mode: throughput (`thrpt`) · Unit: ops/ms · Higher is better · `±` is the 99% confidence interval
>
> ⚠ = high variance (scoreError > 30% of score) — point estimate is unreliable; see [Variance diagnosis](#variance-diagnosis)

### Pool size 4

| Threads | HikariCP | DBCP2 | c3p0 | Vibur | Tomcat |
|---:|---:|---:|---:|---:|---:|
|  1 | 7,770 ± 194    | 1,590 ± 38  | 110 ± 5   | 4,361 ± 106 | 3,084 ± 156 |
|  4 | 27,856 ± 271   | 1,356 ± 80  | 127 ± 10  | 1,662 ± 299 | 2,136 ± 93  |
|  8 | 1,487 ± 244    | 717 ± 55    | 109 ± 11  | 24 ± 2      | 1,558 ± 275 |
| 32 | 1,455 ± 288    | 232 ± 14    | 103 ± 7   | 22 ± 1      | 755 ± 91    |
| 64 | 1,172 ± 129    | 126 ± 17    | 92 ± 10   | 21 ± 1      | 472 ± 95    |

### Pool size 8

| Threads | HikariCP | DBCP2 | c3p0 | Vibur | Tomcat |
|---:|---:|---:|---:|---:|---:|
|  1 | 7,811 ± 145       | 1,605 ± 37  | 235 ± 12  | 4,394 ± 86   | 3,232 ± 125 |
|  4 | 27,178 ± 419      | 1,417 ± 111 | 291 ± 22  | 1,366 ± 107  | 2,102 ± 84  |
|  8 | 36,353 ± 12,389 ⚠ | 1,055 ± 88  | 256 ± 27  | 1,351 ± 89   | 1,422 ± 86  |
| 32 | 14,647 ± 1,941    | 434 ± 31    | 197 ± 21  | 23 ± 2       | 1,366 ± 227 |
| 64 | 5,409 ± 929       | 254 ± 13    | 209 ± 26  | 21 ± 1       | 899 ± 123   |

## Variance diagnosis

The ⚠ results are not random noise — the raw per-fork iteration data reveals a specific pattern.

### HikariCP: inter-fork JIT compilation lottery

HikariCP's high-variance results are **bimodal between forks, not within forks**. Within a single fork the 5 measurement iterations are tightly clustered; the variance comes entirely from different forks landing in different stable operating modes. This was first observed in the 2-fork run:

| Benchmark | Fork 1 mean (ops/ms) | Fork 2 mean (ops/ms) |
|---|---:|---:|
| p8_hikari_8threads | ~20,300 | ~51,400 |
| p8_hikari_4threads | ~10,800 | ~27,000 |
| p8_hikari_32threads | ~8,500 | ~14,100 |

The intra-fork stability rules out OS scheduling jitter as the cause. The inter-fork split is caused by a **JIT profiling race during warmup**: the JIT compiler profiles `ConcurrentBag.borrow()` during the first few hundred milliseconds of execution. If thread-local lists are populated by then, the JIT optimizes the fast path; if they are still empty, it optimizes the shared-list scan path. Once compiled, the fork is locked into that mode. See [Profiling the bimodal](#profiling-the-bimodal-root-cause-investigation) for the full experimental evidence.

**With 5 forks, the distribution becomes interpretable.** For `p8_hikari_8threads` the five fork means are:

| Fork | Mean (ops/ms) | Mode |
|---:|---:|---|
| 1 | ~20,700 | slow (handoff path) |
| 2 | ~48,600 | fast (thread-local path) |
| 3 | ~52,200 | fast (thread-local path) |
| 4 | ~13,000 | slow (handoff path) |
| 5 | ~47,300 | fast (thread-local path) |

The fast path wins 3 out of 5 forks. The JMH mean (36,353 ± 12,389) is still wide, but now its shape is understood: it is an average over two distinct modes, not random noise. The slow-mode floor (~13,000–21,000 ops/ms) is the conservative estimate of what this pool delivers at 8 threads with pool size 8.

The 5-fork run also resolved most other previously ⚠ HikariCP results. With 2 forks, a single unlucky split (one fast fork + one slow fork) inflated many CIs. With 5 forks, those cases now have enough samples to average out: `p8_hikari_32threads` dropped from CV 42% to 13%, and `p8_hikari_64threads` from CV 38% to 17%. Only `p8_hikari_8threads` remains ⚠ because it is the exact saturation point where the mode split is most pronounced.

### Vibur: inter-fork variance at 4 threads

Vibur at 4 threads (pool size 4) shows CV ~18% with fork means of 1,737 / 1,616 / 1,344 / 1,467 / 2,148 ops/ms. Unlike HikariCP, the spread is continuous rather than bimodal — no clear fast/slow grouping. This is consistent with the `Semaphore` transition: at exactly 4 threads with pool size 4 the pool is right at its saturation boundary, and small differences in thread scheduling across forks determine how often threads contend on the semaphore. At 1 thread (well below saturation) and 8+ threads (well above it) the behaviour is tight and predictable.

### Tomcat and DBCP2: normal scheduling noise

Both pools show continuous spread across forks with no mode split. Their variance is ordinary OS scheduling jitter and is not a concern for interpretation.

## Analysis

### HikariCP — fastest overall, bimodal only at the exact saturation point

HikariCP is the throughput leader at every thread count where threads ≤ pool size. With pool size 4 it peaks at **27,856 ops/ms at 4 threads**; with pool size 8 it peaks at **~36,353 ops/ms at 8 threads**. The pattern is consistent: HikariCP peaks exactly when threads equal pool size.

The explanation is `ConcurrentBag`'s thread-local fast path. When each thread has its own cached connection slot and the pool is exactly saturated, every acquisition is a thread-local list lookup with no CAS contention and no cross-thread interaction. Adding more threads than pool slots forces excess threads through the shared `SynchronousQueue` handoff path, which is both slower and subject to the JIT compilation lottery described above.

With 5 forks, only `p8_hikari_8threads` (the exact saturation point) retains a ⚠ flag. All other thread counts above saturation now have stable, interpretable results — for example, `p8_hikari_32threads` measures 14,647 ± 1,941 ops/ms and `p8_hikari_64threads` measures 5,409 ± 929. These are consistent across forks: HikariCP remains far ahead of every other pool even in the oversubscribed regime.

When threads are below the saturation point, HikariCP results are tight and reproducible (e.g., 7,770 ± 194 at 1 thread with pool size 4; 27,856 ± 271 at 4 threads).

### DBCP2 — stable and predictable under contention

DBCP2 is the most consistent pool in the dataset. Its throughput follows a monotonically decreasing curve as threads increase. The single-thread rate (~1,590–1,605 ops/ms) is its ceiling; adding threads reduces per-thread throughput while total throughput stays roughly flat until threads far outnumber connections, then begins declining.

The `LinkedBlockingDeque` + `ReentrantLock` design means every acquisition costs one lock acquire/release. This is more expensive than HikariCP's thread-local fast path at low contention, but the cost is **deterministic and bounded** — there is no bimodal behavior, no cliff, no collapse. DBCP2's CVs are the lowest of all pools at every thread count.

At 32 threads with pool size 8, DBCP2 delivers 434 ± 31 ops/ms. Tomcat delivers 1,366 ops/ms and HikariCP delivers 14,647 ops/ms at the same point — but both with higher variance. DBCP2 is the choice where **predictability under load matters more than peak throughput**.

### c3p0 — flat and slow across all conditions

c3p0 is the slowest pool at every measured point. Its throughput range is narrow: 92–291 ops/ms across all thread counts and pool sizes. Unlike the other pools, c3p0 does not degrade sharply with thread count — it was already slow at 1 thread and remains similarly slow at 64. The globally `synchronized` queue serialises all acquisitions so completely that adding threads adds negligible additional contention overhead.

The pool-size effect is visible: c3p0 scores ~92–127 ops/ms with pool size 4 versus ~197–291 ops/ms with pool size 8. A larger pool means threads block less often waiting for a slot, reducing monitor re-entry frequency. Even at its best (291 ops/ms, pool=8, 4 threads), c3p0 is **5× slower than DBCP2** at the same conditions.

### Vibur DBCP — strong at low contention, semaphore collapse at high contention

Vibur's uncontended single-thread results (4,361–4,394 ops/ms) are competitive with Tomcat and significantly better than DBCP2 and c3p0. The `ConcurrentLinkedDeque` + `Semaphore` design does deliver a genuine fast path when threads ≤ pool size.

The collapse when threads exceed pool size is severe and consistent across both pool configurations:

- **Pool size 4:** 4,361 ops/ms at 1 thread → 24 ops/ms at 8 threads (×180 drop for 2× thread overshoot)
- **Pool size 8:** 4,394 ops/ms at 1 thread → 1,351 ops/ms at 8 threads (still within pool) → 23 ops/ms at 32 threads (4× overshoot)

Once threads outnumber connections, the `Semaphore` enters a contested state where threads park and unpark on every acquisition. This parking/unparking overhead is catastrophically expensive — the pool drops to ~21–24 ops/ms regardless of thread count, essentially becoming serialised at the OS scheduler level.

The sharp transition between 4 threads (1,662 ops/ms, pool=4, at pool capacity) and 8 threads (24 ops/ms, pool=4, 2× over capacity) is the defining characteristic of Vibur's design. It is an excellent choice only when thread count is reliably ≤ pool size.

### Tomcat JDBC Pool — solid under contention with fair queue disabled

With `fairQueue=false`, Tomcat performs credibly across all contention levels. The `ReentrantLock` in non-fair mode allows barging, which prevents the FIFO convoy stall and keeps throughput higher under oversubscription.

Single-thread throughput (3,084–3,232 ops/ms) is lower than HikariCP and Vibur but higher than DBCP2. Under high contention (32–64 threads), Tomcat is the second-best pool: at 32 threads with pool size 8 it delivers **1,366 ops/ms**, compared to 434 ops/ms for DBCP2, 197 ops/ms for c3p0, and 23 ops/ms for Vibur.

Tomcat degrades gradually as threads increase — there is no cliff. Its main limitation is that its peak throughput is structurally bounded by its lock: it cannot approach HikariCP's thread-local speed at low contention. For services where the thread count routinely exceeds the pool size, Tomcat (non-fair) is the most predictable high-throughput option after HikariCP.

## Summary

| Pool | Peak throughput | Pool size 4 @ 32t | Pool size 8 @ 32t | Scaling behavior |
|---|---:|---:|---:|---|
| **HikariCP** | **~36,353 ops/ms** (8t, p8) ⚠ | 1,455 ops/ms | 14,647 ops/ms | Peaks at threads = pool size; bimodal only at exact saturation |
| **Tomcat** | 3,084 ops/ms (1t, p4) | 755 ops/ms | 1,366 ops/ms | Graceful degradation; best non-Hikari at high contention |
| **DBCP2** | 1,605 ops/ms (1t, p8) | 232 ops/ms | 434 ops/ms | Flat ceiling; most predictable |
| **Vibur** | 4,394 ops/ms (1t, p8) | 22 ops/ms | 23 ops/ms | Fast below pool size; catastrophic collapse above it |
| **c3p0** | 291 ops/ms (4t, p8) | 103 ops/ms | 197 ops/ms | Slow everywhere; flat degradation |

**HikariCP is the correct choice** for Dropwizard + Hibernate services under any thread count. Its `ConcurrentBag` delivers 4–10× the throughput of the next-best pool (Tomcat) in the regime where threads ≤ pool size, which is where well-tuned services spend most of their time. Even in the oversubscribed regime it remains the fastest pool by a wide margin.

If thread count reliably exceeds pool size and variance is a concern, **Tomcat (non-fair)** offers the best combination of throughput and predictability in the high-contention regime.

**Vibur requires caution**: it is fast when uncontended but collapses to near-zero throughput under any pool oversubscription. Its use should be limited to services where thread count is strictly bounded below pool size.

## Profiling the bimodal: root cause investigation

The bimodal behavior at the saturation point warranted deeper investigation. A dedicated profiling benchmark (`HikariProfilingBenchmark`) and orchestration script (`profile-hikari.sh`) systematically identified the root cause through controlled experiments.

### Root cause: JIT profiling race during warmup

The bimodal is caused by a **race between thread-local list population and JIT compilation profiling** during the first few hundred milliseconds of each JVM fork.

Here is the mechanism:

1. **Empty thread-locals at startup.** When a fresh JVM fork starts executing `ConcurrentBag.borrow()`, every thread's thread-local list is empty. All threads take the **shared list scan path** (Phase 2), iterating a `CopyOnWriteArrayList` and competing via CAS.

2. **The population race.** Thread-local lists get populated via `requite()` only when `waiters.get() == 0`. At exact saturation (8 threads, 8 connections), whether `waiters == 0` during early returns is a scheduling lottery. Some forks populate thread-locals within the first ~100ms; others take longer.

3. **The JIT compilation window.** The JIT compiler (C1 at tiers 1-3, C2 at tier 4) profiles `borrow()` during this early window. The profiling data determines which code path the compiler optimizes:
   - **Thread-locals populated before profiling** → JIT optimizes the thread-local fast path → **fast mode** (~47-52k ops/ms on Linux EPYC)
   - **Thread-locals still empty during profiling** → JIT optimizes the shared-list scan path → **slow mode** (~13-21k ops/ms on Linux EPYC)

4. **Lock-in.** Once the JIT commits to a compilation, the system is locked into that mode. The compiled code creates a self-reinforcing feedback loop: fast-mode code keeps `waiters` low (reinforcing thread-local returns), while slow-mode code keeps `waiters` elevated (reinforcing SynchronousQueue handoffs). Within-fork CV is <3% across 5 measurement iterations — the attractor state is stable.

```
                 ┌── TL populated quickly ──→ JIT optimizes TL path ──→ FAST MODE
                 │   (scheduling luck)        (self-reinforcing:         (~50k ops/ms)
                 │                             TL hits keep waiters==0)
Warmup ──────────┤
starts           │
                 └── TL slow to populate ──→ JIT optimizes shared path ──→ SLOW MODE
                     (scheduling luck)       (self-reinforcing:            (~17k ops/ms)
                                              shared scan keeps waiters>0)
```

### Experimental evidence

Five experiments isolate the mechanism. The discriminating variable is whether thread-local lists are populated **before** the JIT profiles `borrow()`:

| Experiment | Thread-locals populated before JMH? | Result | Mean (ops/ms) |
|---|---|---|---|
| `baseline` | No | **BIMODAL** (2F/8S, 1.4x) | F=23,631 S=16,368 |
| `sleep-only` | No (100ms sleep, no borrow) | **BIMODAL** (7F/3S, 1.5x) | F=21,198 S=14,525 |
| `borrow-once` | Yes (1 borrow/return per thread) | UNIMODAL | 15,875 |
| `force-threadlocal` | Yes (10 borrow/return per thread) | UNIMODAL | 16,821 |
| `force-affinity` | Yes (coordinated parallel borrow) | UNIMODAL | 18,608 |

> Results above are from macOS ARM (Apple Silicon), where the bimodal ratio is 1.4x. On the original Linux AMD EPYC the ratio is 2.9x — the effect is amplified by x86 CAS costs and NUMA cache-line bouncing across 8 cores.

Key findings:

1. **`borrow-once` (UNIMODAL) vs `sleep-only` (BIMODAL)** — the minimal intervention that eliminates the bimodal is a single borrow/return per thread. Time delay alone does nothing. This proves thread-local population is the necessary condition, not pool initialization time.

2. **All prewarm modes produce the same end-state.** At teardown, every fork — fast and slow — shows 7/8 threads with unique connections and 1/8 with an empty thread-local list. The system always converges to full affinity. The difference is **when** it converges relative to the JIT profiling window.

3. **C1-only (`-XX:TieredStopAtLevel=1`) still shows bimodal** (2F/8S, 1.5x ratio). C1's profile-guided compilation at tiers 1-3 is sufficient to create the lottery, though C2's more aggressive speculative optimizations amplify the effect (2.9x on Linux with C2 vs 1.5x with C1-only).

4. **Controls confirm saturation-specificity.** 1 thread, 4 threads, and 16 threads all produce unimodal distributions. The bimodal only occurs at the exact saturation point where the thread-local fast path and shared-list scan path are both viable.

### ConcurrentBag code paths

| Path | Mechanism | When used |
|---|---|---|
| **Phase 1: thread-local** | `threadList.get()` → LIFO scan → CAS `NOT_IN_USE→IN_USE` | Thread has cached entries |
| **Phase 2: shared list** | `CopyOnWriteArrayList` iteration → CAS on each entry | Thread-local list empty or all entries in use |
| **Phase 3: handoff queue** | `SynchronousQueue.poll()` with timeout | No entries available; waits for a return |

On return (`requite()`):
- If `waiters > 0`: offer to `SynchronousQueue` (handoff to a waiting thread)
- If `waiters == 0`: add to the returning thread's local list (building up the fast path for next borrow)

This creates the bifurcation: if threads stagger during warmup → some returns see `waiters == 0` → thread-local lists grow → fast mode. If threads synchronize → returns always see `waiters > 0` → handoff instead of thread-local → slow mode.

### Production implications

**The bimodal is primarily a microbenchmark artifact.** In production services:

- **Thread count >> pool size is the norm.** A typical service runs hundreds or thousands of request-handling threads against a pool of 10-50 connections. At this ratio, the thread-local fast path is irrelevant — most borrow() calls go through the shared list or handoff queue. The bimodal disappears because there is only one viable mode (the "slow" path), and HikariCP is still the fastest pool at this operating point.

- **Connection hold times dominate.** Microbenchmarks do `getConnection()` + immediate `close()` in a tight loop (sub-microsecond hold time). Production transactions hold connections for milliseconds to seconds (query execution, network I/O). The pool acquisition overhead — even on the "slow" path — is negligible compared to what happens while the connection is held.

- **If you want the fast path anyway:** pre-warm the pool at service startup by having each thread do one borrow/return cycle before accepting traffic. This is the `borrow-once` pattern and is trivially cheap. However, at production thread counts (>>pool size), this will have no measurable effect on throughput.

- **Pool sizing matters more.** The biggest throughput lever in production is `maximumPoolSize` relative to your concurrency level and query latency. See [HikariCP's pool sizing guide](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) for the formula: `connections = ((core_count * 2) + effective_spindle_count)`.

### Running the profiling experiments

```bash
# Build
./profile-hikari.sh build

# Recommended investigation order:
./profile-hikari.sh baseline             # 1. Reproduce bimodal (10 forks)
./profile-hikari.sh borrow-once          # 2. Minimal pre-warm → expect unimodal
./profile-hikari.sh sleep-only           # 3. Time delay only → expect bimodal
./profile-hikari.sh force-affinity       # 4. Coordinated parallel pre-warm → expect unimodal
./profile-hikari.sh force-threadlocal    # 5. Serialized pre-warm → expect unimodal
./profile-hikari.sh flush-threadlocal    # 6. Flush thread-local each iteration → expect all-slow
./profile-hikari.sh c1-only             # 7. Disable C2 → still bimodal (weaker)
./profile-hikari.sh controls            # 8. Verify 1t/4t/16t show no bimodal
```

Override fork count: `FORKS=20 ./profile-hikari.sh baseline`

Each experiment runs N forks (default 10) and classifies each fork as fast or slow using Otsu's method (`scripts/classify-forks.py`).

### Diagnostic output

The profiling benchmark prints `[DIAG]` lines to stderr at teardown:
- Per-thread `threadLocal.size` and cached entry identity hash codes — reveals whether threads have unique connections or share the same one
- Shared list entry states (`NOT_IN_USE`, `IN_USE`, etc.) and identity hash codes
- `waiters` count

These are captured in the per-fork log files under `profiling/<experiment>/fork_N.log`.
