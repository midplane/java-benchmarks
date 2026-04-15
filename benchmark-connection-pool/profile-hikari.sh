#!/usr/bin/env bash
#
# profile-hikari.sh — Profiling toolkit for HikariCP's bimodal behavior
#
# Orchestrates multiple JMH runs with different JVM flags and profiler
# configurations to root-cause the bimodal throughput distribution observed
# at the pool saturation point (threads == pool size).
#
# Thinking like Brendan Gregg: observe first, hypothesize, experiment, prove.
#
# Usage: ./profile-hikari.sh <mode> [FORKS=N]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BENCHMARK_JAR="$SCRIPT_DIR/target/benchmarks.jar"
OUTPUT_DIR="$SCRIPT_DIR/profiling"
BENCHMARK_CLASS="HikariProfilingBenchmark"
FORKS=${FORKS:-10}

# ---------------------------------------------------------------------------
# Color output
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC} $*"; }
err()     { echo -e "${RED}[ERR ]${NC} $*" >&2; }
header()  { echo -e "\n${BOLD}════════════════════════════════════════════════════════════════${NC}"; echo -e "${BOLD}  $*${NC}"; echo -e "${BOLD}════════════════════════════════════════════════════════════════${NC}\n"; }

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

ensure_jar() {
    if [[ ! -f "$BENCHMARK_JAR" ]]; then
        info "Benchmark jar not found. Building..."
        cmd_build
    fi
}

# Extract the mean throughput score from a JMH JSON result file.
extract_score() {
    local json_file="$1"
    python3 -c "
import json, sys
with open('$json_file') as f:
    data = json.load(f)
for entry in data:
    raw = entry['primaryMetric'].get('rawData', [[]])
    if raw and raw[0]:
        print(f'{sum(raw[0])/len(raw[0]):.0f}')
    else:
        print(f'{entry[\"primaryMetric\"][\"score\"]:.0f}')
" 2>/dev/null || echo "???"
}

# Run a single JMH fork and return the result file path.
run_single_fork() {
    local json_file="$1"
    local log_file="$2"
    local benchmark="$3"
    local prewarm="$4"
    local jvm_args="${5:-}"
    local jmh_extra="${6:-}"
    local wi="${7:-5}"

    local cmd=(java -jar "$BENCHMARK_JAR"
        "$BENCHMARK_CLASS.$benchmark"
        -p "prewarm=$prewarm"
        -f 1 -wi "$wi" -w 2s -i 5 -r 2s
        -rf json -rff "$json_file")

    if [[ -n "$jvm_args" ]]; then
        cmd+=(-jvmArgs "$jvm_args")
    fi

    # shellcheck disable=SC2206
    if [[ -n "$jmh_extra" ]]; then
        cmd+=($jmh_extra)
    fi

    "${cmd[@]}" > "$log_file" 2>&1
}

# Run N forks of a benchmark configuration and classify the results.
run_forks() {
    local experiment="$1"
    local benchmark="$2"
    local prewarm="${3:-none}"
    local jvm_args="${4:-}"
    local jmh_extra="${5:-}"
    local num_forks="${6:-$FORKS}"
    local wi="${7:-5}"
    local dir="$OUTPUT_DIR/$experiment"

    mkdir -p "$dir"
    info "Running $num_forks forks: experiment=$experiment benchmark=$benchmark prewarm=$prewarm"
    [[ -n "$jvm_args" ]] && info "  JVM args: $jvm_args"
    [[ -n "$jmh_extra" ]] && info "  JMH extra: $jmh_extra"
    [[ "$wi" != "5" ]] && info "  Warmup iterations: $wi"
    echo ""

    for fork in $(seq 1 "$num_forks"); do
        local json_file="$dir/fork_${fork}.json"
        local log_file="$dir/fork_${fork}.log"

        printf "  Fork %2d/%d: " "$fork" "$num_forks"

        if run_single_fork "$json_file" "$log_file" "$benchmark" "$prewarm" "$jvm_args" "$jmh_extra" "$wi"; then
            local score
            score=$(extract_score "$json_file")
            echo "${score} ops/ms"
        else
            err "FAILED (see $log_file)"
        fi
    done

    echo ""
    python3 "$SCRIPT_DIR/scripts/classify-forks.py" "$dir"

    # Also extract [DIAG] lines from the last fork for quick inspection
    local last_log="$dir/fork_${num_forks}.log"
    if [[ -f "$last_log" ]] && grep -q '\[DIAG\]' "$last_log" 2>/dev/null; then
        echo ""
        info "Diagnostic output from last fork:"
        grep '\[DIAG\]' "$last_log" | head -20 | sed 's/^/  /'
    fi
}

