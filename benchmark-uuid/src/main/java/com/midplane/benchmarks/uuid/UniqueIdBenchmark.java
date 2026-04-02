package com.midplane.benchmarks.uuid;

import com.github.f4b6a3.ulid.UlidCreator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks three unique-ID strategies under increasing thread counts.
 * Crypto-security is explicitly NOT a goal — we care only about uniqueness
 * and throughput.
 *
 * STRATEGIES COMPARED
 * -------------------
 * 1. UUID.randomUUID().toString()
 *    Standard JDK approach. Internally calls SecureRandom, which uses a
 *    global lock (or per-thread entropy pool depending on JVM/OS). Under
 *    contention this is the slowest option.
 *
 * 2. fastUuid (ThreadLocalRandom-backed UUID v4)
 *    Constructs a UUID from two ThreadLocalRandom longs, then forces the
 *    version (4) and variant bits manually — identical bit layout to a v4
 *    UUID, but without SecureRandom. No lock, no contention.
 *    Trade-off: NOT cryptographically secure.
 *
 * 3. ULID (ulid-creator, monotonic)
 *    128-bit, lexicographically sortable: 48-bit ms timestamp + 80-bit
 *    random component. UlidCreator.getMonotonicUlid() is thread-safe and
 *    uses ThreadLocalRandom internally. Returns a value object whose
 *    toString() is a 26-char Crockford Base32 string.
 *
 * Run:
 *   mvn package -pl benchmark-uuid
 *   java -jar benchmark-uuid/target/benchmarks.jar
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class UniqueIdBenchmark {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a UUID v4 string using ThreadLocalRandom instead of SecureRandom.
     * Bit manipulation mirrors what UUID(long,long) does internally.
     */
    private static String fastUuidString() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long msb = rng.nextLong();
        long lsb = rng.nextLong();
        // version 4
        msb = (msb & 0xffffffffffff0fffL) | 0x0000000000004000L;
        // variant bits (RFC 4122)
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    // -------------------------------------------------------------------------
    // 1 thread
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void uuid_randomUUID_1thread(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(1)
    public void uuid_fastUuid_1thread(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(1)
    public void ulid_monotonic_1thread(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }

    // -------------------------------------------------------------------------
    // 2 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(2)
    public void uuid_randomUUID_2threads(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(2)
    public void uuid_fastUuid_2threads(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(2)
    public void ulid_monotonic_2threads(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }

    // -------------------------------------------------------------------------
    // 4 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(4)
    public void uuid_randomUUID_4threads(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(4)
    public void uuid_fastUuid_4threads(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(4)
    public void ulid_monotonic_4threads(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }

    // -------------------------------------------------------------------------
    // 8 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(8)
    public void uuid_randomUUID_8threads(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(8)
    public void uuid_fastUuid_8threads(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(8)
    public void ulid_monotonic_8threads(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }

    // -------------------------------------------------------------------------
    // 16 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(16)
    public void uuid_randomUUID_16threads(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(16)
    public void uuid_fastUuid_16threads(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(16)
    public void ulid_monotonic_16threads(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }

    // -------------------------------------------------------------------------
    // 32 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(32)
    public void uuid_randomUUID_32threads(Blackhole bh) {
        bh.consume(UUID.randomUUID().toString());
    }

    @Benchmark @Threads(32)
    public void uuid_fastUuid_32threads(Blackhole bh) {
        bh.consume(fastUuidString());
    }

    @Benchmark @Threads(32)
    public void ulid_monotonic_32threads(Blackhole bh) {
        bh.consume(UlidCreator.getMonotonicUlid().toString());
    }
}
