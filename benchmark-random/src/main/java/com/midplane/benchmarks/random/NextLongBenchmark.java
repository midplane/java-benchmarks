package com.midplane.benchmarks.random;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks java.util.Random vs ThreadLocalRandom for nextLong() under
 * increasing thread counts.
 *
 * WHY THEY DIFFER:
 *   - Random stores its seed in an AtomicLong. Each nextLong() performs two
 *     CAS operations on that shared field. Under contention, threads spin-retry,
 *     causing throughput to collapse as thread count grows.
 *   - ThreadLocalRandom keeps per-thread seed state inside the Thread object
 *     itself (no sharing, no CAS). Throughput scales linearly with threads.
 *
 * The @Threads parameter drives JMH to run the benchmark method concurrently
 * with N threads, directly exposing the contention effect.
 *
 * Run:
 *   mvn package -pl benchmark-random
 *   java -jar benchmark-random/target/benchmarks.jar
 *
 * Or with a specific regex and explicit thread counts printed side-by-side:
 *   java -jar benchmark-random/target/benchmarks.jar "NextLongBenchmark" -rf json -rff results.json
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class NextLongBenchmark {

    /**
     * Shared Random instance — intentionally one per benchmark run to reproduce
     * real-world usage where a single Random is shared across threads.
     */
    @State(Scope.Benchmark)
    public static class SharedRandom {
        public final Random random = new Random();
    }

    // -------------------------------------------------------------------------
    // 1 thread — baseline, no contention
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(1)
    public void random_1thread(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(1)
    public void threadLocalRandom_1thread(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }

    // -------------------------------------------------------------------------
    // 2 threads
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(2)
    public void random_2threads(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(2)
    public void threadLocalRandom_2threads(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }

    // -------------------------------------------------------------------------
    // 4 threads
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(4)
    public void random_4threads(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(4)
    public void threadLocalRandom_4threads(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }

    // -------------------------------------------------------------------------
    // 8 threads
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(8)
    public void random_8threads(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(8)
    public void threadLocalRandom_8threads(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }

    // -------------------------------------------------------------------------
    // 16 threads
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(16)
    public void random_16threads(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(16)
    public void threadLocalRandom_16threads(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }

    // -------------------------------------------------------------------------
    // 32 threads
    // -------------------------------------------------------------------------

    @Benchmark
    @Threads(32)
    public void random_32threads(SharedRandom state, Blackhole bh) {
        bh.consume(state.random.nextLong());
    }

    @Benchmark
    @Threads(32)
    public void threadLocalRandom_32threads(Blackhole bh) {
        bh.consume(ThreadLocalRandom.current().nextLong());
    }
}
