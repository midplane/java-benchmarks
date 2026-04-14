package com.midplane.benchmarks.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks two Jackson ObjectMapper serialization methods across varying payload sizes.
 *
 * METHODS COMPARED
 * ----------------
 * 1. writeValueAsString(obj)
 *    Internally serializes to a byte[], then constructs a new String by decoding those bytes
 *    (UTF-8). Every call allocates both the intermediate byte[] AND the final String object.
 *    The copy cost is O(payload size).
 *
 * 2. writeValueAsBytes(obj)
 *    Serializes directly to a byte[] with no further conversion. Single allocation per call.
 *
 * WHY PAYLOAD SIZE, NOT THREAD COUNT
 * -----------------------------------
 * ObjectMapper has no shared mutable state during serialization — each call gets its own
 * buffer from a ThreadLocal-backed recycler, so both methods scale linearly with threads
 * and thread count reveals nothing about their relative allocation overhead.
 * The extra cost of writeValueAsString is a byte[] -> String copy whose size is proportional
 * to the serialized payload. Varying payload size directly exercises this hypothesis.
 *
 * PAYLOAD SIZES
 * -------------
 * small  (~200 B)  — 3 line items
 * medium (~2 KB)   — 30 line items
 * large  (~20 KB)  — 300 line items
 *
 * Run:
 *   mvn package -pl benchmark-jackson
 *   java -jar benchmark-jackson/target/benchmarks.jar -rf json -rff benchmark-jackson/results.json
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class JacksonSerializationBenchmark {

    // -------------------------------------------------------------------------
    // Payload model
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
    // Benchmarks
    // -------------------------------------------------------------------------

    @Benchmark
    public void writeValueAsString(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(state.mapper.writeValueAsString(state.order));
    }

    @Benchmark
    public void writeValueAsBytes(BenchmarkState state, Blackhole bh) throws Exception {
        bh.consume(state.mapper.writeValueAsBytes(state.order));
    }
}
