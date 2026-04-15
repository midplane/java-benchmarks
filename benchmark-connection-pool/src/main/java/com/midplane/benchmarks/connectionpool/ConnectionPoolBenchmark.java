package com.midplane.benchmarks.connectionpool;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.vibur.dbcp.ViburDBCPDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks JDBC connection pool throughput and acquisition latency under concurrent load.
 *
 * BACKGROUND
 * ----------
 * Connection pool implementations differ significantly in their internal concurrency design:
 *
 * - HikariCP: Uses a custom lock-free ConcurrentBag with thread-local biased connection lists.
 *   A thread first checks its own local list before falling back to a shared transfer queue,
 *   avoiding CAS contention in the common case.
 *
 * - Apache DBCP2: Uses a GenericObjectPool backed by a LinkedBlockingDeque. Connection
 *   acquisition acquires a lock on the deque, which becomes a bottleneck under high contention.
 *
 * - c3p0: Uses a synchronized queue (FIFO) protected by a coarse-grained lock. Every
 *   getConnection() acquires this lock, similar to the Guice JIT binding pattern already
 *   benchmarked in this repo.
 *
 * - Vibur DBCP: Uses a ConcurrentLinkedDeque with a Semaphore to bound concurrent acquisitions.
 *   Claims a near-lock-free acquisition path.
 *
 * - Tomcat JDBC Pool: Uses a FairBlockingQueue (unfair mode used here for best performance)
 *   with a ReentrantLock. Dropwizard's original default pool before HikariCP replaced it in v1.3.
 *
 * SETUP
 * -----
 * All pools are configured identically for best possible performance:
 *   - Pool size: parameterized (4 and 8), min=max (fully pre-warmed — no connection creation
 *     during measurement)
 *   - Database: H2 in-memory (eliminates network variance)
 *   - No connection validation on borrow (measures pure pool acquisition overhead)
 *   - Connection timeout: 5 seconds
 *   - Tomcat: fairQueue=false (avoids FIFO convoy effect; tests best-case performance)
 *   - Vibur: testConnectionQuery="" (disables per-borrow isValid() call)
 *
 * Each benchmark method acquires a connection and immediately returns it (close()).
 * This is the most common pattern in a Dropwizard + Hibernate stack where Hibernate
 * acquires and releases a connection per transaction.
 *
 * POOL SIZE DIMENSION
 * -------------------
 * Pool sizes 4 and 8 are benchmarked, producing two saturation points per thread count:
 *   - pool=4: saturates at 4 threads
 *   - pool=8: saturates at 8 threads
 * This reveals how each pool's acquisition algorithm behaves as the pool/thread ratio shifts.
 *
 * THREAD COUNTS
 * -------------
 * Thread counts: 1, 4, 8, 32, 64. Combined with pool sizes:
 *   - threads <= pool size:  low-contention regime (threads rarely have to wait)
 *   - threads >> pool size:  high-contention regime (acquisition blocks)
 *
 * RUN CONFIGURATION
 * -----------------
 * @Fork(5): each benchmark method runs in five independent JVM processes. With only
 *   2 forks, HikariCP's ConcurrentBag exhibits a JIT compilation lottery: the JVM
 *   sometimes compiles the thread-local fast path hot and sometimes the SynchronousQueue
 *   handoff path, producing two completely different stable modes across forks (e.g.
 *   ~20k ops/ms vs ~51k ops/ms for p8_hikari_8threads). 5 forks gives a proper
 *   distribution of how often each mode is reached, making the variance interpretable
 *   rather than just noisy.
 *
 * @Warmup(iterations=5, time=2): longer warmup lets HikariCP's ConcurrentBag
 *   thread-local lists stabilize and ensures all pool lock fast paths are JIT-compiled
 *   before measurement begins.
 *
 * @Measurement(iterations=5, time=2): 2-second windows reduce the impact of a single
 *   unlucky OS scheduling event on the score, especially important at 32–64 threads
 *   where many threads spend time blocked waiting for a connection.
 *
 * Run:
 *   mvn package -pl benchmark-connection-pool
 *   java -jar benchmark-connection-pool/target/benchmarks.jar -rf json -rff benchmark-connection-pool/results.json
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(5)
public class ConnectionPoolBenchmark {