# ---------------------------------------------------------------------------
# Experiment modes
# ---------------------------------------------------------------------------

cmd_build() {
    info "Building benchmark jar..."
    mvn -f "$PROJECT_ROOT/pom.xml" package -pl benchmark-connection-pool -q
    ok "Built: $BENCHMARK_JAR"
}

cmd_baseline() {
    header "BASELINE: Reproduce bimodal distribution"
    info "Natural warmup, no special flags."
    info "Expect: mix of fast (~47k–52k) and slow (~13k–21k) forks."
    echo ""
    ensure_jar
    run_forks "baseline" "saturated_8t" "none"
}

cmd_force_threadlocal() {
    header "HYPOTHESIS TEST: Force thread-local population (serialized)"
    info "Pre-warm each thread's ConcurrentBag thread-local list via serialized"
    info "per-thread setup (waiters == 0 on each return)."
    info ""
    info "CAVEAT: serialized setup means all threads cache the SAME connection"
    info "(shared list index 0). This tests population, NOT affinity."
    info ""
    info "If ALL forks are fast → population alone is sufficient."
    info "If bimodal persists  → affinity (unique entries) is needed."
    echo ""
    ensure_jar
    run_forks "force-threadlocal" "saturated_8t" "force_threadlocal"
}

cmd_force_affinity() {
    header "HYPOTHESIS TEST: Force 1:1 thread-to-connection affinity"
    info "Coordinated parallel warmup: all threads borrow simultaneously"
    info "(each gets a different connection), hold via Phaser barrier, then"
    info "return simultaneously. Each thread's local list gets a UNIQUE connection."
    info ""
    info "If ALL forks are fast → thread-local AFFINITY is the root cause."
    info "If bimodal persists  → affinity is not the differentiator."
    echo ""
    ensure_jar
    run_forks "force-affinity" "saturated_8t" "force_affinity"
}

cmd_sleep_only() {
    header "CONTROL: Sleep-only prewarm (no borrow/return)"
    info "Each thread sleeps 100ms in serialized setup (total ~800ms)."
    info "NO borrow/return — thread-local lists remain EMPTY."
    info ""
    info "If bimodal disappears → initialization time is the factor, not"
    info "                        thread-local population."
    info "If bimodal persists   → thread-local population matters, not just time."
    echo ""
    ensure_jar
    run_forks "sleep-only" "saturated_8t" "sleep_only"
}

cmd_borrow_once() {
    header "CONTROL: Single borrow/return per thread (minimal population)"
    info "Each thread does exactly 1 borrow/return (serialized, waiters==0)."
    info "Thread-local list gets 1 entry. Tests if one cycle is sufficient."
    info ""
    info "If unimodal → even minimal thread-local population eliminates bimodal."
    info "If bimodal  → more warmup cycles needed."
    echo ""
    ensure_jar
    run_forks "borrow-once" "saturated_8t" "borrow_once"
}

