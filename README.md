# virtual-threads-benchmark

Estudo de modelos de concorrência e alocação de memória em hardware escasso: **1 vCPU** (`taskset -c 0`) e **heap de 128m** (`-Xmx128m -Xms128m`), JDK 25 (Temurin). Cada task simula uma chamada bloqueante (`sleep`).

> Todos os números abaixo vêm de **uma única execução** por ponto. Servem para ordem de grandeza, não para comparar diferenças pequenas.

## Como rodar

```bash
mvn -q compile
./run.sh <modelo> <N> [cpu=0]          # JFR em jfr/<modelo>-<N>.jfr
POOL=20 SLEEP_MS=50 ./run.sh vfixed 20000
JAVA_EXTRA=-XX:+UseCompactObjectHeaders TAG=coh ./run.sh virtual 50000
./run.sh server 100000 [vus=50]        # teste de carga real do servidor de agendamento (ciclo 2)
VUS=200 DELAY_MS=50 WARM=20000 PHASES=write,read ./run.sh server 10000
LOAD_CPU=1-3 DELAY_MS=1 ./run.sh server 100000 200   # k6 em 3 CPUs, delay 1 ms, 200 VUs
```

Modelos do [Bench.java](src/main/java/dev/villani/benchmark/Bench.java): `cached`, `fixed` (pool=`POOL`, padrão 500), `vfixed` (pool fixo de virtual threads), `virtual`, `flat`, `flat-dbb`, `flat-nio`, `vsem-heap`, `vsem-slab`. Saída: tempo, bytes alocados pela thread main, GC e pico de heap usada. As classes `CachedThreadPool`, `FixedThreadPool`, `VirtualThreadPool` e `VirtualThreadPoolWithSemaphore` (em `benchmark/executors`) são os experimentos originais, só movidas de pacote.

## Estrutura de pacotes

```
dev.villani
├── benchmark              Bench (ponto de entrada dos experimentos)
│   ├── executors          pools de threads e variantes com semáforo
│   └── eventloop          loops flat (FFM, DirectByteBuffer, NIO Selector)
├── server                 infraestrutura HTTP flat, reutilizável e sem conhecer o domínio (ciclo 2)
│   ├── memory             IntRing, ByteRing, ReadyQueue, ConnectionPool (memória plana)
│   ├── net                NioServer (Selector, backpressure, ganchos de tick/close)
│   ├── http               parser, Router, Dispatcher, ResponseWriter, AsyncHandler, HttpConnectionHandler
│   ├── json               tokenizador, binder para registro pooled, JsonWriter
│   └── time               TimerWheel plana
└── application
    └── agendamento        tabela off-heap, regras, handlers, AgendamentoServer (API de agendamento)
```

Regra: `server` é a base reutilizável e nunca importa `application` nem `benchmark` (verificado por `PackageDependencyTest`); `application` depende de `server`. Dentro do `server`, camadas técnicas (`memory`, `net`, `http`, `json`, `routing`); dentro do `application`, um pacote por feature. Testes seguem o mesmo layout, em estilo BDD (given/when/then), com utilitários em `dev.villani.testsupport` (`AllocationProbe`).

## Resultados

Sleep de 1s (N de 1k a 100k), salvo indicação.

| Modelo | Resultado |
|---|---|
| `newFixedThreadPool(500)` | Tempo = ⌈N/500⌉ s: 2s, 20s, 200s (1k, 10k, 100k). As threads bloqueadas ocupam a vaga sem usar CPU. |
| `newCachedThreadPool` | 1,2s (1k), 2,9s (10k), ~11s (100k, medição anterior). Uma thread de plataforma por task. |
| `newVirtualThreadPerTaskExecutor` | ~1,05s (1k), ~1,2–1,7s (10k), 1,6–3,4s (50k). **Em 128m não termina com 80k–100k**: Full GC contínuo. |
| Virtual + Compact Object Headers | ~11–12% menos alocação (4,2→3,7 MB a 10k; 20,2→17,8 MB a 50k). Não evita o colapso. |
| `vfixed` pool 10/20/50 (20k tasks, 50 ms) | 101s / 50s / 20s, pico de heap 24–25 MB, 0 GCs. Memória plana, mas tempo = N/pool. |
| Virtual + semáforo (50), buffer de 10 KB na heap | 5k: pico de 71 MB; 20k: colapsa. O buffer era alocado antes do `acquire()`. |
| Virtual + semáforo (50), slab off-heap | 5k: 30 MB; 20k: 20,5s, pico de 84 MB. Dados planos; sobra o custo linear das threads estacionadas. |
| Flat (1 thread, estado off-heap) | ~1,02–1,14s para 1k–100k, 0 GCs, alocação constante (~540 KB com FFM, 8–20 KB com buffers NIO). |

