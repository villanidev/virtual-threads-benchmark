# Repeated runs: direct vs ffm (N=60000, 3 reps)

Cells show median [min..max] over the repetitions. 1 vCPU, 128m, k6 constant arrival rate.

## p99 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 12.76 [11.32..15.38] | 8.49 [6.90..9.36] | 6.17 [5.03..6.75] |
| loop-off | direct+musl | 3 | 6.28 [5.89..6.67] | 5.89 [5.59..7.85] | 6.74 [6.25..9.88] |
| loop-off | native+musl | 3 | 5.89 [5.35..5.91] | 7.91 [6.99..8.17] | 7.40 [7.15..9.27] |

## p50 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 1.40 [1.34..1.43] | 0.18 [0.16..0.19] | 0.22 [0.20..0.23] |
| loop-off | direct+musl | 3 | 1.34 [1.31..1.37] | 0.23 [0.20..0.23] | 0.29 [0.27..0.31] |
| loop-off | native+musl | 3 | 1.33 [1.32..1.36] | 0.22 [0.22..0.22] | 0.28 [0.27..0.29] |

## RSS MB

| impl | mem | RSS median [min..max] | boot to listening ms (incl. warm-up) | binary MB |
|---|---|---|---|---|
| loop-off | direct | 108.00 [105.00..120.00] | 1010.00 [1007.00..2012.00] | - |
| loop-off | direct+musl | 30.00 [30.00..30.00] | 1009.00 [1007.00..1011.00] | 17 |
| loop-off | native+musl | 21.00 [21.00..21.00] | 1006.00 [6.00..1011.00] | 17 |
