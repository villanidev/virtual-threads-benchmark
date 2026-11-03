# Repeated runs: direct vs ffm (N=40000, 3 reps)

Cells show median [min..max] over the repetitions. 1 vCPU, 128m, k6 constant arrival rate.

## p99 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 21.85 [21.76..21.88] | 0.86 [0.69..0.98] | 21.03 [21.01..21.05] |
| loop-off | ffm | 3 | 22.45 [22.39..22.77] | 0.90 [0.87..0.93] | 21.02 [21.01..21.08] |

## p50 ms

| impl | mem | reps | write | read | mixed |
|---|---|---|---|---|---|
| loop-off | direct | 3 | 20.24 [20.22..20.26] | 0.18 [0.18..0.19] | 0.20 [0.19..0.20] |
| loop-off | ffm | 3 | 20.25 [20.24..20.26] | 0.19 [0.18..0.19] | 0.20 [0.19..0.20] |

## RSS MB

| impl | mem | RSS median [min..max] |
|---|---|---|
| loop-off | direct | 234.00 [233.00..238.00] |
| loop-off | ffm | 242.00 [241.00..249.00] |
