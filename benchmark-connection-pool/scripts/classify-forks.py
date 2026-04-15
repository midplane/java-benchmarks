#!/usr/bin/env python3
"""
classify-forks.py — Classify JMH fork results as fast or slow mode.

Reads JMH JSON result files from a directory and classifies each fork
based on throughput using Otsu's method (maximize inter-class variance)
to find the optimal threshold between two modes.

If the coefficient of variation is below 15%, the distribution is
classified as unimodal (no bimodal behavior detected).

Usage:
    python3 classify-forks.py <results_directory>
"""

import json
import sys
import os
import glob
import statistics


def load_results(directory):
    """Load all JMH JSON result files from a directory."""
    results = []
    for json_file in sorted(glob.glob(os.path.join(directory, "*.json"))):
        try:
            with open(json_file) as f:
                data = json.load(f)
            for entry in data:
                name = entry["benchmark"].split(".")[-1]
                raw = entry["primaryMetric"].get("rawData", [[]])
                if raw and raw[0]:
                    iters = raw[0]
                    fork_mean = sum(iters) / len(iters)
                    fork_stdev = statistics.stdev(iters) if len(iters) > 1 else 0.0
                else:
                    fork_mean = entry["primaryMetric"]["score"]
                    fork_stdev = 0.0
                results.append(
                    {
                        "file": os.path.basename(json_file),
                        "benchmark": name,
                        "mean": fork_mean,
                        "stdev": fork_stdev,
                        "iterations": iters if raw and raw[0] else [],
                    }
                )
        except Exception as e:
            print(f"  Warning: Error reading {json_file}: {e}", file=sys.stderr)
    return results


def classify(results):
    """
    Classify results as fast/slow using Otsu's method.

    Otsu's method finds the threshold that maximizes the between-class
    variance of a bimodal distribution.  If the distribution is unimodal
    (CV < 15%), all results are marked UNIFORM.

    Returns (results_with_mode, threshold).
    """
    if not results:
        return results, 0.0

    scores = sorted(r["mean"] for r in results)

    if len(scores) < 2:
        for r in results:
            r["mode"] = "ONLY"
        return results, scores[0]

    # Check for unimodal distribution
    mean_val = statistics.mean(scores)
    cv = statistics.stdev(scores) / mean_val if mean_val > 0 else 0
    if cv < 0.15:
        for r in results:
            r["mode"] = "UNIFORM"
        return results, mean_val

    # Otsu's method: find threshold maximizing between-class variance
    best_threshold = scores[0]
    best_variance = 0.0

    for i in range(1, len(scores)):
        low = scores[:i]
        high = scores[i:]
        w0 = len(low) / len(scores)
        w1 = len(high) / len(scores)
        m0 = statistics.mean(low)
        m1 = statistics.mean(high)
        between_var = w0 * w1 * (m0 - m1) ** 2
        if between_var > best_variance:
            best_variance = between_var
            best_threshold = (scores[i - 1] + scores[i]) / 2

    for r in results:
        r["mode"] = "FAST" if r["mean"] > best_threshold else "SLOW"

    return results, best_threshold


def print_results(results, threshold):
    """Print classified results as a formatted table."""
    if not results:
        print("  No results found.")
        return

    fast = [r for r in results if r.get("mode") == "FAST"]
    slow = [r for r in results if r.get("mode") == "SLOW"]
    uniform = [r for r in results if r.get("mode") == "UNIFORM"]

    # Table header
    print(f"  {'File':<25} {'Mean (ops/ms)':>14} {'StDev':>10} {'CV%':>6} {'Mode':<8}")
    print(f"  {'-'*25} {'-'*14} {'-'*10} {'-'*6} {'-'*8}")

    for r in results:
        mode_str = r.get("mode", "?")
        cv_pct = (r["stdev"] / r["mean"] * 100) if r["mean"] > 0 else 0
        print(
            f"  {r['file']:<25} {r['mean']:>14,.0f} {r['stdev']:>10,.0f}"
            f" {cv_pct:>5.1f}% {mode_str:<8}"
        )

    print()

    if uniform:
        all_mean = statistics.mean(r["mean"] for r in results)
        all_cv = (
            statistics.stdev(r["mean"] for r in results) / all_mean * 100
            if all_mean > 0
            else 0
        )
        print(f"  Result: UNIMODAL (CV={all_cv:.1f}%, mean={all_mean:,.0f} ops/ms)")
        print(f"  → No bimodal behavior detected.")

    elif fast and slow:
        fast_mean = statistics.mean(r["mean"] for r in fast)
        slow_mean = statistics.mean(r["mean"] for r in slow)
        ratio = fast_mean / slow_mean if slow_mean > 0 else float("inf")
        print(f"  Result: BIMODAL (threshold={threshold:,.0f} ops/ms)")
        print(
            f"  → Fast: {len(fast)}/{len(results)} forks,"
            f" mean={fast_mean:,.0f} ops/ms"
        )
        print(
            f"  → Slow: {len(slow)}/{len(results)} forks,"
            f" mean={slow_mean:,.0f} ops/ms"
        )
        print(f"  → Ratio: {ratio:.1f}x")

    elif fast:
        fast_mean = statistics.mean(r["mean"] for r in fast)
        print(
            f"  Result: ALL FAST ({len(fast)} forks,"
            f" mean={fast_mean:,.0f} ops/ms)"
        )
        print(f"  → No slow forks observed.")

    elif slow:
        slow_mean = statistics.mean(r["mean"] for r in slow)
        print(
            f"  Result: ALL SLOW ({len(slow)} forks,"
            f" mean={slow_mean:,.0f} ops/ms)"
        )
        print(f"  → No fast forks observed.")


def main():
    if len(sys.argv) < 2:
        print("Usage: classify-forks.py <results_directory>", file=sys.stderr)
        sys.exit(1)

    directory = sys.argv[1]
    if not os.path.isdir(directory):
        print(f"Error: {directory} is not a directory", file=sys.stderr)
        sys.exit(1)

    results = load_results(directory)
    results, threshold = classify(results)
    print_results(results, threshold)


if __name__ == "__main__":
    main()