Alocação por task nas variantes flat é zero. A diferença de ~540 KB contra ~8 KB entre FFM e `DirectByteBuffer` é provavelmente inicialização (hipótese não confirmada); o JFR (`ThreadAllocationStatistics`: ~15,5 MB nas três) não distingue as variantes.

## Interpretação (mechanical sympathy)

- **Sleep não consome CPU.** O tempo dos pools de plataforma é Lei de Little: concorrência × latência. Com tasks bloqueantes, o gargalo é o número de vagas, não a CPU.
- **Virtual threads trocam memória por simplicidade.** Cada task viva custa objetos na heap (`VirtualThread`, `StackChunk`, timer do sleep, rastreamento do executor). O custo é linear em N e vem do runtime do JDK; object pool e flyweight não o alcançam.
- **Limitar a concorrência não ajuda.** Com pool ou semáforo a memória fica plana, mas o tempo vira N/limite, e o semáforo ainda mantém todas as threads estacionadas (e as filas na heap).
- **Single thread + memória off-heap é o que dá memória plana neste tamanho de heap.** A task vira um índice; os prazos ficam em um `MemorySegment` (FFM) ou `DirectByteBuffer`; uma thread espera até o próximo prazo (`parkNanos` ou `Selector.select`).

### Variantes flat

- [FlatEventLoop](src/main/java/dev/villani/benchmark/eventloop/FlatEventLoop.java): FFM (`Arena` + `MemorySegment`), liberação determinística.
- [DirectBufferEventLoop](src/main/java/dev/villani/benchmark/eventloop/DirectBufferEventLoop.java): `ByteBuffer.allocateDirect`; limitado por `-XX:MaxDirectMemorySize`, liberação via `Cleaner`.
- [NioEventLoop](src/main/java/dev/villani/benchmark/eventloop/NioEventLoop.java): espera em `Selector.select(timeoutMs)` (granularidade de 1 ms), o mesmo ponto de bloqueio usado para I/O em servidores NIO.

Como o atraso é constante, uma fila FIFO de prazos substitui a timer wheel.

## Servidor flat sob carga real (ciclo 2)

`./run.sh server N` sobe o `AgendamentoServer` em **1 vCPU (taskset CPU 0), -Xmx128m -Xms128m**, com JFR `profile` e log de GC, e o gerador de carga executa N requisições por fase com 50 usuários virtuais (keep-alive). Criação com chamada externa simulada de 20 ms (timer wheel). Fases: `write` (POST únicos), `read` (GET lista do dia), `mixed` (20% write / 80% read). Resultados completos em `bench/results/`, gravações em `jfr/server-N.jfr`.

