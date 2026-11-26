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

As métricas `axe_qos_*` mostram consultas em execução e em fila por classe, admitidas, recusadas por motivo, rebaixadas, tempo contido e o histograma de duração por classe.

Para cargas analíticas pesadas, a recomendação continua sendo um learner (veja [cluster-raft.md](cluster-raft.md)): o QoS protege o nó que atende os dois tipos.

**Extensão para a E3:** o limite é por nó. Com vários shards, o gateway repassa a classe e cada nó aplica o próprio limite.

## Atualização um nó de cada vez

### Transferência de liderança

O líder passa o cargo sem esperar o prazo de eleição:

1. Segura as escritas novas e espera as que estão em curso terminarem.
2. Segura propostas no log e espera o alvo ter todas as entradas, com tudo aplicado no líder.
3. Manda ao alvo o pedido de eleição imediata (`TIMEOUT_NOW`, a extensão do artigo do Raft).
4. Quando enxerga o novo líder, solta as escritas seguradas. Elas recebem `NOT_LEADER` sem terem sido executadas, e o cliente repete no novo líder.

Se algo falhar no prazo, o líder solta as escritas e continua no cargo. O alvo precisa ser um votante de uma versão que entenda o pedido (o líder pergunta antes, com `PEER_INFO`). Nós antigos recusam as duas mensagens sem efeito colateral.

### Drenar um nó

`POST /admin/v1/cluster/drain` leva o nó a `DRAINING`: o `/ready` passa a responder `503`, o líder transfere o cargo, o nó deixa de disputar eleições (continua votando), espera `AXON_DRAIN_GRACE_MS` para o balanceador perceber e depois as requisições de dados terminarem. Aí o estado é `DRAINED` e o processo pode ser parado. `GET` consulta o estado e `DELETE` desfaz. Com `AXON_DRAIN_ON_SHUTDOWN=true`, o desligamento normal do processo drena antes de parar.

`GET /admin/v1/cluster/health` devolve versão, protocolo, papel, prontidão, estado de drenagem, índice aplicado e, no líder, o atraso e o protocolo de cada membro.

### Compatibilidade de versões

Antes de atualizar um nó, confira que:

- a versão nova não é menor que a de nenhum membro (não há volta para versão antiga no mesmo diretório);
- nenhum membro é anterior à 0.10.1;
- o cluster não passa a ter três versões diferentes.

`GET /admin/v1/cluster/upgrade-check?target=<versão>&members=<versões>` aplica essas regras.

### Roteiro

1. Confira a saúde: todos prontos, atraso zero, versões compatíveis.
2. Atualize os seguidores, um de cada vez: drene, pare, suba a versão nova, espere ficar pronto e alcançar o líder.
3. Atualize o líder por último, com o mesmo passo. A drenagem transfere o cargo para um nó já atualizado.

### O que significa "sem erro visível ao cliente"

O cliente precisa seguir o contrato de repetição: repetir quando a escrita **certamente não aconteceu** (`NOT_LEADER`, redirecionamento `307`, sem quórum recusado antes de gravar, recusa do QoS, `503` de nó drenando e conexão recusada antes de enviar) e nunca repetir às cegas um desfecho desconhecido. Com esse contrato, a atualização entre versões que transferem a liderança não deve produzir erro nenhum.

O redirecionamento `307` de escrita aponta, por padrão, para o endereço do Raft. Para clientes HTTP seguirem o redirecionamento, informe `AXON_CLUSTER_HTTP_PEERS` (por exemplo `n1=http://10.0.0.1:8000,n2=http://10.0.0.2:8000`).

**Limite na atualização a partir da 0.10.x:** o líder antigo não sabe transferir o cargo. O último passo causa uma eleição, e escritas em curso naquele instante podem ficar sem desfecho.

**Extensão para a E3:** a drenagem passa a percorrer os grupos em que o nó é líder.

## Falha de versões mistas

O roteiro `scripts/raft-gates/mixed_versions.py` falhou uma vez: com dois nós novos e o líder derrubado, o líder 0.10.1 não confirmava escritas ao seguidor novo.

