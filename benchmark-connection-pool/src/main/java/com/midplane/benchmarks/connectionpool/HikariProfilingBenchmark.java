package com.midplane.benchmarks.connectionpool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Profiling benchmark for HikariCP's bimodal throughput behavior.
 *
 * <h2>The Problem</h2>
 * At the pool saturation point (threads == pool size == 8), HikariCP's
 * ConcurrentBag exhibits a bimodal distribution <em>between forks</em>:
 * some forks achieve ~47,000–52,000 ops/ms ("fast mode") while others
 * achieve only ~13,000–21,000 ops/ms ("slow mode").  Within each fork
 * the 5 measurement iterations are tightly clustered (&lt;3% CV).
 *
 * <h2>Hypotheses</h2>
 * <ol>
 *   <li><b>Thread-local population (primary hypothesis):</b>
 *       {@code ConcurrentBag.requite()} only adds entries to a thread's
 *       local list when {@code waiters.get() == 0}.  At threads == pool_size,
 *       whether waiters == 0 during early returns depends on OS thread
 *       scheduling during warmup.  Once the system enters a mode (thread-local
 *       lists populated vs. empty), it is self-reinforcing — a dynamical
 *       system with two stable attractors.</li>
 *
 *   <li><b>JIT compilation lottery (secondary hypothesis):</b>
 *       C2 profiles during warmup and may optimize different code paths
 *       depending on which path was hot during the profiling window.
 *       The thread-local fast path and the SynchronousQueue handoff path
 *       are structurally different enough that C2 could specialize for
 *       either one.</li>
 * </ol>
 *
 * <h2>Experiments</h2>
 * This benchmark supports four pre-warming modes via the {@code prewarm}
 * parameter:
 * <ul>
 *   <li>{@code none} — natural warmup; reproduces the bimodal behavior.</li>
 *   <li>{@code force_threadlocal} — serialized per-thread warmup ensures
 *       each thread's ConcurrentBag thread-local list is populated before
 *       JMH warmup begins.  <b>Caveat:</b> serialized setup means all threads
 *       cache the same connection (index 0 in shared list).</li>
 *   <li>{@code force_affinity} — coordinated parallel warmup: all threads
 *       borrow simultaneously (each gets a different connection), hold them,
 *       then return simultaneously.  Each thread's local list gets a unique
 *       connection.  This tests whether 1:1 thread-to-connection affinity
 *       eliminates the bimodal.</li>
 *   <li>{@code flush_threadlocal} — clears each thread's thread-local list
 *       at the start of every measurement iteration via reflection.</li>
 * </ul>
 *
 * <h2>Diagnostic Output</h2>
 * At teardown, the benchmark prints {@code [DIAG]} lines to stderr with:
 * <ul>
 *   <li>ConcurrentBag shared list size and entry states</li>
 *   <li>Per-thread thread-local list sizes</li>
 * </ul>
 * These are captured by {@code profile-hikari.sh} for post-run analysis.
 *
 * <h2>Usage</h2>
 * Run via {@code profile-hikari.sh} for orchestrated experiments:
 * <pre>
 *   ./profile-hikari.sh baseline         # reproduce bimodal
 *   ./profile-hikari.sh force-threadlocal # test thread-local hypothesis
 *   ./profile-hikari.sh c1-only          # test JIT hypothesis
 *   ./profile-hikari.sh all-portable     # run all non-perf experiments
 * </pre>
 *
 * Or run directly with JMH:
 * <pre>
 *   java -jar benchmarks.jar HikariProfilingBenchmark.saturated_8t \
 *        -p prewarm=none -f 1 -rf json -rff result.json
 * </pre>
 *
 * @see ConnectionPoolBenchmark for the full multi-pool comparison benchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1) // profiling script runs multiple forks externally for per-fork analysis
public class HikariProfilingBenchmark {

    private static final String JDBC_URL = "jdbc:h2:mem:profiling;DB_CLOSE_DELAY=-1";
    private static final String DRIVER   = "org.h2.Driver";
    private static final String USER     = "sa";
    private static final String PASSWORD = "";
    private static final int POOL_SIZE   = 8;

