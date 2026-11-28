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

Em cluster, o log do consenso tem configuração própria, `AXON_RAFT_LOG_SYNC` (`always` por padrão, ou `batch`), descrita em [cluster-raft.md](cluster-raft.md).

No macOS o fsync completo (`F_FULLFSYNC`) custa cerca de 19 ms. Por isso `always` mede 50 operações por segundo com uma thread e 192 com oito. Em Linux o custo costuma ser bem menor, mas a medida ainda não foi feita. Escolha `always` quando a janela zero de perda for obrigatória e a vazão de escrita for baixa.

## O que mudou no caminho de escrita e de leitura

- O monitor global do armazenamento foi substituído por 1024 travas por faixa de chave e leituras por iterador sem trava.
- Valor e versão são gravados num único lote atômico. Cada comando de escrita (`CREATE`, `INSERT`, `UPDATE`, `DELETE`, `RELATE`, `UNRELATE`) grava registro, índices e arestas num único lote. Um comando que falha no meio não deixa linhas nem entradas de índice.
- `SELECT ... LIMIT` para de varrer ao completar o limite. `ORDER BY ... LIMIT` usa um heap de tamanho fixo (top-K).
- O índice GEO (de posição geográfica) guarda as coordenadas na própria entrada e responde "os N mais próximos" sem varrer a tabela (`GEO_KNN` no `EXPLAIN`). Desde a E4 ele usa o H3: ver [indice-geografico.md](indice-geografico.md).
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

- razão de ingestão (8 threads contra 1 thread, melhor de 3 depois de um aquecimento) com piso de sanidade de 0,3. Em hardware dedicado (macOS, 12 núcleos) medimos cerca de 3 vezes, mas em Linux virtualizado e com o hospedeiro carregado medimos de 0,4 a 0,9 vezes, nos modos `batch` e `off`. Não dá para provar a meta de 2 vezes nesse ambiente, então ela é acompanhada e não reprovada; o caminho de escrita multi-thread é tratado na fase E2;
- `SELECT * FROM t LIMIT 10` (segunda execução) abaixo de 50 ms;
- busca dos 20 mais próximos (índice GEO) com p99 abaixo de 100 ms;
- `ORDER BY valor DESC LIMIT 10` e `WHERE` sem casamento com `LIMIT 10`, sem índice, abaixo de 500 ms em 200 mil linhas com 12 núcleos (a meta sobe até 2,5 vezes em máquinas menores, o que ainda reprova a volta à varredura serial de cerca de 2,4 s);
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
- O grupo de commit no Raft e as leituras em seguidores são da fase E2 (seção abaixo e [cluster-raft.md](cluster-raft.md)). Múltiplos grupos Raft ficam para a E3.

## Índice criado em segundo plano

`DEFINE INDEX` sobre uma tabela que já tem dados não bloqueia as escritas. Tabelas com até
`AXON_INDEX_INLINE_ROWS` linhas (padrão 10000) constroem o índice no próprio comando, e uma duplicata de
`UNIQUE` falha ali mesmo. Tabelas maiores constroem em uma thread própria, em blocos de 500 linhas.

- **Estado visível:** `INFO FOR TABLE` mostra `state` (`building` ou `ready`) e `processed` (linhas já
  varridas) em cada índice. Enquanto está `building`, as escritas já mantêm o índice, mas as consultas não o
  usam e seguem por varredura, com o mesmo resultado.
- **Escritas concorrentes:** cada bloco segura por alguns milissegundos o lock de escrita do banco, e cada
  statement de escrita segura o lock de leitura, então nenhum bloco enxerga um statement pela metade. Uma
  escrita que chega durante a construção entra no índice uma vez só.
- **Falha ou aborto:** uma duplicata em `UNIQUE` (inclusive criada por uma escrita concorrente) tira o índice
  do catálogo e do plano de controle e apaga as entradas parciais. O motivo fica em `index_failures` no
  `INFO FOR TABLE`. `DROP INDEX` durante a construção também interrompe e limpa.
- **Reinício:** a chave `IXB|ns|db|tabela|índice` marca a construção em andamento e só some no mesmo lote
  atômico que grava as chaves de "pronto". Se o motor reinicia com o marcador presente, o índice volta como
  `building`, as entradas parciais são apagadas e a construção recomeça. Um índice parcial nunca vira pronto.
- **Limites conhecidos:** statements dentro de uma transação explícita só enxergam o índice depois do commit,
  e os contadores de frequência do índice `SEARCH` já perdiam atualizações entre escritores simultâneos, o que
  esta fase não corrige.
- **Transação aberta antes do índice:** se um índice com entradas é criado ou removido depois do `BEGIN`, o
  `COMMIT` recusa a transação com conflito de versão, sem escrever nada, e o cliente a refaz. A checagem é por
  banco, então vale também para transações que escrevem em outra tabela do mesmo banco.