    private static final String JDBC_URL = "jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1";
    private static final String DRIVER   = "org.h2.Driver";
    private static final String USER     = "sa";
    private static final String PASSWORD = "";

    // -------------------------------------------------------------------------
    // Pool states — pool size 4
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class HikariState4 {
        static final int SIZE = 4;
        HikariDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(JDBC_URL);
            cfg.setUsername(USER);
            cfg.setPassword(PASSWORD);
            cfg.setDriverClassName(DRIVER);
            cfg.setMinimumIdle(SIZE);
            cfg.setMaximumPoolSize(SIZE);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(600_000);
            cfg.setConnectionTestQuery(null);
            dataSource = new HikariDataSource(cfg);
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class Dbcp2State4 {
        static final int SIZE = 4;
        BasicDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            dataSource = new BasicDataSource();
            dataSource.setUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClassName(DRIVER);
            dataSource.setInitialSize(SIZE);
            dataSource.setMinIdle(SIZE);
            dataSource.setMaxIdle(SIZE);
            dataSource.setMaxTotal(SIZE);
            dataSource.setMaxWaitMillis(5_000);
            dataSource.setTestOnBorrow(false);
            dataSource.setTestWhileIdle(false);
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() throws Exception { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class C3p0State4 {
        static final int SIZE = 4;
        ComboPooledDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataSource = new ComboPooledDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUser(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClass(DRIVER);
            dataSource.setInitialPoolSize(SIZE);
            dataSource.setMinPoolSize(SIZE);
            dataSource.setMaxPoolSize(SIZE);
            dataSource.setCheckoutTimeout(5_000);
            dataSource.setTestConnectionOnCheckout(false);
            dataSource.setTestConnectionOnCheckin(false);
            dataSource.setIdleConnectionTestPeriod(0);
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class ViburState4 {
        static final int SIZE = 4;
        ViburDBCPDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            dataSource = new ViburDBCPDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setPoolInitialSize(SIZE);
            dataSource.setPoolMaxSize(SIZE);
            dataSource.setConnectionTimeoutInMs(5_000);
            // Empty string disables per-borrow isValid() validation
            dataSource.setTestConnectionQuery("");
            dataSource.setConnectionIdleLimitInSeconds(-1);
            dataSource.start();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.terminate(); }
    }

    @State(Scope.Benchmark)
    public static class TomcatState4 {
        static final int SIZE = 4;
        DataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            PoolProperties p = new PoolProperties();
            p.setUrl(JDBC_URL);
            p.setUsername(USER);
            p.setPassword(PASSWORD);
            p.setDriverClassName(DRIVER);
            p.setInitialSize(SIZE);
            p.setMinIdle(SIZE);
            p.setMaxIdle(SIZE);
            p.setMaxActive(SIZE);
            p.setMaxWait(5_000);
            // fairQueue=false: avoids FIFO convoy effect; tests best-case performance
            p.setFairQueue(false);
            p.setTestOnBorrow(false);
            p.setTestWhileIdle(false);
            dataSource = new DataSource(p);
            dataSource.createPool();
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    // -------------------------------------------------------------------------
    // Pool states — pool size 8
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class HikariState8 {
        static final int SIZE = 8;
        HikariDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(JDBC_URL);
            cfg.setUsername(USER);
            cfg.setPassword(PASSWORD);
            cfg.setDriverClassName(DRIVER);
            cfg.setMinimumIdle(SIZE);
            cfg.setMaximumPoolSize(SIZE);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(600_000);
            cfg.setConnectionTestQuery(null);
            dataSource = new HikariDataSource(cfg);
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class Dbcp2State8 {
        static final int SIZE = 8;
        BasicDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            dataSource = new BasicDataSource();
            dataSource.setUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClassName(DRIVER);
            dataSource.setInitialSize(SIZE);
            dataSource.setMinIdle(SIZE);
            dataSource.setMaxIdle(SIZE);
            dataSource.setMaxTotal(SIZE);
            dataSource.setMaxWaitMillis(5_000);
            dataSource.setTestOnBorrow(false);
            dataSource.setTestWhileIdle(false);
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() throws Exception { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class C3p0State8 {
        static final int SIZE = 8;
        ComboPooledDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataSource = new ComboPooledDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUser(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClass(DRIVER);
            dataSource.setInitialPoolSize(SIZE);
            dataSource.setMinPoolSize(SIZE);
            dataSource.setMaxPoolSize(SIZE);
            dataSource.setCheckoutTimeout(5_000);
            dataSource.setTestConnectionOnCheckout(false);
            dataSource.setTestConnectionOnCheckin(false);
            dataSource.setIdleConnectionTestPeriod(0);
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    @State(Scope.Benchmark)
    public static class ViburState8 {
        static final int SIZE = 8;
        ViburDBCPDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            dataSource = new ViburDBCPDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setPoolInitialSize(SIZE);
            dataSource.setPoolMaxSize(SIZE);
            dataSource.setConnectionTimeoutInMs(5_000);
            // Empty string disables per-borrow isValid() validation
            dataSource.setTestConnectionQuery("");
            dataSource.setConnectionIdleLimitInSeconds(-1);
            dataSource.start();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.terminate(); }
    }

    @State(Scope.Benchmark)
    public static class TomcatState8 {
        static final int SIZE = 8;
        DataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            PoolProperties p = new PoolProperties();
            p.setUrl(JDBC_URL);
            p.setUsername(USER);
            p.setPassword(PASSWORD);
            p.setDriverClassName(DRIVER);
            p.setInitialSize(SIZE);
            p.setMinIdle(SIZE);
            p.setMaxIdle(SIZE);
            p.setMaxActive(SIZE);
            p.setMaxWait(5_000);
            // fairQueue=false: avoids FIFO convoy effect; tests best-case performance
            p.setFairQueue(false);
            p.setTestOnBorrow(false);
            p.setTestWhileIdle(false);
            dataSource = new DataSource(p);
            dataSource.createPool();
            Connection[] conns = new Connection[SIZE];
            for (int i = 0; i < SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() { dataSource.close(); }
    }

    // -------------------------------------------------------------------------
    // Benchmarks — pool size 4
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void p4_hikari_1thread(HikariState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p4_dbcp2_1thread(Dbcp2State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p4_c3p0_1thread(C3p0State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p4_vibur_1thread(ViburState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p4_tomcat_1thread(TomcatState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p4_hikari_4threads(HikariState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p4_dbcp2_4threads(Dbcp2State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p4_c3p0_4threads(C3p0State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p4_vibur_4threads(ViburState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p4_tomcat_4threads(TomcatState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p4_hikari_8threads(HikariState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p4_dbcp2_8threads(Dbcp2State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p4_c3p0_8threads(C3p0State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p4_vibur_8threads(ViburState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p4_tomcat_8threads(TomcatState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p4_hikari_32threads(HikariState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p4_dbcp2_32threads(Dbcp2State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p4_c3p0_32threads(C3p0State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p4_vibur_32threads(ViburState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p4_tomcat_32threads(TomcatState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p4_hikari_64threads(HikariState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p4_dbcp2_64threads(Dbcp2State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p4_c3p0_64threads(C3p0State4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p4_vibur_64threads(ViburState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p4_tomcat_64threads(TomcatState4 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    // -------------------------------------------------------------------------
    // Benchmarks — pool size 8
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void p8_hikari_1thread(HikariState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p8_dbcp2_1thread(Dbcp2State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p8_c3p0_1thread(C3p0State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p8_vibur_1thread(ViburState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void p8_tomcat_1thread(TomcatState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p8_hikari_4threads(HikariState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p8_dbcp2_4threads(Dbcp2State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p8_c3p0_4threads(C3p0State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p8_vibur_4threads(ViburState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void p8_tomcat_4threads(TomcatState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p8_hikari_8threads(HikariState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p8_dbcp2_8threads(Dbcp2State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p8_c3p0_8threads(C3p0State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p8_vibur_8threads(ViburState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void p8_tomcat_8threads(TomcatState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p8_hikari_32threads(HikariState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p8_dbcp2_32threads(Dbcp2State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p8_c3p0_32threads(C3p0State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p8_vibur_32threads(ViburState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void p8_tomcat_32threads(TomcatState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p8_hikari_64threads(HikariState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p8_dbcp2_64threads(Dbcp2State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p8_c3p0_64threads(C3p0State8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p8_vibur_64threads(ViburState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void p8_tomcat_64threads(TomcatState8 s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }
}
