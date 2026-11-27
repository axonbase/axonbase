# Operação e resiliência (fase E5)

Desenho da fase E5 do [plano de escala](plano-escala-e0-e5.md): prioridade por tipo de carga, atualização um nó de cada vez, testes de caos e exercício de recuperação de desastre. Tudo aqui vale para um cluster de **um grupo** Raft. O ponto de extensão para vários shards (fase E3) está marcado em cada seção.

## Prioridade por tipo de carga

QoS (Quality of Service) é a regra que impede que consultas analíticas ocupem os recursos das transacionais. O padrão é **desligado**: sem `AXON_QOS`, o motor se comporta como antes, sem classificar nada.

### Classificação

Há duas classes: transacional (o que não for classificado como analítico) e analítica. A decisão segue esta ordem:

1. Escritas, scripts com qualquer instrução que escreve e comandos dentro de transação explícita são sempre transacionais. Fazer uma escrita esperar com travas de chave na mão atrasaria as outras.
2. Usuário listado em `AXON_QOS_ANALYTICAL_USERS`: analítica.
3. Pedido do cliente (cabeçalho `Axon-Workload` no `/sql`, campo `workload` no RPC, Remote Procedure Call): `analytical` sempre vale. `transactional` só vale com `AXON_QOS_TRUST_CLIENT=true`, para um cliente não escapar dos limites sozinho.
4. No modo `auto`, um `SELECT` com `GROUP BY` ou `GROUP ALL`, ou sem `WHERE`, sem `LIMIT` e sem registro específico.
5. No modo `auto`, uma leitura que passa de `AXON_QOS_SCAN_THRESHOLD` acessos ao armazenamento é **rebaixada** no meio da execução. É a rede de segurança para o que o texto não revela, como um filtro sem índice.

`AXON_QOS=explicit` usa só as regras 1 a 3. `AXON_QOS=auto` usa todas.

### Limites, fila e orçamento

Uma consulta analítica precisa de uma vaga para começar. Sem vaga, espera numa fila limitada. Fila cheia ou espera longa viram recusa (`503` com `Retry-After` e `kind: QOS_REJECTED` no HTTP, HyperText Transfer Protocol, e código `-32015` no RPC). Nada foi executado, então repetir é seguro.

O orçamento é de processador: enquanto houver trabalho transacional recente, as analíticas juntas usam no máximo `AXON_QOS_ANALYTICAL_CPU_PERCENT` dos núcleos. A contenção é cooperativa, no mesmo ponto em que o motor confere cancelamento. Sem trabalho transacional, as analíticas usam a máquina inteira.

| Variável | Padrão | Efeito |
|---|---|---|
| `AXON_QOS` | `off` | `off`, `explicit` ou `auto` |
| `AXON_QOS_ANALYTICAL_CONCURRENCY` | um quarto dos núcleos, mínimo 1 | consultas analíticas ao mesmo tempo |
| `AXON_QOS_ANALYTICAL_QUEUE` | 4 vezes as vagas | quantas esperam vaga |
| `AXON_QOS_ANALYTICAL_QUEUE_TIMEOUT_MS` | 5000 | espera máxima por vaga |
| `AXON_QOS_ANALYTICAL_CPU_PERCENT` | 50 | fatia de processador com transacional ativo |
| `AXON_QOS_ANALYTICAL_TIMEOUT_MS` | 0 (sem limite) | tempo máximo de execução de uma analítica |
| `AXON_QOS_SCAN_THRESHOLD` | 10000 | acessos a partir dos quais uma leitura vira analítica |
| `AXON_QOS_ANALYTICAL_USERS` | vazio | usuários sempre analíticos |
| `AXON_QOS_TRUST_CLIENT` | `false` | aceita `Axon-Workload: transactional` |

Vagas mais fila nunca passam da metade do limite de requisições de dados do HTTP, para a fila analítica não ocupar as vagas das transacionais.

Numa varredura paralela, o grupo de threads de leitura é dividido entre todas as consultas. A pausa devida pelo trabalho feito nele é cobrada da thread da própria consulta, para não segurar as varreduras das outras.

Um valor mal escrito em `AXON_QOS*`, `AXON_CLUSTER_HTTP_PEERS` ou `AXON_DRAIN_*` derruba o processo na partida, antes de abrir a porta ou entrar no cluster.