### Medição em 5 milhões de linhas

Medido de verdade em 5 milhões de linhas por tabela (duas tabelas, uma com o índice criado antes da carga e
outra depois), com `OnlineIndexScaleGateTest` em RocksDB, quatro escritores concorrentes durante a construção
e as mesmas mexidas reaplicadas na referência. Dois runs, ambos verdes:

| Medida | Resultado |
|---|---|
| Construção do índice `UNIQUE` | 532 a 800 s |
| Escritas concorrentes durante a construção | 97 a 114 mil |
| Latência de escrita p99 | 175 a 227 ms |
| Latência de escrita máxima | 0,8 a 1,9 s |

O número de entradas e as 2000 sondas ficaram idênticos aos do índice criado antes da carga, e a duplicata
em `UNIQUE` abortou sem deixar entradas nem marcador. Para repetir: `-Daxon.gate.index=true`.

## Portões do cluster (fase E2)

Os três portões de desempenho do Raft rodam em máquinas separadas: três nós e um gerador de carga (o portão 3 usa também a máquina do nó novo, que pode ser o próprio gerador). Cada máquina precisa de Java 21.

### Como rodar

Na máquina com o código, gere o pacote e copie `target/raft-gates/axon-gates.tar.gz` para todas:

```bash
scripts/raft-gates/run.sh bundle
```

Em cada nó, e no nó novo, suba o agente uma vez (o `--id` é diferente em cada máquina e o token é o mesmo em todas):

```bash
AXON_GATE_TOKEN=troque-por-um-segredo axon-gates/run.sh node --id n1 --advertise 10.0.0.2 --data /mnt/ssd/axon-gates
```

No gerador de carga, um comando por portão:

```bash
AXON_GATE_TOKEN=troque-por-um-segredo axon-gates/run.sh gate1 --nodes 10.0.0.2,10.0.0.3,10.0.0.4
```

```bash
AXON_GATE_TOKEN=troque-por-um-segredo axon-gates/run.sh gate2 --nodes 10.0.0.2,10.0.0.3,10.0.0.4
```

```bash
AXON_GATE_TOKEN=troque-por-um-segredo axon-gates/run.sh gate3 --nodes 10.0.0.2,10.0.0.3,10.0.0.4 --new 10.0.0.5
```

| Portão | Meta | Parâmetros (padrão) | Linha do veredito |
|---|---|---|---|
| 1, escrita em grupo | com 64 escritores, o caso `always` com pelo menos 60% da vazão do caso `batch` | `--writers 8,64,256`, `--seconds 30`, `--rounds 3`, `--warmup 10` | `GATE1 RESULT` (`PASS` ou `FAIL` só para 64; os demais saem como `INFO`) |
| 2, leitura em seguidores | 3 nós com pelo menos 2,5 vezes a vazão de leitura de 1 nó | `--rows 100000`, `--connections 192`, `--seconds 30`, `--rounds 3` | `GATE2 RESULT` (o veredito usa a leitura sem consistência pedida) |
| 3, nó novo | entrar num cluster de 10 GB em menos de 15 minutos sem pausar as escritas por mais de 1 segundo | `--gb 10`, `--rate 1000` escritas por segundo, `--baseline 60`, `--source follower` ou `leader`, `--mode batch` | `GATE3 RESULT` |

Todas as linhas de resultado começam com `GATE1`, `GATE2` ou `GATE3`, então `grep '^GATE'` filtra a saída. O comando sai com 1 quando um portão reprova.

Nenhum portão injeta falha, então cada um conta as eleições abertas durante a medida e reprova se houver alguma (`GATEn RESULT elections=`). A carga do portão 3 retoma no líder da vez se ele mudar, pulando o que já foi gravado, e a troca continua reprovando. O agente não grava a linha de cada requisição atendida com sucesso; eventos do cluster, recusas e erros continuam no log.

No portão 1 o modo vale explicitamente para o armazenamento **e** para o log Raft de cada nó: o caso `always` sincroniza os dois a cada escrita, e o caso `batch`, nenhum. As variáveis `AXON_STORAGE_SYNC` e `AXON_RAFT_LOG_SYNC` no ambiente dos agentes não mudam a comparação, e cada linha `GATE1` informa `storage=` e `raft_log=`.

