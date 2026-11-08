# virtual-threads-benchmark (branch `custom-nio-http-feature`)

Servidor HTTP próprio de agendamento, **single-thread, NIO, memória plana e zero alocação**, em **1 vCPU** (`taskset -c 0`) e **heap de 128m** (`-Xmx128m -Xms128m`), JDK 25 (Temurin). Backends off-heap: `DirectByteBuffer`, FFM (`MemorySegment`) e, em imagem nativa, `NativeRegion` (Native Image SDK). Cobre os ciclos 2 (servidor flat) e 3 (GraalVM native-image).

> Esta é uma de três branches: `main` (benchmark de concorrência, ciclo 1), `custom-nio-http-feature` (esta) e `custom-http-feature` (baseline com o `HttpServer` do JDK, virtual threads e pools, usado como ponto de comparação). Os números de comparação com o baseline estão na README daquela branch. A tag `ciclo-1-3-completo` guarda o repositório inteiro antes da separação. Resultados (`results/`, `jfr/`) não são versionados: são gerados pelos scripts.

> Os números abaixo vêm de **uma única execução** por ponto, salvo onde há repetições. Servem para ordem de grandeza.

## Como rodar

```bash
./run.sh server 100000 [vus=50]        # teste de carga real do servidor de agendamento (k6)
VUS=200 DELAY_MS=50 WARM=20000 PHASES=write,read ./run.sh server 10000
LOAD_CPU=1-3 DELAY_MS=1 ./run.sh server 100000 200
./run.sh repeat 40000 5                # loop-off direct/ffm repetido (CELLS, SUMMARY), mediana [min..max]
./run.sh jmh                           # AccessBench: custo de acesso (Region direct/ffm, StructLayout)
./run.sh native-build                  # target/native/agendamento (musl estático; LINK=mostly, OPT=3)
```

Requer k6 no PATH. Testes: `mvn test` e `mvn test -DargLine="-Dflat.memory=ffm --enable-native-access=ALL-UNNAMED"`.

## Estrutura de pacotes

```
dev.villani
├── server                 infraestrutura HTTP flat, reutilizável e sem conhecer o domínio
│   ├── memory             Region (direct|ffm|native), IntRing, ByteRing, ReadyQueue, ConnectionPool
│   ├── net                NioServer (Selector, backpressure, ganchos de tick/close)
│   ├── http               parser, Router, Dispatcher, ResponseWriter, AsyncHandler, HttpConnectionHandler
│   ├── json               tokenizador, binder para registro pooled, JsonWriter
│   ├── os                 CpuAffinity (FFM) e NativeAffinity (@CFunction em imagem nativa)
│   └── time               TimerWheel plana
└── application
    └── agendamento        tabela off-heap, regras, handlers, AgendamentoServer (exemplo de uso do servidor)
```

Regra: `server` nunca importa `application` (verificado por `PackageDependencyTest`). Testes seguem o mesmo layout, em estilo BDD (given/when/then), com `AllocationProbe` em `dev.villani.testsupport`.

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

### Heap menor e ZGC (N=20.000, 1 vCPU, RATE 2000, uma execução por célula)

Parâmetros do `server.sh`: `HEAP` (MB, padrão 128) e `GC` (`serial|zgc|g1|parallel`; sem ele a JVM escolhe, Serial em 1 vCPU). Os nomes dos arquivos ganham `-h<HEAP>` e `-<GC>`.

| variante | GCs (carga) | pausa ms | RSS MB | threads |
|---|---|---|---|---|
| loop-off, serial, 64m | 5 | 3,5 | 170 | 16 |
| loop-off, serial, 128m | 1 | 0,2 | 232 | 16 |
| loop-off, ZGC, 128m | 0 | 0 | 241 | 24 |

- O RSS cai quase 1:1 com o `-Xms` (pre-touch): 128m para 64m tira ~60 MB, sem mudar a latência do loop-off.
- ZGC no loop-off não ajuda (não há o que coletar) e custa +10–20 MB de RSS e +8 threads.
- `-XX:MaxDirectMemorySize` limita só `DirectByteBuffer`; o dimensionamento do container deve partir do RSS medido, não do valor do off-heap.


## Passo 12: backend FFM (`MemorySegment`) e thread affinity

