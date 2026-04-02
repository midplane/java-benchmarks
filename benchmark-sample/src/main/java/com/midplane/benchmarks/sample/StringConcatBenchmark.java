package com.midplane.benchmarks.sample;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Sample benchmark comparing string concatenation approaches.
 *
 * Run with:
 *   mvn package -pl benchmark-sample
 *   java -jar benchmark-sample/target/benchmarks.jar
 *
 * Or a specific benchmark:
 *   java -jar benchmark-sample/target/benchmarks.jar StringConcatBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StringConcatBenchmark {

    @Param({"10", "100"})
    private int iterations;

    @Benchmark
    public void stringConcatenation(Blackhole bh) {
        String result = "";
        for (int i = 0; i < iterations; i++) {
            result += i;
        }
        bh.consume(result);
    }

    @Benchmark
    public void stringBuilder(Blackhole bh) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < iterations; i++) {
            sb.append(i);
        }
        bh.consume(sb.toString());
    }

    @Benchmark
    public void stringJoiner(Blackhole bh) {
        java.util.StringJoiner sj = new java.util.StringJoiner(",");
        for (int i = 0; i < iterations; i++) {
            sj.add(String.valueOf(i));
        }
        bh.consume(sj.toString());
    }
}