    // =========================================================================
    // Pool state (shared across all benchmark threads within a fork)
    // =========================================================================

    @State(Scope.Benchmark)
    public static class PoolState {
        HikariDataSource dataSource;

        /**
         * Pre-warming mode:
         * <ul>
         *   <li>"none" — natural warmup (reproduces bimodal)</li>
         *   <li>"force_threadlocal" — serialize per-thread setup; all threads
         *       cache the same connection (shared list index 0)</li>
         *   <li>"force_affinity" — coordinated parallel setup; each thread
         *       gets a unique connection in its thread-local list</li>
         *   <li>"flush_threadlocal" — clear thread-local lists at start of
         *       each measurement iteration</li>
         *   <li>"sleep_only" — just sleep ~100ms per thread in trial setup,
         *       no borrow/return. Tests if initialization time alone matters.</li>
         *   <li>"borrow_once" — single borrow/return per thread (serialized).
         *       Minimal thread-local population; tests if one cycle is enough.</li>
         * </ul>
         */
        @Param({"none", "force_threadlocal", "force_affinity", "flush_threadlocal",
                 "sleep_only", "borrow_once"})
        String prewarm;

        /** Serializes thread-local pre-warming so each thread warms alone. */
        final Semaphore warmGate = new Semaphore(1);

        /**
         * Phaser for coordinated parallel warmup (force_affinity mode).
         * Threads register dynamically; the phaser coordinates borrow-hold-return.
         */
        final Phaser affinityPhaser = new Phaser(0);

        @Setup(Level.Trial)
        public void setup() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(JDBC_URL);
            cfg.setUsername(USER);
            cfg.setPassword(PASSWORD);
            cfg.setDriverClassName(DRIVER);
            cfg.setMinimumIdle(POOL_SIZE);
            cfg.setMaximumPoolSize(POOL_SIZE);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(600_000);
            cfg.setConnectionTestQuery(null);
            dataSource = new HikariDataSource(cfg);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            introspectPoolState();
            dataSource.close();
        }

        /**
         * Uses reflection to examine ConcurrentBag internals at teardown.
         * Prints shared list size, waiter count, and per-entry state.
         */
        private void introspectPoolState() {
            try {
                Object pool = getField(dataSource, "pool");
                if (pool == null) pool = getField(dataSource, "fastPathPool");
                Object bag = getField(pool, "connectionBag");

                @SuppressWarnings("unchecked")
                CopyOnWriteArrayList<Object> sharedList =
                        (CopyOnWriteArrayList<Object>) getField(bag, "sharedList");
                AtomicInteger waiters = (AtomicInteger) getField(bag, "waiters");

                System.err.println("[DIAG] === ConcurrentBag pool-level state ===");
                System.err.println("[DIAG] sharedList.size = " + sharedList.size());
                System.err.println("[DIAG] waiters         = " + waiters.get());

                // Count entries by state:
                //   STATE_NOT_IN_USE = 0, STATE_IN_USE = 1,
                //   STATE_REMOVED = -1, STATE_RESERVED = -2
                int notInUse = 0, inUse = 0, removed = 0, reserved = 0;
                for (Object entry : sharedList) {
                    int state = ((Number) getField(entry, "state")).intValue();
                    switch (state) {
                        case  0: notInUse++; break;
                        case  1: inUse++;    break;
                        case -1: removed++;  break;
                        case -2: reserved++; break;
                    }
                }
                System.err.println("[DIAG] entry states: NOT_IN_USE=" + notInUse
                        + " IN_USE=" + inUse + " REMOVED=" + removed
                        + " RESERVED=" + reserved);

                // Print identity hash of each entry for cross-referencing with
                // per-thread local lists.
                StringBuilder ids = new StringBuilder("[DIAG] sharedList entry ids:");
                for (int idx = 0; idx < sharedList.size(); idx++) {
                    ids.append(" [").append(idx).append("]=")
                       .append(System.identityHashCode(sharedList.get(idx)));
                }
                System.err.println(ids.toString());
            } catch (Exception e) {
                System.err.println("[DIAG] Pool introspection failed: " + e);
            }
        }

