# virtual-threads-benchmark: baseline com `HttpServer` do JDK

Branch `custom-http-feature`. Servidor da API de agendamento sobre o `com.sun.net.httpserver.HttpServer` do JDK, para comparar **modelo de concorrência x layout de memória** em hardware escasso: **1 vCPU** (`taskset -c 0`), **heap de 128m** (`-Xmx128m -Xms128m`), JDK 25 (Temurin), JFR e k6. É a referência ("baseline") contra a qual o servidor próprio da branch `custom-nio-http-feature` é medido.

> Números de **uma única execução** por ponto (salvo onde indicado): ordem de grandeza, não diferenças pequenas. `results/` não é versionado: rode os comandos abaixo para gerar de novo.

## Branches (um experimento por branch)

| branch | conteúdo |
|---|---|
| `main` | benchmark de modelos de concorrência e memória (ciclo 1) |
| `custom-nio-http-feature` | servidor HTTP próprio, NIO, off-heap, zero alocação, GraalVM (ciclos 2 e 3). Os números do `loop-off` estão lá |
| `custom-http-feature` | **esta**: baseline com `HttpServer` do JDK |
| `custom-iouring-feature` | (futura) io_uring |

## Como rodar

```bash
./run.sh server 60000 [vus=50]            # uma célula: IMPL=vt-off|vt-heap|fixed-heap|fixed-off|cached-heap|cached-off (padrão vt-off)
IMPL=vt-heap DELAY_MS=1 WARM=10000 ./run.sh server 40000
./run.sh compare 60000 50                 # matriz concorrência x memória (results/compare/summary.md)
./run.sh repeat 40000 5                   # repetições, mediana/min/max (results/repeat/summary.md)
./run.sh native-build                     # imagem nativa estática: target/native/baseline (GraalVM CE)
NATIVE=1 IMPL=vt-off ./run.sh server 40000
mvn test                                  # unidades + contrato (6 variantes devem responder bytes idênticos)
```

Variáveis do [server.sh](bench/server.sh): `RATE`, `VUS`, `DELAY_MS`, `WARM`, `PHASES`, `POOL`, `HEAP`, `GC`, `MEM_API=direct|ffm|native`, `PRETOUCH` (vazio desliga), `NATIVE`, `NATIVE_JFR`, `SERVER_CPU`, `LOAD_CPU`.

## Estrutura de pacotes

```
dev.villani
├── baseline               BaselineServer (HttpServer do JDK) + ConcurrencyModel (VIRTUAL | FIXED | CACHED)
│   ├── heap               HeapController: Jackson, records, ConcurrentHashMap
│   └── offheap            OffHeapController: JSON flat, SharedTable off-heap com StampedLock, pool de RequestContext
├── application.agendamento  domínio (AgendamentoTable/Rules/Dias/WarmUp/Limits), cópia mínima: exemplo de uso
└── server
    ├── http               DelayPolicy
    ├── json               leitor/escritor JSON sem alocação
    └── memory             Region (direct | ffm | native), ByteRing, IntRing
```

`server` não importa `application`. O `application.agendamento` aqui é só o domínio compartilhado pelas variantes; o servidor HTTP (e seu roteamento) vem do JDK.

## Passo 11: concorrência x layout de memória (mesma API, mesmo k6)

`./run.sh compare 60000 50` roda 8 execuções (`vt|fixed|cached` x `heap|off`, `fixed` com pool 200 e 16): 1 vCPU, `-Xmx128m -Xms128m`, JFR, aquecimento de 10k ciclos, k6 em taxa constante (`RATE=2000`, delay 20 ms, 50 VUs). Pastas: `results/compare/raw/` e `results/compare/summary.md` (gerado por `bench/summarize.py`; nada disso é versionado, gere de novo). `IMPL=<modelo>-<memória> ./run.sh server N` roda uma célula só.

- **\*-heap**: `HttpServer` do JDK + Jackson + records + `ConcurrentHashMap`. **\*-off**: o mesmo `HttpServer`, mas JSON flat, tabelas off-heap com `StampedLock` e pool limitado de contextos de requisição (sem `ThreadLocal`). O teste de contrato (`AgendamentoContractTest`) garante respostas idênticas nas 6 variantes.

| impl | GCs na carga | pausa total | alloc KB/req (JFR, estimativa) | RSS MB | threads | CPU JVM |
|---|---|---|---|---|---|---|
| vt-off | 69 | 47 ms | 13,6 | 258 | 21 | 7,6% |
| fixed-off (200) | 71 | 94 ms | 12,3 | 280 | 218 | 9,6% |
| cached-off | 66 | 65 ms | 12,5 | 273 | 68 | 8,7% |
| vt-heap | 418 | 496 ms | 82,7 | 236 | 21 | 9,0% |
| fixed-heap (200) | 367 | 553 ms | 62,3 | 265 | 218 | 12,6% |
| cached-heap | 321 | 458 ms | 62,4 | 246 | 71 | 10,0% |
| fixed-off (16) | 51 | 48 ms | 12,4 | 265 | 34 | 6,1% |
| fixed-heap (16) | 178 | 224 ms | 43,5 | 236 | 34 | 6,9% |