| N | fase | tempo (ms) | req/s | p50 (ms) | p99 (ms) | máx (ms) | status |
|---|---|---|---|---|---|---|---|
| 1.000 | write | 908 | 1.102 | 27,3 | 76,1 | 87,2 | 100% 2xx |
| 1.000 | read | 276 | 3.629 | 10,5 | 34,4 | 39,5 | 100% 2xx |
| 1.000 | mixed | 300 | 3.328 | 7,7 | 40,0 | 45,9 | 100% 2xx |
| 10.000 | write | 4.973 | 2.011 | 21,4 | 70,8 | 154,3 | 100% 2xx |
| 10.000 | read | 2.008 | 4.980 | 9,2 | 24,4 | 52,3 | 100% 2xx |
| 10.000 | mixed | 1.395 | 7.170 | 1,3 | 35,1 | 73,1 | 100% 2xx |
| 100.000 | write | 44.086 | 2.268 | 21,4 | 29,5 | 68,0 | 100% 2xx |
| 100.000 | read | 6.997 | 14.291 | 2,3 | 15,2 | 123,6 | 100% 2xx |
| 100.000 | mixed | 9.112 | 10.975 | 0,37 | 22,8 | 35,8 | 100% 2xx |

Servidor, run de 100.000: **0 GCs** (Serial), 0 erros, RSS ≈ 220 MB (heap 128 MB pré-tocado + off-heap + JVM), 16 threads (JVM; o loop é uma só). No JFR a thread do servidor alocou ~1,2 MB em 300 mil requisições (≈ custo do accept: `SocketChannelImpl`/`SelectionKey` das 150 conexões), ou seja, 0 bytes por requisição; só 2 `ObjectAllocationSample` (boot).

Como interpretar:
- **Write é limitado pela simulação, não pelo servidor:** 50 usuários / 20 ms ≈ 2.500 req/s no máximo (lei de Little). O p50 de ~21 ms mostra que o servidor adiciona ~1 ms sobre o atraso. Para ver o teto real do servidor, rode `DELAY_MS=1` ou `VUS` maior.
- **Read e mixed mostram a capacidade de CPU:** 11–14 mil req/s em 1 vCPU, com o gerador de carga e o servidor em CPUs separadas. A cauda (p99, máx) com N=1.000 é aquecimento da JIT (ver `WARM`).
- Hot spots no JFR (amostras de CPU): `ListarAgendamentosHandler.handle` e `JsonWriter.putLong` dominam; candidatos a otimização (serialização de inteiros e do item da lista).
- **Aquecimento de ponta a ponta no boot** (`WARM=<ciclos>`): uma instância descartável do servidor (tabela, pool e timer wheel próprios) sobe em porta efêmera no loopback, 16 clientes sintéticos por socket executam N ciclos create (adiado 1 ms) + list + cancel, com pipelining e reconexões, e só depois a porta real abre. Dados fake nunca chegam à instância real. (Uma primeira versão que só exercitava `Dispatcher` em memória foi descartada: não aquecia NIO, timer wheel nem accept.)

  | ciclos | boot (s) | GCs no boot | N=1.000 read req/s / p99 | N=10.000 read req/s / p99 | N=10.000 write p99 |
  |---|---|---|---|---|---|
  | 0 (frio) | 0 | 0 | 3.629 / 34 ms | 4.980 / 24 ms | 71 ms |
  | 10.000 | 4,6 | 13 | 4.033 / 24 ms | 14.395 / 15 ms | 29 ms |
  | 30.000 | 10,9 | 36 | 2.091 / 70 ms | 15.777 / 15 ms | 29 ms |
  | 60.000 | 20,5 | 70 | 8.709 / 9 ms | 16.295 / 10 ms | 29 ms |
  | 100.000 | 34,2 | 116 | 6.806 / 32 ms | 14.367 / 9 ms | 36 ms |

  Leitura: o grande salto está nos primeiros ~10 mil ciclos (N=10.000: read 5,0 → 14,4 mil req/s; write p99 71 → 29 ms). Acima disso o ganho médio é marginal (14–16 mil req/s) enquanto o boot cresce linearmente (~0,34 s por mil ciclos); o N=1.000 oscila demais para decidir, porque a própria JVM do gerador de carga também está fria nos primeiros milhares de requisições. Ponto de partida sugerido: 10–20 mil ciclos. O aquecimento aloca à vontade (os GCs do boot), mas nenhum GC ocorre durante a carga real.