        /**
         * Reflective field access that walks up the class hierarchy.
         * Handles private fields in parent classes (e.g., PoolBase).
         */
        static Object getField(Object obj, String fieldName) throws Exception {
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new NoSuchFieldException(
                    fieldName + " not found in hierarchy of " + obj.getClass().getName());
        }
    }

    // =========================================================================
    // Per-thread state (pre-warming + per-iteration flush + diagnostics)
    // =========================================================================

    @State(Scope.Thread)
    public static class ThreadState {

        /**
         * Trial-level setup: optionally pre-populate this thread's
         * ConcurrentBag thread-local list.
         *
         * <p><b>force_threadlocal</b>: serialized borrow/return — fast but all
         * threads cache the same connection (shared list index 0).</p>
         *
         * <p><b>force_affinity</b>: coordinated parallel borrow — all threads
         * borrow simultaneously (each gets a different connection), hold them
         * via a Phaser barrier, then return simultaneously.  Since no thread
         * is borrowing during the return phase, {@code waiters == 0} and each
         * thread's unique connection goes into its own thread-local list.</p>
         */
        @Setup(Level.Trial)
        public void prewarm(PoolState pool) throws Exception {
            if ("force_threadlocal".equals(pool.prewarm)) {
                pool.warmGate.acquire();
                try {
                    for (int i = 0; i < 10; i++) {
                        try (Connection c = pool.dataSource.getConnection()) {
                            // no-op: just populate the thread-local list
                        }
                    }
                    Thread.sleep(5);
                } finally {
                    pool.warmGate.release();
                }
            } else if ("force_affinity".equals(pool.prewarm)) {
                // Register this thread with the phaser
                pool.affinityPhaser.register();

                // Phase 0 → 1: all threads borrow simultaneously.
                // With 8 threads and 8 connections, each thread gets a
                // different connection via CAS on the shared list.
                pool.affinityPhaser.arriveAndAwaitAdvance();
                Connection c = pool.dataSource.getConnection();

                // Phase 1 → 2: all threads hold their connections.
                // No thread is in borrow(), so waiters == 0.
                pool.affinityPhaser.arriveAndAwaitAdvance();

                // All return simultaneously.  requite() sees waiters == 0
                // and adds each thread's unique connection to its local list.
                c.close();

                // Phase 2 → 3: wait for all returns to complete.
                pool.affinityPhaser.arriveAndAwaitAdvance();

                // Deregister so the phaser doesn't block future phases.
                pool.affinityPhaser.arriveAndDeregister();
            } else if ("sleep_only".equals(pool.prewarm)) {
                // Pure time delay — no borrow/return, no thread-local population.
                // Tests whether initialization time alone eliminates bimodal.
                // Total delay across 8 serialized threads ≈ 800ms, comparable
                // to the time force_threadlocal spends in setup.
                pool.warmGate.acquire();
                try {
                    Thread.sleep(100);
                } finally {
                    pool.warmGate.release();
                }
            } else if ("borrow_once".equals(pool.prewarm)) {
                // Minimal thread-local population: single borrow/return per thread.
                // Serialized so waiters == 0 on return → entry goes to thread-local.
                // Tests if one cycle is sufficient vs. the 10 cycles in force_threadlocal.
                pool.warmGate.acquire();
                try {
                    try (Connection c = pool.dataSource.getConnection()) {
                        // single borrow/return
                    }
                    Thread.sleep(5);
                } finally {
                    pool.warmGate.release();
                }
            }
        }

        /**
         * Iteration-level setup: optionally clear this thread's ConcurrentBag
         * thread-local list at the start of every iteration.
         */
        @Setup(Level.Iteration)
        public void flushIfRequested(PoolState pool) {
            if (!"flush_threadlocal".equals(pool.prewarm)) return;

            try {
                Object hikariPool = PoolState.getField(pool.dataSource, "pool");
                if (hikariPool == null) {
                    hikariPool = PoolState.getField(pool.dataSource, "fastPathPool");
                }
                Object bag = PoolState.getField(hikariPool, "connectionBag");

                Field threadListField = bag.getClass().getDeclaredField("threadList");
                threadListField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ThreadLocal<List<Object>> threadLocal =
                        (ThreadLocal<List<Object>>) threadListField.get(bag);
                List<Object> myList = threadLocal.get();
                if (myList != null) {
                    myList.clear();
                }
            } catch (Exception e) {
                // best-effort; don't fail the benchmark
            }
        }

        /**
         * Trial-level teardown: print this thread's ConcurrentBag thread-local
         * list size AND the identity hash codes of cached entries.  This reveals
         * whether threads have unique connections (fast path: no CAS contention)
         * or all share the same connection (slow path: 7/8 CAS failures).
         */
        @TearDown(Level.Trial)
        public void reportThreadLocalState(PoolState pool) {
            try {
                Object hikariPool = PoolState.getField(pool.dataSource, "pool");
                if (hikariPool == null) {
                    hikariPool = PoolState.getField(pool.dataSource, "fastPathPool");
                }
                Object bag = PoolState.getField(hikariPool, "connectionBag");

                Field threadListField = bag.getClass().getDeclaredField("threadList");
                threadListField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ThreadLocal<List<Object>> threadLocal =
                        (ThreadLocal<List<Object>>) threadListField.get(bag);
                List<Object> myList = threadLocal.get();

                StringBuilder sb = new StringBuilder();
                sb.append("[DIAG] ").append(Thread.currentThread().getName())
                  .append(" threadLocal.size=").append(myList != null ? myList.size() : "null");

                if (myList != null && !myList.isEmpty()) {
                    sb.append(" entries=[");
                    for (int i = 0; i < myList.size(); i++) {
                        if (i > 0) sb.append(",");
                        Object entry = myList.get(i);
                        sb.append(System.identityHashCode(entry));
                    }
                    sb.append("]");
                }

                System.err.println(sb.toString());
            } catch (Exception e) {
                System.err.println("[DIAG] Thread-local introspection failed for "
                        + Thread.currentThread().getName() + ": " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Benchmark methods
    // =========================================================================

    /**
     * <b>The bimodal case.</b>  8 threads contending for 8 connections —
     * the exact saturation point where the fast/slow mode split occurs.
     *
     * <p>Expected behavior by prewarm mode:</p>
     * <ul>
     *   <li>{@code none}: bimodal (~50% fast, ~50% slow across forks)</li>
     *   <li>{@code force_threadlocal}: always fast (~47k–52k ops/ms)</li>
     *   <li>{@code flush_threadlocal}: always slow (~13k–21k ops/ms)</li>
     * </ul>
     */
    @Benchmark
    @Threads(8)
    public void saturated_8t(PoolState pool, ThreadState ts, Blackhole bh) throws SQLException {
        try (Connection c = pool.dataSource.getConnection()) { bh.consume(c); }
    }

    /**
     * Control: single thread, always hits the thread-local fast path.
     * Expected: ~7,800 ops/ms with negligible variance.
     */
    @Benchmark
    @Threads(1)
    public void control_1t(PoolState pool, ThreadState ts, Blackhole bh) throws SQLException {
        try (Connection c = pool.dataSource.getConnection()) { bh.consume(c); }
    }

    /**
     * Control: 4 threads with 8-connection pool (below saturation).
     * Expected: ~27,000 ops/ms with no bimodal behavior.
     */
    @Benchmark
    @Threads(4)
    public void control_4t(PoolState pool, ThreadState ts, Blackhole bh) throws SQLException {
        try (Connection c = pool.dataSource.getConnection()) { bh.consume(c); }
    }

    /**
     * Over-saturation: 16 threads for 8 connections.  Always in "slow" mode
     * (excess threads always contend via the handoff queue).
     * Expected: consistent ~20k–25k ops/ms, no bimodal.
     */
    @Benchmark
    @Threads(16)
    public void oversaturated_16t(PoolState pool, ThreadState ts, Blackhole bh) throws SQLException {
        try (Connection c = pool.dataSource.getConnection()) { bh.consume(c); }
    }
}
