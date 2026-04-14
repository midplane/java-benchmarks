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
 * - Tomcat JDBC Pool: Uses a FairBlockingQueue with a fair ReentrantLock. Dropwizard's original
 *   default pool before HikariCP replaced it in v1.3.
 *
 * SETUP
 * -----
 * All pools are configured identically:
 *   - Pool size: min=8, max=8 (fully pre-warmed — no connection creation during measurement)
 *   - Database: H2 in-memory (eliminates network variance)
 *   - No connection validation on borrow (measures pure pool acquisition overhead)
 *   - Connection timeout: 5 seconds
 *
 * Each benchmark method acquires a connection and immediately returns it (close()).
 * This is the most common pattern in a Dropwizard + Hibernate stack where Hibernate
 * acquires and releases a connection per transaction.
 *
 * THREAD COUNTS
 * -------------
 * Thread counts: 1, 4, 8, 32, 64. Pool size is fixed at 8, so:
 *   - 1–8 threads:  low-contention regime (threads <= pool size, rarely have to wait)
 *   - 32–64 threads: high-contention regime (threads >> pool size, acquisition blocks)
 *
 * RUN CONFIGURATION
 * -----------------
 * @Fork(2): each benchmark method runs in two independent JVM processes. Eliminates
 *   JIT state and GC pressure carryover between pool implementations. Results must
 *   agree across forks to be trustworthy.
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
 *   java -jar benchmark-connection-pool/target/benchmarks.jar
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ConnectionPoolBenchmark {

    private static final String JDBC_URL  = "jdbc:h2:mem:benchmark;DB_CLOSE_DELAY=-1";
    private static final String DRIVER    = "org.h2.Driver";
    private static final String USER      = "sa";
    private static final String PASSWORD  = "";
    private static final int    POOL_SIZE = 8;

    // -------------------------------------------------------------------------
    // Pool states
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class HikariState {
        HikariDataSource dataSource;

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
            // Disable connection test on borrow — measure pool overhead only
            cfg.setConnectionTestQuery(null);
            dataSource = new HikariDataSource(cfg);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            dataSource.close();
        }
    }

    @State(Scope.Benchmark)
    public static class Dbcp2State {
        BasicDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            dataSource = new BasicDataSource();
            dataSource.setUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClassName(DRIVER);
            dataSource.setInitialSize(POOL_SIZE);
            dataSource.setMinIdle(POOL_SIZE);
            dataSource.setMaxIdle(POOL_SIZE);
            dataSource.setMaxTotal(POOL_SIZE);
            dataSource.setMaxWaitMillis(5_000);
            // Disable validation on borrow
            dataSource.setTestOnBorrow(false);
            dataSource.setTestWhileIdle(false);
            // Pre-warm: borrow and return all connections to establish physical connections
            try {
                Connection[] conns = new Connection[POOL_SIZE];
                for (int i = 0; i < POOL_SIZE; i++) conns[i] = dataSource.getConnection();
                for (Connection c : conns) c.close();
            } catch (SQLException e) {
                throw new RuntimeException("DBCP2 warm-up failed", e);
            }
        }

        @TearDown(Level.Trial)
        public void teardown() throws Exception {
            dataSource.close();
        }
    }

    @State(Scope.Benchmark)
    public static class C3p0State {
        ComboPooledDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataSource = new ComboPooledDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUser(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setDriverClass(DRIVER);
            dataSource.setInitialPoolSize(POOL_SIZE);
            dataSource.setMinPoolSize(POOL_SIZE);
            dataSource.setMaxPoolSize(POOL_SIZE);
            dataSource.setCheckoutTimeout(5_000);
            // Disable connection testing
            dataSource.setTestConnectionOnCheckout(false);
            dataSource.setTestConnectionOnCheckin(false);
            dataSource.setIdleConnectionTestPeriod(0);
            // Pre-warm
            Connection[] conns = new Connection[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            dataSource.close();
        }
    }

    @State(Scope.Benchmark)
    public static class ViburState {
        ViburDBCPDataSource dataSource;

        @Setup(Level.Trial)
        public void setup() {
            dataSource = new ViburDBCPDataSource();
            dataSource.setJdbcUrl(JDBC_URL);
            dataSource.setUsername(USER);
            dataSource.setPassword(PASSWORD);
            dataSource.setPoolInitialSize(POOL_SIZE);
            dataSource.setPoolMaxSize(POOL_SIZE);
            dataSource.setConnectionTimeoutInMs(5_000);
            // Disable connection validation (connectionIdleLimitInSeconds=-1 disables idle testing)
            dataSource.setTestConnectionQuery("isValid");
            dataSource.setConnectionIdleLimitInSeconds(-1);
            dataSource.start();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            dataSource.terminate();
        }
    }

    @State(Scope.Benchmark)
    public static class TomcatState {
        DataSource dataSource;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            PoolProperties p = new PoolProperties();
            p.setUrl(JDBC_URL);
            p.setUsername(USER);
            p.setPassword(PASSWORD);
            p.setDriverClassName(DRIVER);
            p.setInitialSize(POOL_SIZE);
            p.setMinIdle(POOL_SIZE);
            p.setMaxIdle(POOL_SIZE);
            p.setMaxActive(POOL_SIZE);
            p.setMaxWait(5_000);
            p.setFairQueue(true);
            // Disable connection testing
            p.setTestOnBorrow(false);
            p.setTestWhileIdle(false);
            dataSource = new DataSource(p);
            dataSource.createPool();
            // Pre-warm
            Connection[] conns = new Connection[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) conns[i] = dataSource.getConnection();
            for (Connection c : conns) c.close();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            dataSource.close();
        }
    }

    // -------------------------------------------------------------------------
    // 1 thread
    // -------------------------------------------------------------------------

    @Benchmark @Threads(1)
    public void hikari_1thread(HikariState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void dbcp2_1thread(Dbcp2State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void c3p0_1thread(C3p0State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void vibur_1thread(ViburState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(1)
    public void tomcat_1thread(TomcatState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    // -------------------------------------------------------------------------
    // 4 threads
    // -------------------------------------------------------------------------

    @Benchmark @Threads(4)
    public void hikari_4threads(HikariState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void dbcp2_4threads(Dbcp2State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void c3p0_4threads(C3p0State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void vibur_4threads(ViburState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(4)
    public void tomcat_4threads(TomcatState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    // -------------------------------------------------------------------------
    // 8 threads  (== pool size: saturation point)
    // -------------------------------------------------------------------------

    @Benchmark @Threads(8)
    public void hikari_8threads(HikariState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void dbcp2_8threads(Dbcp2State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void c3p0_8threads(C3p0State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void vibur_8threads(ViburState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(8)
    public void tomcat_8threads(TomcatState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    // -------------------------------------------------------------------------
    // 32 threads  (4× pool size: heavy contention)
    // -------------------------------------------------------------------------

    @Benchmark @Threads(32)
    public void hikari_32threads(HikariState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void dbcp2_32threads(Dbcp2State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void c3p0_32threads(C3p0State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void vibur_32threads(ViburState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(32)
    public void tomcat_32threads(TomcatState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    // -------------------------------------------------------------------------
    // 64 threads  (8× pool size: extreme contention)
    // -------------------------------------------------------------------------

    @Benchmark @Threads(64)
    public void hikari_64threads(HikariState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void dbcp2_64threads(Dbcp2State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void c3p0_64threads(C3p0State s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void vibur_64threads(ViburState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }

    @Benchmark @Threads(64)
    public void tomcat_64threads(TomcatState s, Blackhole bh) throws SQLException {
        try (Connection c = s.dataSource.getConnection()) { bh.consume(c); }
    }
}