cmd_flush_threadlocal() {
    header "NEGATIVE CONTROL: Flush thread-local lists every iteration"
    info "Clear each thread's ConcurrentBag thread-local list at the start"
    info "of every measurement iteration via reflection."
    info ""
    info "If ALL forks are slow → thread-local list is the differentiator."
    info "If some are fast      → system recovers within a 2s iteration."
    echo ""
    ensure_jar
    run_forks "flush-threadlocal" "saturated_8t" "flush_threadlocal"
}

cmd_c1_only() {
    header "HYPOTHESIS TEST: Disable C2 compiler"
    info "Running with -XX:TieredStopAtLevel=1 (C1 only, no C2 optimizations)."
    info ""
    info "If bimodal disappears → C2 compilation is a necessary condition."
    info "If bimodal persists   → C2 is not the cause."
    echo ""
    ensure_jar
    run_forks "c1-only" "saturated_8t" "none" "-XX:TieredStopAtLevel=1"
}

cmd_no_osr() {
    header "HYPOTHESIS TEST: Disable on-stack replacement"
    info "Running with -XX:-UseOnStackReplacement."
    info "OSR can cause non-deterministic optimization of hot loops."
    info ""
    info "If bimodal disappears → OSR is the non-deterministic factor."
    info "If bimodal persists   → OSR is not involved."
    echo ""
    ensure_jar
    run_forks "no-osr" "saturated_8t" "none" "-XX:-UseOnStackReplacement"
}

cmd_jit_log() {
    header "JIT COMPILATION LOG: Capture per-fork compilation decisions"
    info "Capturing -XX:+PrintCompilation -XX:+PrintInlining per fork."
    info "Compare fast vs slow forks to find the JIT divergence point."
    echo ""
    ensure_jar
    run_forks "jit-log" "saturated_8t" "none" \
        "-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining"

    echo ""
    info "JIT logs captured in $OUTPUT_DIR/jit-log/"
    info ""
    info "To compare ConcurrentBag compilation between fast and slow forks:"
    info "  grep 'ConcurrentBag' $OUTPUT_DIR/jit-log/fork_<FAST>.log | sort > /tmp/fast_jit.txt"
    info "  grep 'ConcurrentBag' $OUTPUT_DIR/jit-log/fork_<SLOW>.log | sort > /tmp/slow_jit.txt"
    info "  diff /tmp/fast_jit.txt /tmp/slow_jit.txt"
    info ""
    info "Key methods to look for:"
    info "  com.zaxxer.hikari.util.ConcurrentBag::borrow"
    info "  com.zaxxer.hikari.util.ConcurrentBag::requite"
    info "  com.zaxxer.hikari.util.FastList::get"
    info "  java.util.concurrent.SynchronousQueue::poll"
}

cmd_warmup_sweep() {
    header "WARMUP SWEEP: Does longer warmup eliminate the bimodal?"
    info "Varying warmup iterations: 1, 2, 5, 10, 20, 50."
    info "If longer warmup converges → warmup duration is critical."
    info "If bimodal persists at all levels → warmup length is not the cause."
    echo ""
    ensure_jar

    local forks_per_wi=5
    for wi in 1 2 5 10 20 50; do
        echo ""
        info "--- Warmup iterations: $wi ---"
        local dir="$OUTPUT_DIR/warmup-sweep/wi_${wi}"
        mkdir -p "$dir"

        for fork in $(seq 1 "$forks_per_wi"); do
            local json_file="$dir/fork_${fork}.json"
            local log_file="$dir/fork_${fork}.log"

            printf "  wi=%2d fork %d/%d: " "$wi" "$fork" "$forks_per_wi"

            if run_single_fork "$json_file" "$log_file" "saturated_8t" "none" "" "" "$wi"; then
                local score
                score=$(extract_score "$json_file")
                echo "${score} ops/ms"
            else
                err "FAILED"
            fi
        done

        python3 "$SCRIPT_DIR/scripts/classify-forks.py" "$dir"
    done
}