O portão 1 escreve de dentro do processo do líder, em dois cenários: `raw` confirma lotes direto no consenso, e `sql` faz `CREATE` pelo motor. O portão 2 usa o `/sql` por HTTP e só mede depois que cada nó tem exatamente a quantidade de linhas carregada. Cada resposta é conferida: só conta a que traz a linha pedida, e qualquer erro, resposta vazia ou conteúdo diferente reprova o portão (`errors=` na linha do veredito). O gerador mantém uma conexão persistente por thread, num cliente HTTP mínimo (o do Java gasta mais processador do que o servidor numa leitura por id), e imprime o uso de processador dele (`generator_cpu`) e de cada nó (`GATE2 cpu`). Com o gerador em 80% ou mais, a razão mede o gerador e a rodada precisa de uma máquina maior: use para ele uma máquina com pelo menos quatro vezes os processadores de um nó (`n2-standard-32` para nós `n2-standard-8`), na mesma zona. O portão 3 tira o backup físico num seguidor, o nó novo baixa os arquivos pelo agente, restaura, entra como learner e compara uma amostra de 5000 linhas por SHA-256 (Secure Hash Algorithm 256). O tempo total inclui backup, transferência, restauração e o alcance do log.

Portas em cada nó: 8100 (controle), 8000 (HTTP) e 9000 (Raft). O agente recria a base quando mandado e o servidor dele sobe sem autenticação: use apenas numa rede isolada. Cada subida cria um diretório novo em `--data` e nada é apagado, então reserve disco (o portão 3 guarda a base em cada nó, o backup e a cópia no nó novo). Um endereço pode levar um deslocamento de portas (`127.0.0.1+11000`) para ensaiar numa só máquina.

O teste opcional `RaftWriteGateTest` mede o portão 1 com os três nós num processo e num disco só. Rode-o apenas numa máquina Linux dedicada:

```bash
mvn -pl axonbase-core -am -Daxon.gate.raft=true -Daxon.gate.raft.writers=8,64,256 -Dtest=RaftWriteGateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Subir um cluster de três nós com o servidor de verdade

A imagem sai do `Dockerfile` da raiz (`docker build -t axonbase .`). Cada nó precisa de:

| Variável | Exemplo no nó 1 |
|---|---|
| `AXON_CLUSTER_ID` | `producao` (igual em todos) |
| `AXON_NODE_ID` | `n1` (diferente em cada um) |
| `AXON_RAFT_BIND` | `10.0.0.2:9000` (endereço que os outros usam) |
| `AXON_RAFT_PEERS` | `10.0.0.3:9000,10.0.0.4:9000` |
| `AXON_SECRET` e `AXON_ROOT_PASSWORD` | segredos próprios, obrigatórios fora do endereço local |
| `AXON_STORAGE_SYNC` | opcional, `batch` por padrão |

Portas: 8000 (HTTP) e 9000 (Raft). A imagem só declara a 8000, então publique a 9000 também (ou use a rede do host). Monte um volume em `/data`: o estado do Raft fica em `.raft` dentro do diretório de dados. Quem vem da 1.0.0-rc1 ou anterior deve ler [onde fica o estado do Raft](cluster-raft.md#onde-fica-o-estado-do-raft-e-como-atualizar) antes de recriar contêineres. Informe os endereços dos membros por IP (Internet Protocol): um nome que não resolve na subida do processo não é resolvido de novo.

### Medidas locais (apenas informativas)

Feitas num Mac compartilhado com a carga do sistema entre 20 e 600, em Docker Desktop (12 CPUs, 8 GB). **Não valem como veredito** de nenhum portão: a decisão do dono é medir em máquinas dedicadas.

Portão 1, três nós num contêiner e num disco, 12 segundos por cenário, melhor das três rodadas (a de menor carga):

| Cenário e escritores | `always` (ops/s) | `batch` (ops/s) | razão |
|---|---|---|---|
| `raw`, 8 | 994 | 2.365 | 42% |
| `raw`, 64 | 2.407 | 5.754 | 42% |
| `raw`, 256 | 2.111 | 5.349 | 39% |
| `sql`, 8 | 1.053 | 3.870 | 27% |
| `sql`, 64 | 1.970 | 5.736 | 34% |
| `sql`, 256 | 2.192 | 5.480 | 40% |

Na rodada anterior, com 64 escritores, a razão foi de 30% (`raw`) e 28% (`sql`). **A meta de 60% não foi atingida nesta máquina.**

Portão 2, um nó contra três, 2 CPUs por nó, 96 conexões, duas rodadas: o nó único fez 582 e 295 leituras por segundo, e os três juntos 160 e 122. **A meta não foi atingida**, com o hospedeiro sem CPU livre: o ensaio funcional posterior, com pouquíssimos dados, deu o resultado oposto (4,1 vezes), o que mostra que estes números dependem do ambiente.

Portão 3: o de 10 GB não foi medido (não havia disco para as cópias). O ensaio com 50 MB entrou em 6,8 s, com maior pausa entre escritas de 75 ms e amostra idêntica.

