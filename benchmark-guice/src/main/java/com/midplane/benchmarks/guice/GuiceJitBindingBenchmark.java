package com.midplane.benchmarks.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provider;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks Guice JIT (Just-In-Time) binding contention under concurrent access.
 *
 * BACKGROUND
 * ----------
 * Guice's InjectorImpl.getJustInTimeBinding() acquires a synchronized lock on
 * InjectorJitBindingData every time it resolves a JIT binding. This means every
 * call to injector.getInstance() for a class without an explicit pre-built binding
 * hits this global lock.
 *
 * In production, this was observed to cause:
 * - 926 contention events
 * - 181 seconds of total blocked time (13% of total)
 *
 * STRATEGIES COMPARED
 * -------------------
 * 1. getInstance_jit: Direct injector.getInstance() with JIT binding (contended)
 *    - Every call acquires synchronized lock on InjectorJitBindingData
 *    - This is the problematic pattern found in production
 *
 * 2. getInstance_explicit: injector.getInstance() with explicit module binding
 *    - Binding is resolved at injector creation time
 *    - No JIT binding lock needed at runtime
 *
 * 3. cachedProvider: Provider cached in ConcurrentHashMap, then provider.get()
 *    - First call per class resolves provider and caches it
 *    - Subsequent calls use cached provider (no Guice lock)
 *    - Recommended fix for existing code
 *
 * 4. directProvider: Pre-resolved provider at setup time
 *    - Provider resolved once during @Setup
 *    - Represents the ideal "resolve at startup" pattern
 *
 * SIMULATED SCENARIO
 * ------------------
 * This benchmark simulates the SimpleBuilderFactory.create() pattern where
 * injector.getInstance() is called for different builder classes on every request.
 *
 * Run:
 *   mvn package -pl benchmark-guice
 *   java -jar benchmark-guice/target/benchmarks.jar
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GuiceJitBindingBenchmark {

    // Distinct types to simulate different DataBuilder classes (no shared logic needed)
    public static class ServiceA {}
    public static class ServiceB {}
    public static class ServiceC {}
    public static class ServiceD {}
    public static class ServiceE {}
    public static class ServiceF {}
    public static class ServiceG {}
    public static class ServiceH {}

    @SuppressWarnings("unchecked")
    private static final Class<?>[] SERVICE_CLASSES = {
        ServiceA.class, ServiceB.class, ServiceC.class, ServiceD.class,
        ServiceE.class, ServiceF.class, ServiceG.class, ServiceH.class
    };

    @State(Scope.Benchmark)
    public static class JitInjectorState {
        Injector injector;

        @Setup(Level.Trial)
        public void setup() {
            injector = Guice.createInjector();
        }
    }

    @State(Scope.Benchmark)
    public static class ExplicitInjectorState {
        Injector injector;

        @Setup(Level.Trial)
        public void setup() {
            injector = Guice.createInjector(new AbstractModule() {
                @Override
                protected void configure() {
                    for (Class<?> clazz : SERVICE_CLASSES) {
                        bind((Class<Object>) clazz);
                    }
                }
            });
        }
    }

    @State(Scope.Benchmark)
    public static class CachedProviderState {
        Injector injector;
        ConcurrentHashMap<Class<?>, Provider<?>> providerCache;

        @Setup(Level.Trial)
        public void setup() {
            injector = Guice.createInjector();
            providerCache = new ConcurrentHashMap<>();
        }

        @SuppressWarnings("unchecked")
        public <T> T getInstance(Class<T> clazz) {
            return (T) providerCache.computeIfAbsent(clazz, injector::getProvider).get();
        }
    }

    @State(Scope.Benchmark)
    public static class DirectProviderState {
        Provider<?>[] providers;

        @Setup(Level.Trial)
        public void setup() {
            Injector injector = Guice.createInjector();
            providers = new Provider[SERVICE_CLASSES.length];
            for (int i = 0; i < SERVICE_CLASSES.length; i++) {
                providers[i] = injector.getProvider(SERVICE_CLASSES[i]);
            }
        }

        public Object getByIndex(int index) {
            return providers[index % providers.length].get();
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {
        int counter = 0;

        public int nextIndex() {
            return counter++ % SERVICE_CLASSES.length;
        }
    }

    // ---- 1 thread ----

    @Benchmark @Threads(1)
    public void getInstance_jit_1thread(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(1)
    public void getInstance_explicit_1thread(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(1)
    public void cachedProvider_1thread(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(1)
    public void directProvider_1thread(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }

    // ---- 2 threads ----

    @Benchmark @Threads(2)
    public void getInstance_jit_2threads(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(2)
    public void getInstance_explicit_2threads(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(2)
    public void cachedProvider_2threads(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(2)
    public void directProvider_2threads(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }

    // ---- 4 threads ----

    @Benchmark @Threads(4)
    public void getInstance_jit_4threads(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(4)
    public void getInstance_explicit_4threads(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(4)
    public void cachedProvider_4threads(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(4)
    public void directProvider_4threads(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }

    // ---- 8 threads ----

    @Benchmark @Threads(8)
    public void getInstance_jit_8threads(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(8)
    public void getInstance_explicit_8threads(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(8)
    public void cachedProvider_8threads(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(8)
    public void directProvider_8threads(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }

    // ---- 16 threads ----

    @Benchmark @Threads(16)
    public void getInstance_jit_16threads(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(16)
    public void getInstance_explicit_16threads(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(16)
    public void cachedProvider_16threads(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(16)
    public void directProvider_16threads(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }

    // ---- 32 threads ----

    @Benchmark @Threads(32)
    public void getInstance_jit_32threads(JitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(32)
    public void getInstance_explicit_32threads(ExplicitInjectorState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.injector.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(32)
    public void cachedProvider_32threads(CachedProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getInstance(SERVICE_CLASSES[ts.nextIndex()]));
    }

    @Benchmark @Threads(32)
    public void directProvider_32threads(DirectProviderState state, ThreadState ts, Blackhole bh) {
        bh.consume(state.getByIndex(ts.nextIndex()));
    }
}