As métricas `axe_qos_*` mostram consultas em execução e em fila por classe, admitidas, recusadas por motivo, rebaixadas, tempo contido e o histograma de duração por classe.

Para cargas analíticas pesadas, a recomendação continua sendo um learner (veja [cluster-raft.md](cluster-raft.md)): o QoS protege o nó que atende os dois tipos.

**Extensão para a E3:** o limite é por nó. Com vários shards, o gateway repassa a classe e cada nó aplica o próprio limite.

## Atualização um nó de cada vez

**Da 0.10.x, atualize só com os três nós parados.** Atualizar um nó de cada vez a partir da 0.10.x não é suportado, porque a 0.10.x pode [confirmar escritas sem gravar](#defeito-na-0101-e-anteriores-escrita-confirmada-sem-gravar). Faça a [parada total](#falha-de-versões-mistas). Esta seção descreve a troca um nó de cada vez entre versões novas (desta em diante). O reinício um nó de cada vez de um cluster só com versão nova foi medido: 60 de 60 rodadas boas, com a mesma versão dos dois lados (veja o [roteiro](#roteiro)).

### Transferência de liderança

O líder passa o cargo sem esperar o prazo de eleição:

1. Segura as escritas novas e espera as que estão em curso terminarem.
2. Segura propostas no log e espera o alvo ter todas as entradas, com tudo aplicado no líder e quem escreveu já respondido.
3. Manda ao alvo o pedido de eleição imediata (`TIMEOUT_NOW`, a extensão do artigo do Raft).
4. Quando enxerga o novo líder, solta as escritas seguradas. Elas recebem `NOT_LEADER` sem terem sido executadas, e o cliente repete no novo líder.

Se algo falhar no prazo, o líder solta as escritas e continua no cargo. O alvo precisa ser um votante de uma versão que entenda o pedido (o líder pergunta antes, com `PEER_INFO`). Nós antigos recusam as duas mensagens sem efeito colateral.

### Drenar um nó

`POST /admin/v1/cluster/drain` leva o nó a `DRAINING`: o `/ready` passa a responder `503`, o líder transfere o cargo, o nó deixa de disputar eleições (continua votando), espera `AXON_DRAIN_GRACE_MS` para o balanceador perceber e depois as requisições de dados terminarem. Se o nó receber o cargo durante a espera, transfere de novo. Aí o estado é `DRAINED` e o processo pode ser parado. `GET` consulta o estado e `DELETE` desfaz. Com `AXON_DRAIN_ON_SHUTDOWN=true`, o desligamento normal do processo drena antes de parar.

`GET /admin/v1/cluster/health` devolve versão, protocolo, papel, prontidão, estado de drenagem, índice aplicado e, no líder, o atraso e o protocolo de cada membro.

### Compatibilidade de versões

Antes de atualizar um nó, confira que:

- a versão nova não é menor que a de nenhum membro (não há volta para versão antiga no mesmo diretório);
- nenhum membro é da linha 0.10.x ou anterior (dessas versões não há troca nó a nó);
- o cluster não passa a ter três versões diferentes.

`GET /admin/v1/cluster/upgrade-check?target=<versão>&members=<versões>` aplica essas regras.

**A partir da 0.10.x, use a [parada total](#falha-de-versões-mistas).** Com um membro 0.10.x, a verificação recusa a troca e manda parar os três nós. Ela decide só pelo número da versão: uma compilação desta linha de desenvolvimento que ainda se declare 0.10.x também é recusada, até a versão do projeto mudar.

### Roteiro

Vale entre versões que transferem a liderança (a partir desta).

1. Confira a saúde: todos prontos, atraso zero, versões compatíveis.
2. Atualize os seguidores, um de cada vez: drene, pare, suba a versão nova, espere ficar pronto e alcançar o líder.
3. Atualize o líder por último, com o mesmo passo. A drenagem transfere o cargo para um nó já atualizado.

### O que significa "sem erro visível ao cliente"

O cliente precisa seguir o contrato de repetição: repetir quando a escrita **certamente não aconteceu** (`NOT_LEADER`, redirecionamento `307`, sem quórum recusado antes de gravar, recusa do QoS, `503` de nó drenando e conexão recusada antes de enviar) e nunca repetir às cegas um desfecho desconhecido. Há uma exceção: um comando que já chamou um serviço externo (embeddings, provedor de IA ou banco remoto) e perdeu a liderança antes de confirmar devolve erro comum, sem redirecionamento, porque repetir faria a chamada outra vez. Com esse contrato, a atualização entre versões que transferem a liderança não deve produzir erro nenhum.

O redirecionamento `307` de escrita aponta, por padrão, para o endereço do Raft. Para clientes HTTP seguirem o redirecionamento, informe `AXON_CLUSTER_HTTP_PEERS` (por exemplo `n1=http://10.0.0.1:8000,n2=http://10.0.0.2:8000`).

**A partir da 0.10.x** este roteiro não vale: o líder antigo não sabe transferir o cargo, a versão antiga não tem drenagem e os defeitos dela podem perder escritas confirmadas enquanto ela participa do quórum. Veja [Falha de versões mistas](#falha-de-versões-mistas).

**O que foi medido:** o reinício um nó de cada vez de um cluster só desta versão, derrubando também o líder, passou em 60 rodadas sem perda, sem parada e sem anomalia. Os dois lados eram a mesma versão: a troca entre duas versões novas diferentes ainda não existe para medir.

**Conexões que não são HTTP:** uma conexão WebSocket continua aberta no nó drenado. Escritas passam a receber `NOT_LEADER` com o líder novo, e a conexão cai quando o processo para: o cliente precisa reconectar. Uma transação explícita aberta no líder durante a transferência recebe `NOT_LEADER` no `COMMIT` e precisa ser refeita.

**Extensão para a E3:** a drenagem passa a percorrer os grupos em que o nó é líder.

## Defeito na 0.10.1 e anteriores: escrita confirmada sem gravar

**Situação:** confirmado. A prova automática (`scripts/raft-gates/LegacyQuorumLoss.java`, commit `17bea4d`) rodou no ambiente de testes: contra a 0.10.1, cinco escritas confirmadas não ficaram em nenhum nó; contra esta versão, todas ficaram. O código das versões anteriores foi só lido, não executado. Não é um problema de atualização: acontece num cluster só com a versão antiga. O mesmo caminho existe na 0.4.0, na 0.8.0, na 0.9.2, na 0.10.0 e na 0.10.1.

**O que acontece.** Quando uma escrita falha por falta de quórum (basta os seguidores não responderem a tempo), o líder descarta a entrada do arquivo do log, mas não da memória. Na escrita seguinte ele encontra a entrada velha no índice, não grava a nova e confirma ao cliente. O log deixa de crescer e **todas** as escritas seguintes desse líder são confirmadas e perdidas, até o processo reiniciar.

**Mitigação para quem roda essas versões.** Reinicie o nó que respondeu falta de quórum, antes de ele voltar a receber escritas. As escritas já perdidas não são recuperadas.

**Esta versão** usa outro log e não herda o caminho. A prova, compilada contra ela, sai com `PROVA RESULT OK`.

```bash
javac -cp "$CLASSPATH_DA_VERSAO" -d out scripts/raft-gates/LegacyQuorumLoss.java
```

```bash
java -cp "$CLASSPATH_DA_VERSAO:out" LegacyQuorumLoss "$(mktemp -d)"
```

## Falha de versões mistas

O roteiro `scripts/raft-gates/mixed_versions.py` falhou uma vez: com dois nós novos e o líder derrubado, o líder 0.10.1 não confirmava escritas ao seguidor novo.

**Causa.** O protocolo antigo não informa o termo de cada entrada: quem recebe grava a entrada com o termo da requisição. Quando o líder antigo acaba de ser eleito e precisa reenviar uma entrada de um mandato anterior, o seguidor a grava com o termo novo. Na entrada seguinte o líder informa como anterior o termo verdadeiro, o seguidor recusa, o líder volta uma posição, o seguidor aceita sem mudar nada e o ciclo se repete para sempre. Como o líder antigo só confirma com entradas do mandato atual, o cluster para. Só acontece quando o seguidor não tinha a última entrada no momento da eleição, por isso a intermitência.

**Correção do laço.** Uma entrada gravada com o termo do líder atual veio dele. Se ele informa um termo anterior menor para ela, o seguidor novo aceita e segue, **sem mudar o termo gravado**. Uma primeira tentativa, que rebaixava o termo, foi descartada: nas rodadas no ambiente de testes ela fez o cluster misto perder escritas confirmadas, porque um nó com o termo rebaixado perde a eleição para um membro antigo com menos dados.

**Outras falhas do cluster misto, também corrigidas no nó novo:**

| Comportamento da 0.10.1 | O que o nó novo faz |
|---|---|
| o líder apaga uma escrita sem quórum e grava outra no mesmo índice e mandato | o seguidor troca a entrada pela reescrita |
| o líder envia um snapshot com o estado aplicado e o índice do fim do log | o seguidor que já tem dados recusa o snapshot cujo marcador aponta outro índice |
| o batimento do líder informa um índice de commit sem dizer de que entrada fala | o seguidor só confirma entradas gravadas com o mandato desse líder |
| o reenvio de uma entrada antiga carrega o commit atual | o seguidor só confirma até o índice conferido naquele pedido |
| o seguidor responde que aceitou uma entrada que não gravou | o líder novo não conta essa resposta para o quórum e abre um mandato novo |
| um nó que apagou o log num snapshot se candidata com menos do que já foi confirmado | o nó novo não vota nele |
| esse nó, como líder, manda outra entrada ou um snapshot em cima do confirmado | o nó novo recusa e, se a entrada for de outro mandato, sobe o mandato para tirá-lo do cargo |
| um nó antigo aplica entradas além do marcador recebido e depois é atualizado | na partida, o nó novo usa o índice do estado salvo pela versão antiga |

Essas três últimas defesas passam na suíte e não bastam no cluster misto (veja a tabela abaixo). Elas só valem quando o nó novo participa: com um nó novo e dois antigos, os antigos formam maioria sozinhos.

O mesmo laço existe entre dois nós 0.10.1, e ali não há correção possível: numa troca um nó de cada vez, que não é suportada, a saída é reiniciar o líder antigo travado.

**O que o ambiente de testes mostrou.** Cada rodada é uma execução do roteiro de versões mistas, da 0.10.1 para a versão indicada.

| Versão testada | Resultado |
|---|---|
| `12f5c46`, base sem correção | 30 rodadas, 15 falhas (as examinadas são o cluster parado) |
| `0ee8798`, só o laço e a troca da entrada | 30 rodadas: 27 boas, 2 com uma escrita perdida num nó, 1 parada |
| `b56cf07`, com as cinco primeiras defesas | 60 rodadas: 57 boas, 3 com 435 a 447 escritas perdidas nos três nós |
| `b1d915f`, mesmo código de consenso | 60 rodadas: 58 boas, 2 com 221 e 232 escritas perdidas |
| `9b53841`, com diagnóstico | 60 rodadas: 58 boas, 1 com 427 escritas perdidas, 1 parada ainda no cluster antigo |
| `238e9a1`, com todas as defesas | 60 rodadas: 55 boas, 4 com 207 a 440 escritas perdidas por líder antigo, 1 com uma escrita ausente num nó antigo |

As perdas grandes vêm do [defeito da versão antiga](#defeito-na-0101-e-anteriores-escrita-confirmada-sem-gravar): nas rodadas com diagnóstico, as escritas perdidas foram confirmadas por um líder antigo com o log vazio. O cluster só com a 0.10.1 travou nas 33 rodadas lidas, antes de chegar à conferência.

**Um caso em que a líder era nova.** Em `238e9a1`, uma escrita confirmada pela líder nova ficou fora de um seguidor antigo; os outros dois nós a guardam. Pelos registros, o outro seguidor antigo não a gravou (a líder não contou a resposta dele), então o voto que fechou o quórum veio de um nó antigo que já se dizia aplicado naquele índice, com o log vazio, e por isso nunca a aplicou. Se a líder caísse naquele instante, a escrita se perderia. Como esse nó chegou a esse estado não foi determinado, e o nó novo não tem como perceber: o protocolo antigo não informa o índice aplicado.

**Conclusão.** A atualização um nó de cada vez a partir da 0.10.x não é suportada, porque não pode ser garantida: enquanto um nó antigo participar do quórum, os defeitos dele atingem o cluster, inclusive o de confirmar escritas sem gravar. As defesas do nó novo reduzem a exposição, mas não a eliminam. Da 0.10.x, atualize só com os três nós parados.

**Atualização com parada total.** `mixed_versions.py <dir> <classpath antigo> <classpath novo> parada` derruba os três nós antigos (pedindo para sair e com kill), sobe os três na versão nova, confere as perdas e segue escrevendo. A linha `MIXED PARADA <como> RESULT` separa o que a versão antiga já tinha perdido do que a troca perdeu. Em `17bea4d`, 30 rodadas com as duas paradas (60 trocas) passaram todas, sem perda causada pela troca e sem divergência. Em duas trocas limpas a versão antiga já tinha perdido 407 e 413 escritas antes da parada.

**Atualização a partir da 0.10.x (decidido pelo dono):** parada total, que a 1.0.0-rc1 pode exigir. Com o S3 configurado, a versão nova [recusa a subida quando a restauração pedida falha](backup-e-restauracao.md#restauração-do-s3-na-subida), em vez de subir com a base vazia. Pare as escritas, espere os três nós ficarem com o mesmo índice, pare os três e suba os três na versão nova. Um nó antigo pode já estar diferente dos outros antes da parada, por causa dos próprios defeitos: compare o conteúdo e, havendo diferença, refaça os nós divergentes a partir do que tiver os dados completos.

## Testes de caos

Os cenários ficam em testes que rodam na suíte com poucas repetições e, sob demanda, com mais repetições e outra semente:

| Cenário | O que se verifica |
|---|---|
| matar o líder | nova eleição em menos de 10 segundos e nenhuma escrita confirmada perdida |
| isolar o líder da maioria | a maioria elege outro, o isolado não confirma nada, e depois de religado os três convergem |
| cortar um sentido de um enlace | o cluster continua confirmando e converge |
| matar durante o backup | todo backup listado passa na verificação e o nó volta com os dados |
| matar durante o restore | a base fica inteira (a antiga ou a restaurada), nunca misturada |

A partição de rede é feita por um repassador TCP (Transmission Control Protocol) entre os nós, que o teste corta e religa. `-Daxon.chaos.seed=<n>` repete a mesma sequência de falhas e `-Daxon.chaos.rounds=<n>` aumenta as repetições. Cada linha de resultado começa com `CHAOS`.

## Recuperação de desastre

DR (Disaster Recovery) é a volta depois de perder o cluster inteiro. As peças são o backup físico, o journal de PITR (Point-In-Time Recovery, restauração a um instante) e um armazenamento fora do cluster, MinIO ou outro compatível com S3 (Simple Storage Service).

### Journal em cluster

Com `AXON_PITR=on`, cada nó do cluster passa a arquivar os lotes confirmados, em ordem de índice do log e com o carimbo do líder (relógio híbrido, HLC, Hybrid Logical Clock). A marca d'água de um nó é o último lote confirmado que ele arquivou. Ao voltar de uma queda, o nó reentrega ao journal o que aplicou depois do último lote arquivado e que o log ainda guarda. Um nó que recebe o estado por snapshot, ou que já descartou esse trecho do log, fica com um buraco, que outro nó cobre. A restauração a um instante pela API administrativa continua exclusiva do nó único.

### Arquivamento

A ferramenta `com.axonbase.core.dr.DrTool` copia para o armazenamento os segmentos fechados do journal e os backups físicos de um nó, nessa ordem, e uma falha num dos dois não impede o outro. Rode-a ao lado de cada nó, em intervalo (`watch`). Sem `watch`, ela sai com 1 se alguma parte falhar. Cada backup só conta depois do seu descritor, gravado por último.

```text
<prefixo>/<época>/groups/<grupo>/backups/<nó>/files/...
<prefixo>/<época>/groups/<grupo>/backups/<nó>/descriptors/<número>-<carimbo físico>-<carimbo lógico>
<prefixo>/<época>/groups/<grupo>/journal/<nó>/seg-*.axj
```

A **época** separa histórias diferentes: um cluster restaurado reaproveita índices do log, então arquive-o sempre numa época nova.

### Restauração

1. Escolhe o backup mais novo cujo carimbo não passe do instante pedido. Se a base dele já contiver escritas posteriores ao instante, tenta o anterior.
2. Baixa o backup e os segmentos de todos os nós.
3. Restaura o backup e reaplica o journal em ordem de índice, juntando os nós, até o instante. Um índice que falta em todos interrompe a restauração, com o último instante alcançável na mensagem.
4. Grava o ponto do log, apaga a lista antiga de membros e, se o grupo era um shard, a identificação da história antiga dele, e entrega um diretório pronto. Depois disso, tire um backup de shards novo.

Copie o diretório para os dados de cada nó (ou restaure em cada nó: o resultado é o mesmo), com o diretório do Raft vazio, e suba o cluster.

O RPO (Recovery Point Objective, perda tolerada) é o intervalo de arquivamento somado ao fechamento do segmento (no máximo 30 segundos).

Um instante só é alcançável se os segmentos provarem que não falta escrita até ele. Num cluster parado, a prova vai até a última escrita: para voltar ao estado mais novo, use `latest`.

Com `latest` (sem instante), a restauração também falha quando a marca de algum segmento mostra que o log chegou a um lote que não está em segmento algum, por exemplo quando a fila do journal estourou nos últimos lotes. Antes ela terminava com sucesso sem essas escritas. Confira primeiro se o arquivamento de todos os nós terminou. Se o lote se perdeu mesmo, aceitar a perda exige um `--at` anterior ao instante `latest` impresso na falha; o instante é dado em milissegundos, então o que foi escrito naquele último milissegundo também fica de fora.

### Roteiro de arquivamento e restauração

Em cada nó, com `AXON_PITR=on` no servidor e as variáveis `AXON_S3_ENDPOINT`, `AXON_S3_BUCKET`, `AXON_S3_ACCESS` e `AXON_S3_SECRET` no ambiente (e `AXON_S3_ENCRYPT_KEY` para cifrar):

```bash
java -cp "lib/*" com.axonbase.core.dr.DrTool watch --store s3 --epoch e1 --node n1 --backups /data/db/.physical-backups --journal /data/db/.pitr-journal
```

Tire backups físicos num seguidor ou learner (`POST /admin/v1/backups/physical`), no intervalo da sua política. Para restaurar:

```bash
java -cp "lib/*" com.axonbase.core.dr.DrTool restore --store s3 --epoch e1 --target /data/novo --work /data/trabalho --at 2026-01-31T14:32:10Z
```

Se a base tiver partições na [camada fria](particoes-e-camada-fria.md), o ambiente do comando precisa de `AXON_COLD_DIR` ou `AXON_COLD_S3` apontando para os objetos dela, que o arquivamento não copia. Sem isso, ou faltando objeto, a restauração falha e não entrega a base.

Suba cada nó com os dados em `/data/novo` (ou uma cópia dele) e sem diretório do Raft, e arquive o cluster novo numa época nova.

**Extensão para a E3:** o layout já separa os grupos. A restauração de vários shards repete o passo por grupo com o mesmo instante, e o manifesto de cluster da E3 entra ao lado de `groups/`.

## Portões da E5

Os portões são opt-in e rodam em máquinas separadas, com `scripts/resilience-gates/run.sh`. Cada linha de resultado começa com `E5`, o veredito é `E5 <portão> RESULT PASS|FAIL` e o comando sai com 1 quando reprova.

| Portão | Meta |
|---|---|
| `failover` | líder morto sob carga: nenhuma escrita confirmada perdida e eleição em menos de 10 segundos |
| `upgrade` | atualização nó a nó entre versões que transferem a liderança, sem erro visível ao cliente |
| `qos` | carga analítica sem piorar o p99 transacional em mais de 20% |
| `dr` | restaurar 10 GB do MinIO em menos de 10 minutos, mais restauração a um instante |

## Decisões que dependem do dono

Cada uma segue a recomendação até resposta em contrário. Já decidido: a atualização a partir da 0.10.x é com parada total. A verificação de atualização (`upgrade-check`) aplica essa regra.

| Decisão | Recomendação adotada |
|---|---|
| QoS ligado por padrão? | Não. `AXON_QOS=off` até o portão `qos` passar em máquinas separadas. |
| Cliente pode se declarar transacional? | Só com `AXON_QOS_TRUST_CLIENT=true`. |
| Drenar no desligamento por padrão? | Não por ora (`AXON_DRAIN_ON_SHUTDOWN=false`). Ligar por padrão na 1.0.0 depois do portão `upgrade`. |
| Definição de "sem erro visível" | Com o contrato de repetição acima, medida entre versões que transferem a liderança. |
| Quem arquiva para o MinIO | A ferramenta `DrTool` ao lado do nó. O Enterprise pode passar a fazer o mesmo depois. |
| Prazo de eleição | Mantido (350 a 850 ms), bem abaixo da meta de 10 segundos. |
