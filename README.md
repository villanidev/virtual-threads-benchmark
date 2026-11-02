# virtual-threads-benchmark

Estudo de modelos de concorrência e alocação de memória em hardware escasso: **1 vCPU** (`taskset -c 0`) e **heap de 128m** (`-Xmx128m -Xms128m`), JDK 25 (Temurin). Cada task simula uma chamada bloqueante (`sleep`).

> Todos os números abaixo vêm de **uma única execução** por ponto. Servem para ordem de grandeza, não para comparar diferenças pequenas.

## Como rodar

```bash
mvn -q compile
./run.sh <modelo> <N> [cpu=0]          # JFR em jfr/<modelo>-<N>.jfr
POOL=20 SLEEP_MS=50 ./run.sh vfixed 20000
JAVA_EXTRA=-XX:+UseCompactObjectHeaders TAG=coh ./run.sh virtual 50000
```

Modelos do [Bench.java](src/main/java/dev/villani/benchmark/Bench.java): `cached`, `fixed` (pool=`POOL`, padrão 500), `vfixed` (pool fixo de virtual threads), `virtual`, `flat`, `flat-dbb`, `flat-nio`, `vsem-heap`, `vsem-slab`. Saída: tempo, bytes alocados pela thread main, GC e pico de heap usada. As classes `CachedThreadPool`, `FixedThreadPool`, `VirtualThreadPool` e `VirtualThreadPoolWithSemaphore` (em `benchmark/executors`) são os experimentos originais, só movidas de pacote.

## Estrutura de pacotes

```
dev.villani
├── benchmark              Bench (ponto de entrada dos experimentos)
│   ├── executors          pools de threads e variantes com semáforo
│   └── eventloop          loops flat (FFM, DirectByteBuffer, NIO Selector)
└── server                 infraestrutura HTTP flat, reutilizável e sem conhecer o domínio (ciclo 2)
    ├── memory             IntRing, ConnectionPool (memória plana)
    └── ...                adiante: net, http, json, routing, scheduling
└── application            casos de uso que consomem o server (ciclo 2)
    └── agendamento        handlers, repository, rules (API de agendamento)
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

## Limitações

- Uma execução por ponto; sem repetições, mediana ou dispersão. As diferenças de tempo entre as variantes flat (1,06–1,14s) e o ganho de tempo do Compact Object Headers não são conclusivos.
- O modelo flat só foi validado para temporização. Parsing, I/O real e lógica de negócio sem objetos por requisição ainda não foram implementados.
- Sem GraalVM e imagens nativas. Epoll fora de escopo; io_uring é o candidato para I/O assíncrono real.

## Próximo ciclo

Implementações flat de parsing, I/O e lógica de negócio com alocação zero por requisição.
