"""
Benchmark the areas server with random points.
GET /query for each point (single-point latency) and one POST /query (batch).
Writes results to a markdown file. Requires the areas server to be running.

Usage:
  AREAS_SERVER_URL=http://127.0.0.1:5001 python -m scripts.benchmark_areas_server
  python -m scripts.benchmark_areas_server --points 20 --out docs/areas_performance.md
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def _base_url():
    return (os.environ.get("AREAS_SERVER_URL") or "http://127.0.0.1:5001").strip().rstrip("/")


def _http_get(url: str, timeout: int = 60) -> tuple[int, dict | None, float]:
    """GET url; returns (status_code, parsed_json_or_none, elapsed_seconds)."""
    req = urllib.request.Request(url, method="GET")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode()
            elapsed = time.perf_counter() - start
            try:
                data = json.loads(body) if body else None
            except json.JSONDecodeError:
                data = None
            return resp.getcode(), data, elapsed
    except urllib.error.HTTPError as e:
        elapsed = time.perf_counter() - start
        body = e.read().decode() if e.fp else ""
        try:
            data = json.loads(body) if body.strip().startswith("{") else None
        except json.JSONDecodeError:
            data = None
        err_msg = body[:500] if body else e.reason or str(e)
        print(f"GET error: {e.code} {e.reason} — {err_msg}", file=sys.stderr)
        return e.code, data, elapsed
    except OSError as e:
        elapsed = time.perf_counter() - start
        print(f"GET error (connection/timeout): {e}", file=sys.stderr)
        return -1, None, elapsed


def _http_post(url: str, data_bytes: bytes, timeout: int = 120) -> tuple[int, dict | None, float]:
    """POST url with JSON body; returns (status_code, parsed_json_or_none, elapsed_seconds)."""
    req = urllib.request.Request(url, data=data_bytes, method="POST")
    req.add_header("Content-Type", "application/json")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode()
            elapsed = time.perf_counter() - start
            try:
                data = json.loads(body) if body else None
            except json.JSONDecodeError:
                data = None
            return resp.getcode(), data, elapsed
    except urllib.error.HTTPError as e:
        elapsed = time.perf_counter() - start
        body = e.read().decode() if e.fp else ""
        try:
            data = json.loads(body) if body.strip().startswith("{") else None
        except json.JSONDecodeError:
            data = None
        err_msg = body[:500] if body else e.reason or str(e)
        print(f"POST error: {e.code} {e.reason} — {err_msg}", file=sys.stderr)
        return e.code, data, elapsed
    except OSError as e:
        elapsed = time.perf_counter() - start
        print(f"POST error (connection/timeout): {e}", file=sys.stderr)
        return -1, None, elapsed


def _random_points(n: int, seed: int | None = None) -> list[tuple[float, float]]:
    if seed is not None:
        random.seed(seed)
    return [
        (random.uniform(-90, 90), random.uniform(-180, 180))
        for _ in range(n)
    ]


def run_benchmark(
    base_url: str,
    num_points: int = 10,
    seed: int | None = 42,
) -> dict:
    """Run GET per point and one POST batch; return stats and per-point results."""
    points = _random_points(num_points, seed=seed)
    get_times: list[float] = []
    get_errors = 0
    get_status_codes: list[int] = []

    for lat, lon in points:
        url = f"{base_url}/query?lat={lat}&lon={lon}"
        status, data, elapsed = _http_get(url)
        get_times.append(elapsed)
        get_status_codes.append(status)
        if status != 200:
            get_errors += 1

    batch_status, batch_data, batch_elapsed = _http_post(
        f"{base_url}/query",
        json.dumps({"points": [[lat, lon] for lat, lon in points]}).encode(),
    )

    return {
        "base_url": base_url,
        "num_points": num_points,
        "seed": seed,
        "points": points,
        "get_times_ms": [t * 1000 for t in get_times],
        "get_errors": get_errors,
        "get_status_codes": get_status_codes,
        "batch_elapsed_ms": batch_elapsed * 1000,
        "batch_status": batch_status,
        "batch_ok": batch_status == 200 and batch_data and "results" in batch_data and len(batch_data["results"]) == num_points,
    }


def write_md(results: dict, out_path: Path) -> None:
    """Write benchmark results to a markdown file."""
    n = results["num_points"]
    get_times = results["get_times_ms"]
    get_ok = results["get_errors"] == 0

    lines = [
        "# Areas server performance",
        "",
        f"**Base URL:** `{results['base_url']}`  ",
        f"**Points:** {n} (random, seed={results['seed']})  ",
        f"**Date:** {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}",
        "",
        "## Single-point (GET /query)",
        "",
    ]
    if get_times:
        lines.extend([
            f"- **Min:** {min(get_times):.2f} ms",
            f"- **Max:** {max(get_times):.2f} ms",
            f"- **Mean:** {sum(get_times) / len(get_times):.2f} ms",
            f"- **Total:** {sum(get_times):.2f} ms",
            f"- **Errors:** {results['get_errors']} / {n}",
            "",
        ])
    if not get_ok:
        lines.append(f"Status codes: {results['get_status_codes']}")
        lines.append("")

    lines.extend([
        "## Batch (POST /query)",
        "",
        f"- **Elapsed:** {results['batch_elapsed_ms']:.2f} ms",
        f"- **Status:** {results['batch_status']}",
        f"- **OK:** {results['batch_ok']}",
        "",
        "## Per-point GET latency (ms)",
        "",
        "| # | lat | lon | ms |",
        "|---|-----|-----|-----|",
    ])
    for i, (lat, lon) in enumerate(results["points"], 1):
        ms = get_times[i - 1] if i <= len(get_times) else 0
        lines.append(f"| {i} | {lat:.6f} | {lon:.6f} | {ms:.2f} |")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Benchmark areas server with random points")
    parser.add_argument("--points", type=int, default=10, help="Number of random points (default 10)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed (default 42)")
    parser.add_argument("--out", type=str, default="areas_server_performance.md", help="Output markdown file")
    args = parser.parse_args()

    base_url = _base_url()
    print(f"Benchmarking {base_url} with {args.points} random points (seed={args.seed}) ...")

    # Quick health check
    status, data, _ = _http_get(f"{base_url}/health")
    if status != 200 or (data and data.get("status") != "ok"):
        print(f"Error: server unhealthy or unreachable (GET /health -> {status})", file=sys.stderr)
        return 1

    results = run_benchmark(base_url, num_points=args.points, seed=args.seed)
    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = Path(__file__).resolve().parent.parent / out_path
    write_md(results, out_path)
    print(f"Results written to {out_path}")

    if results["get_errors"]:
        print(f"Warning: {results['get_errors']} GET request(s) failed", file=sys.stderr)
    if not results["batch_ok"]:
        print("Warning: batch POST did not return OK or expected result count", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