**Causa.** O protocolo antigo não informa o termo de cada entrada: quem recebe grava a entrada com o termo da requisição. Quando o líder antigo acaba de ser eleito e precisa reenviar uma entrada de um mandato anterior, o seguidor a grava com o termo novo. Na entrada seguinte o líder informa como anterior o termo verdadeiro, o seguidor recusa, o líder volta uma posição, o seguidor aceita sem mudar nada e o ciclo se repete para sempre. Como o líder antigo só confirma com entradas do mandato atual, o cluster para. Só acontece quando o seguidor não tinha a última entrada no momento da eleição, por isso a intermitência.

**Correção.** O seguidor novo sabe que uma entrada gravada com o termo da requisição veio deste líder. Se o líder informa um termo anterior menor para ela, o seguidor corrige o termo e segue. Um reenvio do mesmo conteúdo não trunca o que veio depois.

O mesmo laço existe entre dois nós 0.10.1, e ali não há correção possível: se acontecer durante a atualização, reinicie o líder antigo travado.

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

Com `AXON_PITR=on`, cada nó do cluster passa a arquivar os lotes confirmados, em ordem de índice do log e com o carimbo do líder (relógio híbrido, HLC, Hybrid Logical Clock). A marca d'água de um nó é o último lote confirmado que ele arquivou. Um nó que recebe o estado por snapshot fica com um buraco, que outro nó cobre. A restauração a um instante pela API administrativa continua exclusiva do nó único.

### Arquivamento

A ferramenta `com.axonbase.core.dr.DrTool` copia para o armazenamento os backups físicos e os segmentos fechados do journal de um nó. Rode-a ao lado de cada nó, em intervalo (`watch`). Cada backup só conta depois do seu descritor, gravado por último.

```text
<prefixo>/<época>/groups/<grupo>/backups/<nó>/files/...
<prefixo>/<época>/groups/<grupo>/backups/<nó>/descriptors/<id>.json
<prefixo>/<época>/groups/<grupo>/journal/<nó>/seg-*.axj
```

A **época** separa histórias diferentes: um cluster restaurado reaproveita índices do log, então arquive-o sempre numa época nova.

### Restauração

1. Escolhe o backup mais novo cujo carimbo não passe do instante pedido.
2. Baixa o backup e os segmentos de todos os nós.
3. Restaura o backup e reaplica o journal em ordem de índice, juntando os nós, até o instante. Um índice que falta em todos interrompe a restauração, com o último instante alcançável na mensagem.
4. Grava o ponto do log e apaga a lista antiga de membros, e entrega um diretório pronto.

Copie o diretório para os dados de cada nó (ou restaure em cada nó: o resultado é o mesmo), com o diretório do Raft vazio, e suba o cluster.

O RPO (Recovery Point Objective, perda tolerada) é o intervalo de arquivamento somado ao fechamento do segmento (no máximo 30 segundos).

**Extensão para a E3:** o layout já separa os grupos. A restauração de vários shards repete o passo por grupo com o mesmo instante, e o manifesto de cluster da E3 entra ao lado de `groups/`.

## Portões da E5

Os portões são opt-in e rodam em máquinas separadas, com `scripts/resilience-gates/run.sh`. Cada linha de resultado começa com `E5`, o veredito é `E5 <portão> RESULT PASS|FAIL` e o comando sai com 1 quando reprova.

| Portão | Meta |
|---|---|
| `failover` | líder morto sob carga: nenhuma escrita confirmada perdida e eleição em menos de 10 segundos |
| `upgrade` | atualização nó a nó sem erro visível ao cliente |
| `qos` | carga analítica sem piorar o p99 transacional em mais de 20% |
| `dr` | restaurar 10 GB do MinIO em menos de 10 minutos, mais restauração a um instante |

## Decisões que dependem do dono

Cada uma segue a recomendação até resposta em contrário.

| Decisão | Recomendação adotada |
|---|---|
| QoS ligado por padrão? | Não. `AXON_QOS=off` até o portão `qos` passar em máquinas separadas. |
| Cliente pode se declarar transacional? | Só com `AXON_QOS_TRUST_CLIENT=true`. |
| Drenar no desligamento por padrão? | Não por ora (`AXON_DRAIN_ON_SHUTDOWN=false`). Ligar por padrão na 1.0.0 depois do portão `upgrade`. |
| Definição de "sem erro visível" | Com o contrato de repetição acima, medida entre versões que transferem a liderança. |
| Quem arquiva para o MinIO | A ferramenta `DrTool` ao lado do nó. O Enterprise pode passar a fazer o mesmo depois. |
| Prazo de eleição | Mantido (350 a 850 ms), bem abaixo da meta de 10 segundos. |
