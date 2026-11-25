# Escala de um nó e durabilidade (0.9.0)

A versão 0.9.0 reescreve o caminho de armazenamento e de consulta de um único nó. Nenhuma configuração é obrigatória: o motor ajusta threads, caches e buffers sozinho a partir dos núcleos e da memória visíveis.

## Durabilidade: `AXON_STORAGE_SYNC`

O log de escrita antecipada (WAL, Write-Ahead Log) do RocksDB fica sempre ligado. A variável opcional `AXON_STORAGE_SYNC` (ou a propriedade `-Daxon.storage.sync`) escolhe quando o sistema operacional é forçado a gravar esse log em disco com fsync.

| Valor | Comportamento | Perda possível |
|---|---|---|
| `batch` (padrão) | fsync em segundo plano a cada 1 segundo | até cerca de 1 segundo de escritas, apenas em queda do sistema operacional ou da energia |
| `always` | fsync a cada escrita | nenhuma |
| `off` | sem fsync periódico (padrão do próprio RocksDB) | o que ainda estiver no cache do sistema operacional |

Uma queda apenas do processo do banco não perde nada em nenhum dos três modos, porque o WAL é gravado antes da confirmação.

No macOS o fsync completo (`F_FULLFSYNC`) custa cerca de 19 ms. Por isso `always` mede 50 operações por segundo com uma thread e 192 com oito. Em Linux o custo costuma ser bem menor, mas a medida ainda não foi feita. Escolha `always` quando a janela zero de perda for obrigatória e a vazão de escrita for baixa.

## O que mudou no caminho de escrita e de leitura

- O monitor global do armazenamento foi substituído por 1024 travas por faixa de chave e leituras por iterador sem trava.
- Valor e versão são gravados num único lote atômico. Cada comando de escrita (`CREATE`, `INSERT`, `UPDATE`, `DELETE`, `RELATE`, `UNRELATE`) grava registro, índices e arestas num único lote. Um comando que falha no meio não deixa linhas nem entradas de índice.
- `SELECT ... LIMIT` para de varrer ao completar o limite. `ORDER BY ... LIMIT` usa um heap de tamanho fixo (top-K).
- O índice GEO (de posição geográfica) guarda as coordenadas na própria entrada e responde "os N mais próximos" por expansão de anéis (`GEO_KNN` no `EXPLAIN`).
- Índices de vetor, contadores de versão e a trilha de auditoria têm custo constante por escrita. Antes, cada escrita listava o log inteiro.
- O caminho quente do `/sql` (impressão digital da consulta, geração de ids, buffer de log e cache do JWT (JSON Web Token, o token de login)) deixou de alocar e de travar por requisição.

## Medidas

Carga de 200 mil linhas, máquina de desenvolvimento macOS com a carga do sistema entre 10 e 18 durante a rodada.

| Teste | 0.8.0 | 0.9.0 (`batch`) |
|---|---|---|
| Ingestão, 1 thread | 6.183 ops/s | 14.505 ops/s |
| Ingestão, 8 threads | 10.340 ops/s | 42.982 ops/s |
| Ingestão com índice UNIQUE e coluna | 3.742 ops/s | 25.491 ops/s |
| Atualização de posição com índice GEO | 3.429 ops/s | 10.849 ops/s |
| Atualização, 4 threads | 1.828 ops/s | 10.963 ops/s |
| `SELECT * LIMIT 10` | 2.934 ms | 6,3 ms |
| Raio de 2 km, 4 threads | 8 ops/s (p50 409 ms) | 1.475 ops/s (p50 2,5 ms) |
| 20 mais próximos, 4 threads | não existia | 334 ops/s (p50 10 ms) |

### Modos de sincronização no macOS (200 mil linhas, em processo)

| Teste (ops/s) | `always` | `batch` | `off` |
|---|---|---|---|
| Ingestão, 1 thread | 50 | 14.505 | 13.948 |
| Ingestão, 8 threads | 192 | 42.982 | 48.132 |
| Ingestão com UNIQUE e coluna | 180 | 25.491 | 32.113 |
| Atualização, 4 threads | 95 | 10.963 | 16.105 |
| Posição com GEO, 8 threads | 184 | 10.849 | 28.899 |

### Carga HTTP em contêiner Linux