cmd_flame() {
    header "FLAME GRAPHS: async-profiler per fork"
    info "Requires async-profiler. Run on Linux for best results."
    info "Each fork gets its own flame graph SVG."
    echo ""
    ensure_jar

    local dir="$OUTPUT_DIR/flame"
    mkdir -p "$dir"

    for fork in $(seq 1 "$FORKS"); do
        local json_file="$dir/fork_${fork}.json"
        local log_file="$dir/fork_${fork}.log"
        local flame_dir="$dir/fork_${fork}_flamegraph"
        mkdir -p "$flame_dir"

        printf "  Fork %2d/%d: " "$fork" "$FORKS"

        if run_single_fork "$json_file" "$log_file" "saturated_8t" "none" "" \
                "-prof async:output=flamegraph;dir=$flame_dir"; then
            local score
            score=$(extract_score "$json_file")
            echo "${score} ops/ms"
        else
            warn "FAILED (async-profiler may not be installed; see $log_file)"
        fi
    done

    echo ""
    python3 "$SCRIPT_DIR/scripts/classify-forks.py" "$dir"

    echo ""
    info "Flame graphs in $dir/fork_*_flamegraph/"
    info "Open the SVG files in a browser. Compare fast vs slow fork flame graphs."
    info ""
    info "What to look for:"
    info "  Fast forks: ConcurrentBag.borrow → threadList.get → FastList.get → CAS"
    info "  Slow forks: ConcurrentBag.borrow → sharedList iteration or SynchronousQueue.poll"
}

cmd_perfasm() {
    header "PERFASM: Hot assembly code (Linux perf required)"
    info "Captures disassembly of the hottest methods per fork."
    echo ""
    ensure_jar
    run_forks "perfasm" "saturated_8t" "none" "" "-prof perfasm" 5
}

cmd_counters() {
    header "HARDWARE COUNTERS: perf stat normalized (Linux perf required)"
    info "Captures IPC, cache misses, branch mispredictions per fork."
    info "Fast forks should show higher IPC and fewer branch mispredictions."
    echo ""
    ensure_jar
    run_forks "counters" "saturated_8t" "none" "" "-prof perfnorm"
}

cmd_latency() {
    header "LATENCY DISTRIBUTION: SampleTime mode"
    info "Captures per-fork latency histograms (p50, p90, p99, p999)."
    info "Fast forks: tight distribution, low p99."
    info "Slow forks: wider distribution, higher p99 from handoff queue waits."
    echo ""
    ensure_jar
    run_forks "latency" "saturated_8t" "none" "" "-bm sample -tu us"
}

cmd_controls() {
    header "CONTROL BENCHMARKS: Verify non-bimodal cases"
    info "Running 1t, 4t, 16t to confirm bimodal only occurs at saturation point."
    echo ""
    ensure_jar

    info "--- 1 thread (always fast, thread-local path) ---"
    run_forks "control-1t" "control_1t" "none" "" "" 5
    echo ""

    info "--- 4 threads (below saturation, always fast) ---"
    run_forks "control-4t" "control_4t" "none" "" "" 5
    echo ""

    info "--- 16 threads (above saturation, always 'slow' path) ---"
    run_forks "control-16t" "oversaturated_16t" "none" "" "" 5
}

cmd_all_portable() {
    header "RUNNING ALL PORTABLE EXPERIMENTS"
    info "(Skipping perf-dependent experiments: perfasm, counters)"
    echo ""

    cmd_baseline
    echo ""
    cmd_force_threadlocal
    echo ""
    cmd_force_affinity
    echo ""
    cmd_flush_threadlocal
    echo ""
    cmd_c1_only
    echo ""
    cmd_no_osr
    echo ""
    cmd_controls

    echo ""
    ok "All portable experiments complete. Results in $OUTPUT_DIR/"
    info ""
    info "Interpretation guide:"
    info "  1. If force-threadlocal eliminates bimodal → thread-local population is root cause"
    info "  2. If flush-threadlocal forces all-slow    → confirms thread-local is the differentiator"
    info "  3. If c1-only eliminates bimodal           → C2 JIT is involved"
    info "  4. If no-osr eliminates bimodal            → OSR is the non-deterministic factor"
    info "  5. If controls show no bimodal             → confirms it only appears at saturation"
}

