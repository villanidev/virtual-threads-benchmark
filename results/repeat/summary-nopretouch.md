# Repeated runs: direct vs ffm (N=60000, 3 reps)

Cells show median [min..max] over the repetitions. 1 vCPU, 128m, k6 constant arrival rate.

## p99 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 7.99 [7.29..8.49] | 6.34 [5.16..8.14] | 6.91 [6.33..7.55] |
| loop-off | direct+musl | 3 | 6.39 [6.14..6.57] | 7.96 [6.65..10.58] | 11.20 [8.20..12.12] |
| loop-off | native+musl | 3 | 6.89 [6.62..8.20] | 7.36 [6.59..8.93] | 6.79 [5.62..7.22] |

## p50 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 1.35 [1.34..1.36] | 0.18 [0.16..0.19] | 0.23 [0.20..0.23] |
| loop-off | direct+musl | 3 | 1.36 [1.34..1.36] | 0.24 [0.23..0.27] | 0.30 [0.30..0.34] |
| loop-off | native+musl | 3 | 1.34 [1.33..1.39] | 0.22 [0.21..0.22] | 0.28 [0.27..0.28] |

## RSS MB

| impl | mem | RSS median [min..max] | boot to listening ms (incl. warm-up) | binary MB |
|---|---|---|---|---|
| loop-off | direct | 145.00 [144.00..146.00] | 4018.00 [4017.00..4018.00] | - |
| loop-off | direct+musl | 70.00 [70.00..71.00] | 3017.00 [3014.00..3018.00] | 17 |
| loop-off | native+musl | 49.00 [49.00..50.00] | 2011.00 [2010.00..2015.00] | 17 |