- **`server.memory.Region`**: interface mínima de memória off-heap (`get/put`, `getInt/putInt`, `getLong/putLong`, `asByteBuffer()`), com dois backends: `DirectRegion` (`ByteBuffer.allocateDirect`) e `FfmRegion` (`Arena.global().allocate` + `MemorySegment`). `Regions` escolhe por `-Dflat.memory=direct|ffm` (padrão `direct`). As estruturas de dados (`AgendamentoTable`, `IntRing`, `ReadyQueue`, flags do `ConnectionPool`) usam os acessores; os buffers de I/O (slab de conexões, corpo da resposta, rings por requisição) usam `Regions.allocateBuffer`, uma view `ByteBuffer` sobre a mesma memória, porque o NIO só aceita `ByteBuffer`.
- **`server.os.CpuAffinity`**: `sched_setaffinity` via FFM (`Linker.nativeLinker()`, sem JNI nem biblioteca extra), para a thread que chama. `-Dflat.pin=<cpu>` faz o `AgendamentoServer` pinar a thread do loop no boot. Diferente do `taskset`, só a thread do loop fica presa; GC, JIT e JFR podem usar outras CPUs (só faz diferença com 2+ CPUs).
- `server.sh`: `MEM_API=direct|ffm` e `PIN=<cpu>` (precisa estar dentro de `SERVER_CPU`); os nomes dos arquivos ganham `-ffm` e `-pin<cpu>`. Testes: `mvn test` e `mvn test -DargLine="-Dflat.memory=ffm --enable-native-access=ALL-UNNAMED"` (152 testes verdes nos dois).

Resultado (1 vCPU, 128m, RATE 2000, N=40000, uma execução por célula):

| impl | p99 write ms | p99 read ms | GCs (carga) | NMT Other MB | RSS MB |
|---|---|---|---|---|---|
| loop-off direct | 21,9 | 0,74 | 0 | 34 | 236 |
| loop-off ffm | 22,4 | 0,96 | 0 | 34 | 247 |
| loop-off ffm + pin 0 | 22,6 | 0,93 | 1 | 34 | 249 |

- **Sem diferença de alocação nem de memória de dados**: o FFM não alocou mais no heap (0 GCs no loop-off) e os dados ocupam os mesmos 34 MB (NMT `Other`). O FFM **não** entra no `BufferPool direct` nem no `MaxDirectMemorySize`; só no NMT e no RSS.
- **RSS +5–11 MB** com FFM (infraestrutura do linker e dos `MethodHandle`s). O p50 é igual; as diferenças de p99 estão no ruído de uma execução.
- **Pin com 1 CPU é no-op**: o resultado valida o mecanismo (a thread foi pinada), mas o ganho só aparece com 2+ CPUs, ainda por medir.

### Passo 12b: repetições, JMH e struct layout

Uma execução só não separa efeito de ruído, então: `./run.sh repeat [N] [reps]` (env `CELLS`; mediana [min..max], `results/repeat/summary.md`) e `./run.sh jmh` (`AccessBench`, fixado na CPU 0).

**JMH, custo de acesso (ns/op, 2 forks)**: 256 registros (quente, L1) / 200000 (frio, 12,8 MB).

| acesso | quente | frio |
|---|---|---|
| `ByteBuffer` direto | 7,5 | ~31,5 |
| `MemorySegment`, offsets manuais | 7,6 | 31,7 |
| `MemorySegment` + `StructLayout`/`VarHandle` | 7,4 | ~29,4 |
| `Region` direct | 7,55 | 31,7 |
| `Region` ffm | 7,8 | 33,4 |

O custo é dominado por cache miss; as APIs empatam (ffm ≈ +0,25 ns quente, +1,7 ns frio, ~2σ).

**5 repetições (N=40000, mediana)**:

| impl | mem | p99 write | p99 read | RSS MB |
|---|---|---|---|---|
| loop-off | direct | 22,55 | 1,11 | 234 |
| loop-off | ffm | 22,67 | 0,96 | 242 |

Os intervalos de p99 se sobrepõem, então "ffm sempre pior" era ruído. O único custo consistente é **RSS +8 MB no loop-off** (infraestrutura FFM, causa não verificada).