A carga usa HTTP (HyperText Transfer Protocol). Gerador de carga em Go (`scripts/loadtest`), 64 conexões, 15 segundos por cenário, imagem Docker no Docker Desktop do macOS (12 CPUs, 8 GB). O gerador roda no host, então o limite aqui é o caminho HTTP e o mapeamento de porta, não o armazenamento. A carga do sistema ficou perto de 20 durante a rodada.

| Cenário (ops/s) | 0.8.0 | 0.9.0 `batch` | 0.9.0 `always` | 0.9.0 `off` |
|---|---|---|---|---|
| Leitura por id | 4.280 | 4.292 | 4.327 | 4.592 |
| Atualização de status | 1.251 | 3.845 | 1.874 | 3.572 |
| Inserção de pedidos | 2.029 | 4.449 | 1.990 | 4.400 |
| Posição de motorista (GEO) | 1.785 | 4.010 | 2.055 | 4.195 |
| Motoristas próximos | 17 | 3.327 | 3.203 | 3.273 |
| Misto | 249 | 4.835 | 3.537 | 4.654 |

Em Linux o custo do `always` é bem menor do que no macOS: cerca de metade da vazão de escrita do `batch`, contra 0,3% a 1% no macOS. Ainda assim, `batch` é o padrão porque limita a perda a cerca de 1 segundo e quase não custa vazão.

Para reproduzir:

```bash
mvn -pl axonbase-core -am -Daxon.bench=true -Daxon.bench.rows=200000 -Dtest=ScaleBaselineBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

As linhas de resultado começam com `BASE`.

## Teste de portão de escala

O `ScaleGateTest` roda os cenários essenciais do benchmark e reprova quando uma meta é quebrada. Ele só roda com `-Daxon.gate=true`, então os testes normais não o executam. No CI (Continuous Integration, integração contínua), o workflow `.github/workflows/scale-gate.yml` o roda em pull requests que mexem no motor ou no servidor, em cada push na `main` e toda noite, e publica as linhas `BASE` como artefato.

As metas são razões e comparações normalizadas, para resistir ao ruído de executores compartilhados:

- ingestão com 8 threads pelo menos 2,0 vezes a de 1 thread;
- `SELECT * FROM t LIMIT 10` (segunda execução) abaixo de 50 ms;
- busca dos 20 mais próximos (índice GEO) com p99 abaixo de 100 ms;
- `ORDER BY valor DESC LIMIT 10` e `WHERE` sem casamento com `LIMIT 10`, sem índice, abaixo de 500 ms em 200 mil linhas;
- nenhuma métrica pode piorar mais de 25% frente à linha de base em `axonbase-core/src/test/resources/scale-baseline.json`. Cada métrica é dividida por um micro-benchmark de calibração de CPU (uma thread para as métricas de uma thread, todos os núcleos para as paralelas) medido na mesma execução. A razão de ingestão só é comparada com a linha de base quando o número de núcleos é o mesmo. As métricas em milissegundos são o melhor de 3 execuções, e uma piora relativa só reprova quando o valor bruto já passa de metade da meta, porque abaixo disso a variação é ruído de medida.

Quando uma meta falha, o teste repete a medição uma vez antes de reprovar.

Para rodar localmente:

```bash
mvn -pl axonbase-core -am -Daxon.gate=true -Dtest=ScaleGateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Para atualizar a linha de base depois de uma mudança intencional de desempenho (ou de trocar a classe de máquina do CI), gere o arquivo em Linux, que é o ambiente do CI, e versione o resultado:

```bash
docker run --rm -v "$PWD":/repo -w /repo maven:3.9-eclipse-temurin-21 \
  mvn -B -pl axonbase-core -am -Daxon.gate=true -Daxon.gate.update=true -Dtest=ScaleGateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

O arquivo guarda as métricas brutas e as normalizadas, o número de núcleos e as calibrações. Quando o executor do CI tiver um número de núcleos diferente do da linha de base, regrave-a em uma máquina com o mesmo número de núcleos (por exemplo, limitando o container com `--cpus`) para a comparação de razão voltar a valer.

## Limites conhecidos

- Os comandos que leem varreduras com leitura fantasma em transações explícitas ou no cluster ainda serializam, como o monitor antigo.
- Um comando muito grande guarda suas escritas em memória até o fim, proporcionalmente ao tamanho.
- O ganho multi-nó (grupo de commit no Raft, leituras em seguidores, múltiplos grupos Raft) não faz parte desta versão.