cmd_all() {
    cmd_all_portable
    echo ""
    cmd_warmup_sweep
    echo ""
    cmd_jit_log
    echo ""
    cmd_flame
    echo ""
    cmd_latency
    echo ""
    ok "ALL experiments complete. Results in $OUTPUT_DIR/"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

MODE=${1:-help}

case "$MODE" in
    build)              cmd_build ;;
    baseline)           cmd_baseline ;;
    force-threadlocal)  cmd_force_threadlocal ;;
    force-affinity)     cmd_force_affinity ;;
    sleep-only)         cmd_sleep_only ;;
    borrow-once)        cmd_borrow_once ;;
    flush-threadlocal)  cmd_flush_threadlocal ;;
    c1-only)            cmd_c1_only ;;
    no-osr)             cmd_no_osr ;;    jit-log)            cmd_jit_log ;;
    warmup-sweep)       cmd_warmup_sweep ;;
    flame)              cmd_flame ;;
    perfasm)            cmd_perfasm ;;
    counters)           cmd_counters ;;
    latency)            cmd_latency ;;
    controls)           cmd_controls ;;
    all-portable)       cmd_all_portable ;;
    all)                cmd_all ;;

    help|*)
        cat <<HELP

$(echo -e "${BOLD}profile-hikari.sh${NC} — Profiling toolkit for HikariCP's bimodal behavior")

$(echo -e "${YELLOW}Usage:${NC} $0 <mode>")

$(echo -e "${GREEN}Hypothesis Tests (start here):${NC}")
  baseline             Reproduce bimodal with $FORKS forks (natural warmup)
  force-threadlocal    Pre-warm thread-local lists (serialized, same connection)
  force-affinity       Pre-warm with unique connections per thread (1:1 affinity)
  sleep-only           Sleep-only prewarm (no borrow/return, tests time delay)
  borrow-once          Single borrow/return per thread (minimal population)
  flush-threadlocal    Flush thread-local lists each iteration (should force slow)
  c1-only              Disable C2 compiler (test JIT hypothesis)
  no-osr               Disable on-stack replacement
  warmup-sweep         Vary warmup iterations (1, 2, 5, 10, 20, 50)
  controls             Run non-bimodal control benchmarks (1t, 4t, 16t)

$(echo -e "${GREEN}Profiling (deep dive):${NC}")
  flame                Async-profiler flame graphs per fork
  jit-log              JIT compilation logs per fork (-XX:+PrintCompilation)
  perfasm              Hot assembly disassembly (Linux perf required)
  counters             Hardware perf counters (Linux perf required)
  latency              SampleTime mode for latency histograms

$(echo -e "${GREEN}Batch:${NC}")
  build                Build the benchmark jar
  all-portable         Run all non-perf experiments
  all                  Run everything

$(echo -e "${YELLOW}Environment Variables:${NC}")
  FORKS=N              Number of forks per experiment (default: 10)

$(echo -e "${YELLOW}Examples:${NC}")
  $0 build
  $0 baseline
  $0 force-threadlocal
  FORKS=20 $0 baseline
  $0 all-portable

$(echo -e "${YELLOW}Recommended investigation order:${NC}")
  1. $0 build
  2. $0 baseline              # confirm bimodal
  3. $0 force-affinity        # test thread-local affinity hypothesis (KEY)
  4. $0 force-threadlocal     # compare: same connection for all threads
  5. $0 flush-threadlocal     # negative control
  6. $0 c1-only               # test JIT hypothesis
  6. $0 controls              # confirm non-bimodal at other thread counts
  7. $0 flame                 # flame graph comparison (fast vs slow forks)
  8. $0 jit-log               # JIT compilation diff

HELP
        ;;
esac