**Struct layout** (`AgendamentoTable` e `JsonRecord`):
- O `StructLayout` dá clareza e segurança (offsets calculados, sem números mágicos), **não velocidade**: o JMH mostra empate com offsets manuais. Os offsets saem do layout como `static final int` e o acesso passa pelo `Region`, então um código só serve aos dois backends (`DirectByteBuffer` não tem `StructLayout`).
- `AgendamentoTable`: registro de 64 B declarado como struct, offsets idênticos aos anteriores.
- `JsonRecord`: antes 4 arrays na heap por instância; agora flyweight sobre um slab off-heap único do `JsonRecordPool`, com layout por schema (`JsonRecordLayout`).
- Pós-struct (3 reps, loop-off): p99 write 21,85 (direct) / 22,45 (ffm), p99 read 0,86 / 0,90, RSS 234 / 242 MB, ou seja, sem regressão.
- JFR (`ObjectAllocationSample`): as amostras e os 13 GCs ficam nos ~3 s de warm-up (`BufferedInputStream`, `readNBytes`, `Socket.getRemoteSocketAddress`, do cliente de warm-up); **zero amostras e zero GCs durante a carga**, nos dois backends.
- JSON/HTTP continuam fora do escopo do struct (candidatos só parciais).

**Cenário saturado** (`DELAY_MS=1 RATE=8000 ./run.sh repeat 60000 3`, `results/repeat/summary-saturated.md`; mediana [min..max]):

| impl | mem | p50 write | p99 write | p99 read | RSS MB |
|---|---|---|---|---|---|
| loop-off | direct | 1,40 | 7,4 [5,9..7,6] | 6,4 | 237 |
| loop-off | ffm | 1,44 | 8,6 [8,3..10,0] | 9,7 | 246 |

- Sem o delay de 20 ms escondendo a espera, o loop-off tem p99 de 7–9 ms. O ffm ficou um pouco pior (p99 write +1,2 ms, read +3,3 ms), coerente com o custo de acesso do JMH e o +8 MB de RSS, mas são só 3 repetições.
- Para confirmar o ffm pior no loop-off seriam necessárias mais repetições (~5) e uma taxa maior. A comparação com vt-off (15–21 ms) está na branch `custom-http-feature`.

## Ciclo 3: GraalVM native-image (estático)

GraalVM CE 25.0.2, `--gc=serial -O3`, sem PGO (só Oracle GraalVM). `./run.sh native-build` gera `target/native/agendamento`; `LINK=musl` (padrão, `--static --libc=musl`) ou `LINK=mostly` (`--static-nolibc`, glibc dinâmica), `OPT=` troca o nível. `NATIVE=1` no `server.sh` e células `impl:mem[:musl|mostly]` no `repeat.sh` rodam com os mesmos `-Xmx/-Xms128m` e `taskset` de 1 vCPU (sem JFR/NMT; RSS via `/proc`). Resultados: 3 repetições, 2000/s com delay 20 ms (`./run.sh repeat 40000 3`); os arquivos gerados em `results/` não são versionados.

