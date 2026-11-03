# Repeated runs: direct vs ffm (N=60000, 3 reps)

Cells show median [min..max] over the repetitions. 1 vCPU, 128m, k6 constant arrival rate.

## p99 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 7.42 [5.87..7.60] | 6.37 [5.08..6.77] | 4.92 [4.85..7.47] |
| loop-off | ffm | 3 | 8.59 [8.26..9.99] | 9.70 [8.43..16.12] | 5.41 [5.04..7.94] |
| vt-off | direct | 3 | 21.07 [14.17..24.01] | 18.80 [6.97..20.50] | 16.02 [15.88..59.02] |
| vt-off | ffm | 3 | 15.27 [14.12..23.70] | 10.25 [8.48..49.71] | 23.43 [18.71..35.36] |

## p50 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 1.40 [1.38..1.44] | 0.17 [0.15..0.18] | 0.21 [0.19..0.21] |
| loop-off | ffm | 3 | 1.44 [1.41..1.46] | 0.18 [0.18..0.19] | 0.21 [0.20..0.22] |
| vt-off | direct | 3 | 1.52 [1.49..1.88] | 0.38 [0.29..1.00] | 0.63 [0.52..1.70] |
| vt-off | ffm | 3 | 1.55 [1.53..1.63] | 0.33 [0.32..0.59] | 0.69 [0.64..0.86] |

## RSS MB

| impl | mem | RSS median [min..max] |
|---|---|---|
| loop-off | direct | 237.00 [236.00..237.00] |
| loop-off | ffm | 246.00 [241.00..250.00] |
| vt-off | direct | 261.00 [257.00..263.00] |
| vt-off | ffm | 256.00 [256.00..257.00] |