Leitura:
- **Latência não discrimina a 2k req/s**: write p50 ~20,5 ms (o delay) e read p50 0,24–0,43 ms em todos. O p99 de leitura chega a 4,3 ms no fixed-heap (200).
- **Exceção: `fixed` com pool 16** limita o write a pool/delay = 16/20 ms = ~800 req/s (p50 254 ms, 36k iterações perdidas). É o efeito do ciclo 1: pool pequeno com tarefa bloqueante. (Nesses casos a fase `read` lista dias que não chegaram a ser escritos, então ela fica mais leve que o normal.)
- **Alocação**: heap ~62–83 KB/req e centenas de GCs; off-heap com threads cai para ~12–14 KB/req (5–6x menos) e dezenas de GCs; o que sobra é o **piso do `HttpServer` do JDK** (exchange, headers, streams), que o layout de memória não elimina. Zerar exige transporte próprio (`custom-nio-http-feature`).
- **Off-heap é uma técnica ortogonal à concorrência** e vale com modelos tradicionais, mas sozinha não zera a alocação enquanto o transporte for o do JDK; e o custo aparece em RSS (+20–45 MB de contextos e tabelas off-heap contra o heap fixo de 128 MB).
- **RSS**: os `*-off` com threads ficam em 258–280 MB e os `*-heap` em 236–265 MB; o heap pré-tocado (128 MB) é comum a todos. O off-heap custa RSS (+20–45 MB de contextos e tabelas).
- **Achado de configuração**: o `HttpServer` do JDK vem sem `TCP_NODELAY`; headers e body saem em segmentos separados e o delayed-ACK adiciona ~40 ms às respostas com corpo. O `server.sh` usa `-Dsun.net.httpserver.nodelay=true` nas variantes do JDK (sem isso o p50 de leitura era 42 ms).
- Cuidados: uma execução por célula, laptop com SMT, k6 em CPUs 1-3 (irmã SMT do servidor); a estimativa de alocação do JFR é estatística e `jdk.CPULoad` é relativo ao que a JVM enxerga.

### Heap menor e ZGC (N=20.000, 1 vCPU, RATE 2000, uma execução por célula)

Parâmetros do `server.sh`/`compare.sh`: `HEAP` (MB, padrão 128) e `GC` (`serial|zgc|g1|parallel`; sem ele a JVM escolhe, Serial em 1 vCPU). Os nomes dos arquivos ganham `-h<HEAP>` e `-<GC>`. O `summarize.py` conta GCs pelo JFR, porque o `-Xlog:gc` do ZGC não imprime "Pause".

| impl | GCs (carga) | pausa ms | RSS MB | threads |
|---|---|---|---|---|
| vt-heap, serial, 128m | 138 | 150,8 | 232 | 21 |
| vt-heap, ZGC, 128m | 94 | 2,6 | 258 | 30 |

- O RSS cai quase 1:1 com o `-Xms` (pre-touch): 128m para 64m tira ~60 MB (medido no servidor próprio, ver `custom-nio-http-feature`).
- No vt-heap com ZGC as pausas somem (2,6 ms), mas o trabalho de GC vira ciclos concorrentes na mesma CPU (94 ciclos, `alloc` maior), então a latência não melhora em 1 vCPU; custa +10–20 MB de RSS e +8 threads.
- `-XX:MaxDirectMemorySize` limita só `DirectByteBuffer`; o dimensionamento do container deve partir do RSS medido, não do valor do off-heap.


## Ciclo 3 (resumo): GraalVM native-image nas variantes do JDK

`bench/native-build.sh` gera `target/native/baseline` (`--gc=serial -O3`, `LINK=musl` estático por padrão ou `LINK=mostly`). **Jackson (vt-heap)** precisa de reflection config nos records de `HeapController` (`bench/native/.../reachability-metadata.json`).

- vt-off: RSS 260 MB (JVM) → 44 MB direct / **40 MB** native (musl); p50 read 0,28 → 0,34 ms, p99 2,7 → 2,7 ms.
- vt-heap (Jackson sem JIT): p50 read 0,35 → 0,48 ms e p99 write 24 → 36 ms, pior que a JVM.
- Saturado (8000/s, delay 1 ms), muito ruidoso: vt-off nativo é pior em leitura (p50 ~37 ms contra 8 ms na JVM) e vt-heap também (p50 read 59–65 contra 31 ms).

## Conclusão

- **Latência não discrimina** a taxa moderada: o delay domina; modelo e memória aparecem em GCs, alocação e RSS.
- **Off-heap é ortogonal à concorrência** e corta a alocação ~5–6x (de ~62–83 para ~12–14 KB/req), mas o piso do `HttpServer` do JDK (exchange, headers, streams) permanece: só um transporte próprio chega a zero (ver `custom-nio-http-feature`).
- Virtual threads dão o mesmo throughput de um pool grande com 21 threads em vez de ~218, e não sofrem o teto `pool/delay` do `fixed` pequeno.
- Em native-image, off-heap reduz muito o RSS; o caminho com Jackson sem JIT perde para a JVM.

## Limitações

- Uma execução por célula (laptop i7 com SMT, k6 nas CPUs 1-3, irmã SMT do servidor); a alocação do JFR é estatística.
- O Jackson sem JIT no native-image como causa da lentidão do vt-heap é hipótese, não verificada.