- **Gerador de carga: k6 (padrão desde então).** `bench/k6/agendamento.js` (executor `shared-iterations`: N requisições entre `VUS` usuários, keep-alive; fases `write`/`read`/`mixed`; checks de status 201/200) roda em `taskset` (`LOAD_CPU`), com o servidor na CPU 0. O gerador Java anterior (`LoadClient`) foi removido: k6 é o gerador único; tabelas anteriores a esta seção foram medidas com ele. Matriz com k6 (50 VUs, uma execução por ponto, 0 falhas, `bench/results/server-N-k6-*.txt`):

  | N | fase | frio req/s (p99 ms) | warm 10k ciclos req/s (p99 ms) |
  |---|---|---|---|
  | 1.000 | write | 2.094 (33) | 2.072 (42) |
  | 1.000 | read | 7.773 (30) | 7.481 (21) |
  | 10.000 | read | 6.193 (36) | 9.094 (25) |
  | 10.000 | mixed | 5.021 (59) | 7.601 (31) |
  | 100.000 | write | 2.189 (41) | 2.171 (44) |
  | 100.000 | read | 9.318 (23) | 8.119 (32) |
  | 100.000 | mixed | 8.488 (28) | 8.445 (28) |

  Com k6 o servidor segue sem GC durante a carga e sem erros, mas o k6 satura em ~8–9 mil req/s (JS, uma CPU de servidor competindo com 3 do gerador no mesmo chip), abaixo dos 14–19 mil que o gerador Java obteve. Ou seja, **no read/mixed o k6 é o gargalo, não o servidor**: o ganho do aquecimento só aparece em N=10.000 e some em 100.000, e as diferenças entre colunas ficam dentro do ruído. Para achar o teto do servidor use `DELAY_MS=1` (ver varredura de VUs abaixo).
- Uma execução por ponto, loopback, sem repetição: ordem de grandeza.

### Varredura de VUs, delay 1 ms, k6 em CPUs 1-3 (N=100.000 por fase, 0 falhas, 0 GCs)

| VUs | write req/s (p99 ms) | read req/s (p99 ms) | mixed req/s (p99 ms) | pico CPU JVM |
|---|---|---|---|---|
| 50  | 10,0k (19) | 11,1k (24) | 10,9k (23) | 9,8% de 4 CPUs |
| 100 | 10,9k (42) | 10,9k (43) | 10,6k (45) | 6,8% |
| 150 | 10,9k (60) | 10,5k (55) | 10,1k (55) | 8,5% |
| 200 | 11,2k (69) | 11,5k (78) | 11,8k (80) | 8,9% |

Leitura: a vazão é constante (~10–11k) enquanto a latência cresce linearmente com os VUs (lei de Little). O servidor usa ≤ ~40% de uma CPU, logo **o gargalo é o k6**, não o servidor; a latência extra é fila no gerador. RSS ~220 MB (heap 128 MB + metaspace/JIT/buffers off-heap), 16 threads.

### Topologia e pinning

- i7-10510U: 4 CPUs lógicas, 2 núcleos físicos. CPUs 0/1 são irmãs SMT (núcleo 0) e 2/3 também (núcleo 1).
- k6 em 2-3 (1 núcleo, sem interferir no servidor): ~6k req/s. k6 em 1-3: ~9,7k req/s, porém CPU 1 divide L1/L2/unidades de execução com o servidor (CPU 0). Trade-off: pureza do isolamento x capacidade do gerador.
- `taskset` já fixa o processo inteiro (event loop, JIT, GC, JFR) numa CPU: é o cenário de 1 vCPU. Java-Thread-Affinity (OpenHFT) traz JNA + libs nativas e só compensa com várias CPUs (fixar só o event loop e afastar JIT/GC). Alternativa sem dependência: FFM chamando `sched_setaffinity` (~25 linhas), candidata ao passo 12. Isolamento real (`isolcpus`, cpuset) exige privilégios.

### Taxa constante (k6 `constant-arrival-rate`) e `-XX:+UseCompactObjectHeaders`

