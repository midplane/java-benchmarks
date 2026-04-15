package com.midplane.benchmarks.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the full pipeline of serializing an object and computing a SHA-256 hex digest,
 * comparing a String-based path against a byte[]-based path.
 *
 * MOTIVATION
 * ----------
 * In isolation writeValueAsString and writeValueAsBytes show similar throughput, because the
 * byte[]->String copy done by writeValueAsString is cheap relative to the serialization work.
 * However, if the caller then passes that String into DigestUtils.sha256Hex(String), the codec
 * library must re-encode the String back to bytes internally before it can hash it.
 *
 * The byte[] path avoids this round-trip:
 *   writeValueAsBytes -> sha256Hex(byte[])
 * while the String path pays for it twice:
 *   writeValueAsString -> sha256Hex(String)  (internally: String.getBytes() + digest)
 *
 * PATHS COMPARED
 * --------------
 * 1. stringPath  — writeValueAsString(obj)  -> DigestUtils.sha256Hex(String)
 * 2. bytesPath   — writeValueAsBytes(obj)   -> DigestUtils.sha256Hex(byte[])
 *
 * Both paths produce an identical 64-character lowercase hex String.
 *
 * DIMENSIONS
 * ----------
 * payloadSize — small (~200 B), medium (~2 KB), large (~20 KB)
 * threads     — 1, 4, 8, 16  (varied via @Param to expose GC pressure at concurrency)
 *
 * WHY THREADS AS A PARAM
 * ----------------------
 * At higher thread counts the extra String allocation in the string path creates more
 * short-lived heap objects per unit time, increasing GC pressure. This should show up
 * as reduced throughput or higher variance relative to the bytes path under contention.
 *
 * Run:
 *   mvn package -pl benchmark-jackson
 *   java -jar benchmark-jackson/target/benchmarks.jar JacksonDigestBenchmark \
 *        -rf json -rff benchmark-jackson/results-digest.json
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 60)
@Fork(1)
public class JacksonDigestBenchmark {

    // -------------------------------------------------------------------------
    // Payload model (reuses the same shape as JacksonSerializationBenchmark)
    // -------------------------------------------------------------------------

    public static class LineItem {
        public String sku;
        public int quantity;
        public double unitPrice;

        public LineItem() {}

        public LineItem(String sku, int quantity, double unitPrice) {
            this.sku = sku;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }

    public static class Order {
        public String orderId;
        public String customerId;
        public String status;
        public List<LineItem> items;

        public Order() {}

        public Order(String orderId, String customerId, String status, List<LineItem> items) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.status = status;
            this.items = items;
        }
    }

    // -------------------------------------------------------------------------
    // Benchmark state
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param({"small", "medium", "large"})
        public String payloadSize;

        public ObjectMapper mapper;
        public Order order;

        @Setup(Level.Trial)
        public void setUp() {
            mapper = new ObjectMapper();
            int itemCount = switch (payloadSize) {
                case "small"  -> 3;    // ~200 B
                case "medium" -> 30;   // ~2 KB
                case "large"  -> 300;  // ~20 KB
                default -> throw new IllegalArgumentException("Unknown payloadSize: " + payloadSize);
            };
            order = buildOrder(itemCount);
        }

        private static Order buildOrder(int itemCount) {
            List<LineItem> items = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                items.add(new LineItem(
                        String.format("SKU-%06d", i),
                        (i % 10) + 1,
                        10.0 + (i % 100) * 0.99
                ));
            }
            return new Order("ord-00123456", "cust-98765", "CONFIRMED", items);
        }
    }

    // -------------------------------------------------------------------------
    // Benchmarks — string path (serialize -> String -> sha256Hex(String))
    // DigestUtils internally calls string.getBytes(UTF_8) before hashing,
    // so the byte[] encoding happens twice (once in Jackson, once in DigestUtils).
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void stringPath_t01(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsString(state.order)));
    }

    @Benchmark @Threads(4)
    public void stringPath_t04(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsString(state.order)));
    }

    @Benchmark @Threads(8)
    public void stringPath_t08(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsString(state.order)));
    }

    @Benchmark @Threads(16)
    public void stringPath_t16(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsString(state.order)));
    }

    // -------------------------------------------------------------------------
    // Benchmarks — bytes path (serialize -> byte[] -> sha256Hex(byte[]))
    // The byte[] produced by Jackson is handed directly to DigestUtils,
    // skipping the extra String construction and re-encoding.
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void bytesPath_t01(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsBytes(state.order)));
    }

    @Benchmark @Threads(4)
    public void bytesPath_t04(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsBytes(state.order)));
    }

    @Benchmark @Threads(8)
    public void bytesPath_t08(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsBytes(state.order)));
    }

    @Benchmark @Threads(16)
    public void bytesPath_t16(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(DigestUtils.sha256Hex(state.mapper.writeValueAsBytes(state.order)));
    }
}