**Backends em imagem nativa**
- **FFM `MemorySegment` é lento em nativo**, e não por causa do downcall (issue [graal#12219](https://github.com/oracle/graal/issues/12219) trata do custo de *downcall*): num micro com 4 acessos a uma `Region` por iteração, `ffm` ≈ 470 ns, `direct` ≈ 13 ns, `native` ≈ 6,7 ns (na JVM o ffm é só ~2x o direct). `VarHandle` `static final` não ajudou.
- **`NativeRegion`** (`-Dflat.memory=native`, só em imagem): `UnmanagedMemory.calloc` + `Pointer.readInt/writeInt` do Native Image SDK (Word API). Limitado a <2 GB, sem free.
- **`NativeAffinity`**: `@CFunction` para `sched_setaffinity`/`sched_getaffinity`. Resolvido em link estático, então o **pin funciona em imagem estática musl** (o FFM `defaultLookup()` falha com `libc.so.6`). SDK `provided`, só referenciado dessas duas classes.

**Cenário 2000/s (mediana, loop-off):**

| variante | RSS MB | p50 read ms | p99 read ms | p99 write ms |
|---|---|---|---|---|
| JVM direct | 237 | 0,20 | 1,24 | 22,1 |
| musl direct | 70 | 0,25 | 1,44 | 22,0 |
| musl **native** | **47** | 0,24 | **0,95** | 22,0 |
| mostly direct | 74 | 0,25 | 1,39 | 21,7 |
| mostly native | 50 | 0,24 | 1,33 | 23,0 |

Conclusões:
- **Memória:** RSS cai de ~237 MB para 47–74 MB; `NativeRegion` é o menor (47–50 MB loop-off). Parte da queda é ausência de `AlwaysPreTouch`. Binário de ~17 MB.
- **`-O3` + `NativeRegion` empata com a JVM** em baixa carga no loop-off (p50 0,24 vs 0,20, p99 read igual ou melhor) com ~5x menos RSS. Sob saturação, sem JIT/PGO, o nativo não vence a JVM (rodadas saturadas ruidosas; ver branch `custom-http-feature` para vt-off/vt-heap em nativo).
- **musl vs mostly-static:** latência equivalente dentro do ruído; musl usa ~3–4 MB menos RSS e não depende de glibc no destino. Mostly só se justifica se precisar de recursos da glibc (DNS/NSS, `dlopen`).
- **Evitar FFM em nativo**; use `direct` ou `native` (`@CFunction`/Word API).
- **Boot:** a coluna "boot to listening" inclui warm-up com resolução de 1 s; não serve como boot frio.

### Sem pre-touch, GC e sem warm-up (loop-off flat, delay 1 ms, 8000/s, N=60000, 3 reps)

`PRETOUCH=` (vazio) desliga `-XX:+AlwaysPreTouch` no `server.sh`; `WARM=0` desliga o warm-up; `NATIVE_JFR=1` usa `target/native/agendamento-jfr` (build com `NI_EXTRA=--enable-monitoring=jfr BIN_SUFFIX=-jfr`) com `-XX:+PrintGC -XX:+VerboseGC` e JFR. Resultados: `results/repeat/summary-nopretouch.md` (com warm-up) e `summary-nowarm.md`.

| variante | RSS MB (warm → sem warm) | p50 read ms | p99 write ms (warm → sem warm) |
|---|---|---|---|
| JVM direct | 145 → 108 | 0,18 → 0,18 | 8,0 → 12,8 |
| musl direct | 70 → 30 | 0,24 → 0,23 | 6,4 → 6,3 |
| musl native | 49 → **21** | 0,22 → 0,22 | 6,9 → 5,9 |

- Sem pre-touch a JVM cai de ~237 para 145 MB; o nativo continua ~3x menor.
- **Warm-up:** no nativo a latência não muda (sem curva de aquecimento no AOT). Na JVM o p50 não muda, mas o p99 de escrita sobe (~8 → ~12,8 ms), compatível com o JIT ainda compilando.
- **GC:** com warm-up há 13 pausas na JVM (Young, 0,25-8 ms) e 16 no nativo (Incremental 0,3-0,8 ms, Full 0,8-2,8 ms), todas nos primeiros ~2-3,4 s, antes da carga. Com `WARM=0` há zero pausas e zero eventos `jdk.GarbageCollection` (JVM e nativo). Durante a carga não há GC.
- O RSS baixo do nativo sem warm-up (21 MB) vem de o warm-up tocar ~30 MB de eden com dados falsos.
- Limitações: 3 repetições (1 com JFR no nativo, só para GC), k6 na mesma máquina; JFR ligado altera um pouco a latência, então ela não foi comparada.

## Conclusão (ciclos 2 e 3)

**Ciclo 2: servidor flat, zero alocação, off-heap**
- O loop NIO single-thread tem a melhor cauda, e o pin em 1 vCPU elimina a disputa.
- Com off-heap, `StructLayout` e pooling, o JFR mostra zero alocação durante a carga.
- `direct` (`ByteBuffer`) fica ligeiramente à frente do FFM na JVM; o FFM é eficiente mesmo assim.
- O `AlwaysPreTouch` explica ~90 MB do RSS: sem ele a JVM vai de 237 para ~145 MB.

**Ciclo 3: GraalVM native-image**
- Imagens estáticas musl e `-O3` funcionam, e o pin funciona via `@CFunction`.
- O FFM `MemorySegment` é lento em nativo (~470 ns contra ~13 ns do `direct` no micro). A causa é o acesso ao segmento, não o downcall da issue #12219. O `NativeRegion` (Word API) é o backend certo em nativo (~6,7 ns).
- RSS do loop-off nativo: 21-49 MB, contra 108-145 MB na JVM sem pre-touch.
- A latência empata com a JVM aquecida, e o nativo não precisa de warm-up. O GC não pausa durante a carga em nenhum dos dois.
- Musl e mostly-static são equivalentes em latência.

**Limites do estudo:** 1 vCPU, k6 na mesma máquina e 3 repetições (diferenças abaixo de ~1 ms não são conclusivas); PGO (Oracle GraalVM) não testado; boot frio não medido com resolução fina.

## Limitações

- O servidor flat aloca por conexão (JDK NIO) e fecha a conexão se a resposta não couber no buffer de escrita; a lista é truncada pela capacidade do corpo.
- Epoll fora de escopo; io_uring é o candidato para I/O assíncrono real (branch futura `custom-iouring-feature`).