`RATE=<req/s> ./run.sh server N [vus]` usa modelo aberto (a taxa não depende da resposta), o que mede a latência do servidor sem a fila do gerador. DELAY_MS=1, N=100k, k6 em CPUs 1-3, 0 falhas, 0 GCs (`dropped` = iterações que o k6 não conseguiu iniciar a tempo):

| RATE | fase | req/s | p50 / p99 ms | dropped |
|---|---|---|---|---|
| 4000 | write / read / mixed | 3,9k / 4,0k / 3,9k | 1,8/32 · 0,3/8,5 · 0,6/28 | 2,0k / 0,4k / 2,1k |
| 8000 | write / read / mixed | 7,4k / 7,3k / 7,3k | 3,2/47 · 2,8/44 · 1,9/36 | 8,2k / 9,1k / 8,7k |

Com 4k req/s a mediana de leitura é 0,3 ms; o p99 de dezenas de ms vem de pausas do próprio k6/SMT (CPU 1 irmã do servidor), não de GC.

Compact Object Headers (JDK 25, `JAVA_EXTRA=-XX:+UseCompactObjectHeaders TAG=coh`), RATE=4000: RSS 218,3 MB (base) vs 221,0 MB (COH); mesma vazão e latência dentro do ruído. **Sem queda de memória**: o heap de 128 MB é pré-tocado (`AlwaysPreTouch`) e os dados vivem off-heap, então não há objetos em volume para o cabeçalho menor economizar; o COH só ajuda aplicações com muitos objetos pequenos na heap.

## Passo 11: concorrência x layout de memória (mesma API, mesmo k6)

`./run.sh compare 60000 50` roda 9 execuções (`loop-off`, `vt|fixed|cached` x `heap|off`, `fixed` com pool 200 e 16): 1 vCPU, `-Xmx128m -Xms128m`, JFR, aquecimento de 10k ciclos, k6 em taxa constante (`RATE=2000`, delay 20 ms, 50 VUs). Pastas: `results/compare/raw/` (ignorada) e [results/compare/summary.md](results/compare/summary.md) (tabelas completas, geradas por `bench/summarize.py`). `IMPL=<modelo>-<memória> ./run.sh server N` roda uma célula só.

- **loop-off**: o servidor flat (event loop NIO + off-heap). **\*-heap**: `HttpServer` do JDK + Jackson + records + `ConcurrentHashMap`. **\*-off**: o mesmo `HttpServer`, mas JSON flat, tabelas off-heap com `StampedLock` e pool limitado de contextos de requisição (sem `ThreadLocal`). O teste de contrato (`AgendamentoContractTest`) garante respostas idênticas nas 7 variantes.

| impl | GCs na carga | pausa total | alloc KB/req (JFR, estimativa) | RSS MB | threads | CPU JVM |
|---|---|---|---|---|---|---|
| loop-off | 0 | 0 ms | ~0 | 233 | 16 | 3,1% |
| vt-off | 69 | 47 ms | 13,6 | 258 | 21 | 7,6% |
| fixed-off (200) | 71 | 94 ms | 12,3 | 280 | 218 | 9,6% |
| cached-off | 66 | 65 ms | 12,5 | 273 | 68 | 8,7% |
| vt-heap | 418 | 496 ms | 82,7 | 236 | 21 | 9,0% |
| fixed-heap (200) | 367 | 553 ms | 62,3 | 265 | 218 | 12,6% |
| cached-heap | 321 | 458 ms | 62,4 | 246 | 71 | 10,0% |
| fixed-off (16) | 51 | 48 ms | 12,4 | 265 | 34 | 6,1% |
| fixed-heap (16) | 178 | 224 ms | 43,5 | 236 | 34 | 6,9% |

