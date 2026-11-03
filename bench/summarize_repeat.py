#!/usr/bin/env python3
"""Aggregates results/repeat/raw/*.txt (bench/repeat.sh): median [min..max] of k6 p50/p99 per phase, and RSS."""
import glob
import re
import statistics
import sys

RAW = "results/repeat/raw"


def parse(path):
    lines = open(path).read().splitlines()
    info = dict(re.findall(r"(\w+)=(\S*)", lines[0]))
    phases, rss, boot = {}, None, None
    for line in lines:
        p = line.split()
        if p and p[0] in ("write", "read", "mixed") and len(p) >= 9:
            phases[p[0]] = (float(p[5]), float(p[7]))
        mb = re.search(r"boot to listening \(ms\): (\d+)  binary \(KB\): (\w+)", line)
        if mb:
            boot = (int(mb.group(1)), int(mb.group(2)) // 1024 if mb.group(2).isdigit() else None)
        m = re.search(r"RSS \(KB\): (\d+)", line)
        if m:
            rss = int(m.group(1)) // 1024
    return info, phases, rss, boot


def fmt(values):
    return f"{statistics.median(values):.2f} [{min(values):.2f}..{max(values):.2f}]"


def main(n):
    cells = {}
    for txt in sorted(t for t in glob.glob(f"{RAW}/*-{n}-r*.txt") if not t.endswith(".nmt.txt")):
        info, phases, rss, boot = parse(txt)
        mem = info.get("mem", "direct") + ("+" + info["native"] if info.get("native", "no") != "no" else "")
        cells.setdefault((info["impl"], mem), []).append((phases, rss, boot))
    print(f"# Repeated runs: direct vs ffm (N={n}, {max(len(v) for v in cells.values())} reps)\n")
    print("Cells show median [min..max] over the repetitions. 1 vCPU, 128m, k6 constant arrival rate.\n")
    for idx, name in ((1, "p99"), (0, "p50")):
        print(f"## {name} ms\n")
        print("| impl | mem | reps | write | read | mixed |")
        print("|---|---|---|---|---|---|")
        for (impl, mem), runs in sorted(cells.items()):
            cols = [fmt([r[0][ph][idx] for r in runs if ph in r[0]]) for ph in ("write", "read", "mixed")]
            print(f"| {impl} | {mem} | {len(runs)} | " + " | ".join(cols) + " |")
        print()
    print("## RSS MB\n")
    print("| impl | mem | RSS median [min..max] | boot to listening ms (incl. warm-up) | binary MB |")
    print("|---|---|---|---|---|")
    for (impl, mem), runs in sorted(cells.items()):
        boots = [float(r[2][0]) for r in runs if r[2]]
        size = next((r[2][1] for r in runs if r[2] and r[2][1]), None)
        print(f"| {impl} | {mem} | {fmt([float(r[1]) for r in runs if r[1]])} | {fmt(boots) if boots else '-'} | {size or '-'} |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "40000")