Leitura:
- **Latência não discrimina a 2k req/s**: write p50 ~20,5 ms (o delay) e read p50 0,24–0,43 ms em todos. O p99 de leitura vai de 0,95 ms (loop-off) a 4,3 ms (fixed-heap 200).
- **Exceção: `fixed` com pool 16** limita o write a pool/delay = 16/20 ms = ~800 req/s (p50 254 ms, 36k iterações perdidas). É o efeito do ciclo 1: pool pequeno com tarefa bloqueante. (Nesses casos a fase `read` lista dias que não chegaram a ser escritos, então ela fica mais leve que o normal.)
- **Alocação**: heap ~62–83 KB/req e centenas de GCs; off-heap com threads cai para ~12–14 KB/req (5–6x menos) e dezenas de GCs; o que sobra é o **piso do `HttpServer` do JDK** (exchange, headers, streams), que o layout de memória não elimina. Só o servidor flat chega a ~0 e a 0 GCs na carga.
- **Off-heap é uma técnica ortogonal à concorrência** e vale com modelos tradicionais, mas sozinha não zera a alocação enquanto o transporte for o do JDK; e o custo aparece em RSS (+20–45 MB de contextos e tabelas off-heap contra o heap fixo de 128 MB).
- **RSS**: o loop-off é o menor da classe off-heap (233 MB); os `*-off` com threads ficam em 258–280 MB. O heap pré-tocado (128 MB) é comum a todos.
- **Achado de configuração**: o `HttpServer` do JDK vem sem `TCP_NODELAY`; headers e body saem em segmentos separados e o delayed-ACK adiciona ~40 ms às respostas com corpo. O `server.sh` usa `-Dsun.net.httpserver.nodelay=true` nas variantes do JDK (sem isso o p50 de leitura era 42 ms).
- Cuidados: uma execução por célula, laptop com SMT, k6 em CPUs 1-3 (irmã SMT do servidor); a estimativa de alocação do JFR é estatística e `jdk.CPULoad` é relativo ao que a JVM enxerga.

### Heap menor e ZGC (N=20.000, 1 vCPU, RATE 2000, uma execução por célula)

Parâmetros do `server.sh`/`compare.sh`: `HEAP` (MB, padrão 128) e `GC` (`serial|zgc|g1|parallel`; sem ele a JVM escolhe, Serial em 1 vCPU). Os nomes dos arquivos ganham `-h<HEAP>` e `-<GC>`. O `summarize.py` conta GCs pelo JFR, porque o `-Xlog:gc` do ZGC não imprime "Pause". Use um N diferente do `summary.md` versionado para não misturar execuções.

| impl | GCs (carga) | pausa ms | RSS MB | threads |
|---|---|---|---|---|
| loop-off, serial, 64m | 5 | 3,5 | 170 | 16 |
| loop-off, serial, 128m | 1 | 0,2 | 232 | 16 |
| loop-off, ZGC, 128m | 0 | 0 | 241 | 24 |
| vt-heap, serial, 128m | 138 | 150,8 | 232 | 21 |
| vt-heap, ZGC, 128m | 94 | 2,6 | 258 | 30 |

- O RSS cai quase 1:1 com o `-Xms` (pre-touch): 128m para 64m tira ~60 MB, sem mudar a latência do loop-off.
- ZGC no loop-off não ajuda (não há o que coletar) e custa +10–20 MB de RSS e +8 threads. No vt-heap as pausas somem (2,6 ms), mas o trabalho de GC vira ciclos concorrentes na mesma CPU (94 ciclos, `alloc` maior), então a latência não melhora em 1 vCPU.
- `-XX:MaxDirectMemorySize` limita só `DirectByteBuffer`; o dimensionamento do container deve partir do RSS medido, não do valor do off-heap.

## Limitações

- Uma execução por ponto; sem repetições, mediana ou dispersão. As diferenças de tempo entre as variantes flat (1,06–1,14s) e o ganho de tempo do Compact Object Headers não são conclusivos.
- O servidor flat do ciclo 2 aloca por conexão (JDK NIO) e fecha a conexão se a resposta não couber no buffer de escrita; a lista é truncada pela capacidade do corpo.
- Sem GraalVM e imagens nativas. Epoll fora de escopo; io_uring é o candidato para I/O assíncrono real.

## Próximo ciclo

Passo 11: comparar com `HttpServer` do JDK e virtual threads sob a mesma carga; passo 12: variante FFM.
