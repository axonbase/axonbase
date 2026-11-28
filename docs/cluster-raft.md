# Cluster Raft: escrita em grupo, leitura em seguidores e réplica de leitura

Este documento descreve o consenso Raft do motor a partir da fase E2 do [plano de escala](plano-escala-e0-e5.md): o que mudou, como configurar, o que é garantido e os limites. As medidas dos portões ficam em [escala-e-durabilidade.md](escala-e-durabilidade.md).

## Escrita: um fsync para várias propostas

O líder grava as propostas no log sem esperar o disco, e um único fsync (a ordem ao sistema operacional para levar os dados ao disco) atende todas as que chegaram antes dele. A replicação usa uma conexão TCP (Transmission Control Protocol) persistente por membro, junta várias entradas numa requisição e mantém várias requisições em voo, sem esperar a resposta da anterior.

O log Raft tem configuração própria, `AXON_RAFT_LOG_SYNC`, independente de `AXON_STORAGE_SYNC` (que é do armazenamento):

| `AXON_RAFT_LOG_SYNC` | Quando um nó conta a entrada para o quórum | Perda possível |
|---|---|---|
| `always` (padrão) | depois do fsync | nenhuma com quórum disponível |
| `batch` | depois de gravar no sistema operacional, com fsync a cada 1 segundo | só se a maioria dos nós sofrer queda do sistema operacional ou de energia no mesmo segundo |

O padrão mantém a garantia da 0.10.1 (nenhuma entrada contada sem fsync), agora com um fsync para várias escritas. `batch` troca a garantia de cada nó pela do quórum, em troca de vazão. Outro valor impede o nó de subir. O termo e o voto de cada nó são sincronizados em disco nos dois modos.

O armazenamento não faz fsync por entrada aplicada, em nenhum modo: a durabilidade é a do log, e o nó sincroniza o armazenamento antes de descartar entradas. Cada entrada aplicada grava, no mesmo lote atômico, a chave `__raft/applied` com o índice e o termo. Depois de um reinício o nó continua exatamente desse ponto.

### Desfecho desconhecido

Se o quórum some (ou o líder deixa o cargo) depois que a entrada já está no log dele, o motor não sabe o que vai acontecer: a escrita pode já ter sido confirmada, vir a ser quando o quórum voltar, ou nunca acontecer. Isso **não é uma falha**, e repetir sem conferir pode duplicar a escrita. O cliente recebe um erro próprio:

| Onde | Desfecho desconhecido | Falha certa (nada foi gravado) |
|---|---|---|
| RPC (Remote Procedure Call) | código `-32013`, `kind: OUTCOME_UNKNOWN` | código `-32011` (sem quórum) |
| `/sql` e `/table`, por HTTP (HyperText Transfer Protocol) | `504`, `status: UNKNOWN`, `kind: OUTCOME_UNKNOWN` | `400`, `status: ERR` |
| API (Application Programming Interface) de administração | `504`, `OUTCOME_UNKNOWN` | o erro da operação |
| Motor embutido em Java | `CommitOutcomeUnknownException` | `QuorumUnavailableException` ou `NotLeaderException` |

`CommitOutcomeUnknownException` é subclasse de `QuorumUnavailableException`, para não quebrar quem já a tratava: capture-a primeiro. Quando o líder já sabe que não há maioria, ele recusa antes de gravar, e a falha é certa. A versão anterior apagava a entrada do próprio log, o que no Raft permite que dois nós guardem lotes diferentes no mesmo índice e termo.

### Transações concorrentes

No cluster, transações que tocam chaves diferentes são confirmadas em paralelo (e dividem o fsync). Duas que tocam a mesma chave entram em fila: se o que a segunda leu mudou, um comando avulso é refeito pelo próprio motor (até 100 vezes), e uma transação explícita (`BEGIN` e `COMMIT`) recebe o conflito de versão, como no nó único. A versão anterior podia perder uma das duas escritas em silêncio e deixar o líder com dados diferentes dos seguidores.

**Exceção:** um comando que chamou para fora do banco (consulta por link de banco remoto, provedor de embeddings ou provedor de IA) não é refeito, porque a chamada aconteceria de novo. O cliente recebe o conflito de versão e decide se repete.

## Log em segmentos

O log fica em `<diretório raft>/raft-log/`, em segmentos de 64 MiB (mebibytes):

- cada entrada tem um CRC (Cyclic Redundancy Check), um código que detecta bytes alterados;
- truncar apaga os segmentos posteriores e corta um só; compactar apaga segmentos inteiros;
- uma entrada incompleta no fim do último segmento (queda no meio da escrita) é descartada na abertura;
- corrupção em dado já confirmado, ou num segmento que não é o último, **impede o nó de abrir**, com o nome do arquivo na mensagem. A saída é apagar o diretório do Raft para o líder reenviar o estado, ou restaurar o nó de um backup físico;
- bytes ilegíveis numa cauda ainda não confirmada são cortados e guardados num arquivo `.corrupt`;
- o `raft.log` das versões anteriores é convertido na primeira abertura e renomeado para `raft.log.migrated`.

Voltar para uma versão antiga no mesmo diretório não é suportado: ela não lê os segmentos, começa com o log vazio e depende do líder para alcançar o estado. Se depois disso o nó for atualizado de novo, ele se recusa a abrir (há um `raft.log` novo ao lado dos segmentos velhos): apague o diretório do Raft para o líder reenviar o estado.

### O que o log guarda, e quanto disco pede

| Variável | Padrão | Efeito |
|---|---|---|
| `AXON_RAFT_RETAIN_ENTRIES` | 100000 | entradas já aplicadas mantidas no log |
| `AXON_RAFT_RETAIN_MINUTES` | 30 | um segmento só sai do log depois desse tempo sem escrita |

As duas condições precisam valer para um segmento sair. O tempo existe para que um backup de poucos minutos atrás, mais o log, bastem para um nó novo entrar.

Cada nó, líder, seguidor ou learner, precisa reservar para o log, além da base:

- o maior entre **taxa de bytes gravados × 30 minutos** e **tamanho de 100000 entradas**;
- mais um segmento (64 MiB, mebibytes).

O tamanho de uma entrada é a soma das chaves e dos valores gravados (o registro e as entradas de índice), mais algumas dezenas de bytes. Exemplos para os 30 minutos:

| Escritas por segundo | Bytes por escrita | Log em 30 minutos |
|---|---|---|
| 500 | 1 KiB (kibibyte) | cerca de 0,9 GiB (gibibyte) |
| 1000 | 2 KiB | cerca de 3,4 GiB |
| 5000 | 1 KiB | cerca de 8,6 GiB |
| 5000 | 4 KiB | cerca de 34 GiB |

Com um membro votante parado, o líder pode guardar até dez vezes `AXON_RAFT_RETAIN_ENTRIES` para ele, mesmo além dos 30 minutos. Um membro atrasado além disso recebe o estado inteiro pela rede (snapshot), que é adequado só para bases pequenas.

## Onde fica o estado do Raft, e como atualizar

O diretório do Raft é `.raft` dentro do diretório de dados, e os truststores enviados pela API ficam em `.truststores`. Com o comando padrão da imagem (`--path /data` e um volume em `/data`), os dois ficam no volume.

Ao apagar a base de um nó para repô-lo, apague também o `.raft`: um `rm -rf /data/*` não alcança nomes que começam com ponto, e um nó com o estado do Raft de uma base que sumiu fica diferente dos outros.

Até a 1.0.0-rc1 eles ficavam ao lado do diretório de dados (`<caminho>.raft` e `<caminho>.truststores`). A atualização trata o local antigo assim:

| Situação ao subir | O que o servidor faz |
|---|---|
| Há estado do Raft só no local antigo | usa o local antigo, sem mover; avisa no log quando ele está fora do diretório de dados |
| Há estado do Raft nos dois locais | não sobe, para não escolher o errado; deixe só o atual e mova o outro para fora |
| Há truststores no local antigo | copia para `.truststores` os que ainda não estão lá, sem apagar os originais |
| A base tem dados e não há estado do Raft | sobe como antes e avisa no log (`raft_state_missing`) |

Instalações fora de contêiner e as que usam `--path /data/db` com volume em `/data` não precisam de passo algum.

### Contêiner com o comando padrão: copiar antes de recriar

Na 1.0.0-rc1 e anteriores, com `--path /data`, o estado está em `/data.raft`, na camada do contêiner. Recriar o contêiner (trocar a imagem, `docker compose up` com outra tag, `down` e `up`) **apaga** esse estado. A versão nova não tem como recuperá-lo, então copie antes, com o contêiner parado e ainda existente:

```bash
scripts/migrar-estado-docker.sh <contêiner>
```

O script para o contêiner e copia `/data.raft` para `/data/.raft` e `/data.truststores` para `/data/.truststores`, dentro do volume. Depois troque a imagem e recrie. Da 0.10.x ou anterior, pare os três nós, rode o script em cada um e só então suba os três com a imagem nova. Entre versões novas, faça um nó por vez.

Se a atualização falhar e for preciso voltar à imagem antiga no mesmo contêiner, faça isso antes de recriá-lo. Depois de recriado, a imagem antiga volta a procurar o estado em `/data.raft` e não o encontra.

Numa máquina Linux descartável, `scripts/verificar-instalacao.sh` ensaia a instalação nova e a atualização.

Se o contêiner antigo já foi removido (`docker compose down`, `docker rm`), o estado do Raft daquele nó se perdeu, como já acontecia nas versões anteriores. A base continua no volume. Não recrie os outros nós: copie o estado deles primeiro e confira se o nó recriado alcança o líder.

## Compose de cluster: autenticação e portas

O `docker-compose.cluster.yml` sobe sem autenticação, para desenvolvimento e teste, e publica as portas 18001 a 18003 só em `127.0.0.1`. Até a 1.0.0-rc1 elas eram publicadas em todas as interfaces: quem acessava o cluster de outra máquina precisa agora definir `AXON_PUBLISH_ADDRESS=0.0.0.0`, de preferência junto com a autenticação:

```bash
export AXON_REQUIRE_AUTH=true
export AXON_ROOT_PASSWORD='senha-forte'
export AXON_SECRET="$(openssl rand -hex 32)"
export AXON_PUBLISH_ADDRESS=0.0.0.0
docker compose -f docker-compose.cluster.yml up -d
```

Com a autenticação ligada, o servidor recusa a senha e o segredo padrão. Guarde os valores: os três nós precisam dos mesmos a cada subida.

## Leitura em seguidores

Qualquer nó atende leituras. A consistência é pedida por requisição:

- no `/sql` e no `/table`, no cabeçalho HTTP (HyperText Transfer Protocol) `Axon-Read-Consistency`;
- no RPC (Remote Procedure Call), no campo `consistency`, ao lado de `method`.

| Valor | Garantia |
|---|---|
| `stale` | o que o nó tiver |
| `max-lag=<ms>` | o nó esteve em dia com o líder há no máximo esse tempo |
| `linearizable` | a leitura enxerga toda escrita confirmada antes dela |

Sem o pedido, vale `AXON_RAFT_MAX_READ_LAG_MS`. O padrão `0` **não impõe limite**, para manter o comportamento das versões anteriores: um seguidor isolado continua respondendo com dados antigos. Quem não aceita isso define um limite no nó ou pede a consistência na requisição. Quando o nó não alcança a consistência pedida, a resposta é `503` (ou o erro `-32012` no RPC) com `kind: STALE_READ` e o endereço do líder.

A leitura linearizável usa o ReadIndex do Raft: o líder anota o índice confirmado, confirma com uma maioria que ainda é líder, e o nó espera aplicar esse índice (e atualizar o catálogo) antes de ler. Várias leituras dividem a mesma consulta.

Para `max-lag`, o seguidor não confia na hora em que uma mensagem chegou, porque ela pode ter ficado parada na rede. Ele mede pelo próprio relógio: vale uma consulta ao líder iniciada há no máximo o limite pedido. Se não houver uma recente, faz a consulta na hora. Com o padrão sem limite, a leitura não consulta o líder.

Os endpoints `/graphql` e `/mcp` seguem as mesmas regras do `/sql`.

## Réplica de leitura (learner)

Um learner recebe o log e atende leituras, mas não vota e não conta para o quórum. Serve para consultas analíticas e para tirar backups sem pesar no líder.

Para subir um:

1. Tire um backup físico num seguidor ou noutro learner, com `POST /admin/v1/backups/physical` nele (veja [backup-fisico.md](backup-fisico.md)). O líder recusa, para o backup não atrasar as escritas.
2. Leve o diretório de backup até o nó novo.
3. Em **todos os votantes**, liste o endereço do learner em `AXON_RAFT_LEARNERS` (por exemplo `10.0.0.9:9000`, separados por vírgula) e reinicie-os, um de cada vez. O learner recebe o banco inteiro, então sem essa lista nenhum é aceito. O valor `*` aceita qualquer endereço e só serve para ambientes de teste.
4. Suba o servidor com a mesma `AXON_CLUSTER_ID`, o próprio `AXON_NODE_ID` e `AXON_RAFT_BIND`, os endereços dos votantes em `AXON_RAFT_PEERS` e:

```bash
AXON_RAFT_LEARNER=true
AXON_RAFT_BOOTSTRAP_BACKUP=/caminho/do/diretorio/de/backup
```

Com a base vazia, o servidor restaura o backup mais novo e pede ao líder só o que veio depois. Se o backup referenciar a [camada fria](particoes-e-camada-fria.md), o learner precisa de `AXON_COLD_DIR` ou `AXON_COLD_S3` como os outros nós: sem isso, ou faltando objeto, ele não restaura, não sobe e continua sem base. Com uma base já existente, ela é usada como está. Se o log do líder não alcança mais o backup, o learner fica parado e o líder o lista em `learnersNeedingBackup`: apague a base do learner e entre com um backup mais novo.

Um learner no ar não pode ser promovido a votante pela API (Application Programming Interface) de membros. Reinicie-o sem `AXON_RAFT_LEARNER` antes de incluí-lo.

Restaurar ou importar um backup físico pela API continua recusado em cluster: trocaria a base de um membro por fora do consenso. Para repor um membro, apague a base e o diretório do Raft dele e entre pelo backup, como acima.

## Carimbo de tempo

Cada entrada do log leva um carimbo de um relógio híbrido, o HLC (Hybrid Logical Clock). O líder carimba ao propor, e seguidores e learners adotam o carimbo recebido, então a ordem do log é a ordem dos carimbos mesmo depois de uma troca de líder. Um lote que chegue mais de 500 ms à frente do relógio local é recusado e reenviado: sincronize os nós com o `chrony`. Com `AXON_PITR=on`, cada nó arquiva os lotes confirmados para a [recuperação de desastre](operacao-e-resiliencia.md#recuperação-de-desastre). Detalhes do carimbo em [pitr.md](pitr.md#o-carimbo-de-tempo).

## Chave primária e `UNIQUE` na troca de líder

> **Enquanto um nó da 1.0.0-rc1 for o líder, a `PRIMARY KEY` não é conferida.** Na atualização, atualize primeiro os seguidores, transfira a liderança para um nó atualizado e atualize por último o antigo líder. **Voltar para a rc1 exige restaurar o backup.**

A unicidade é conferida por registros internos que apontam cada valor para a linha que o tem. A atualização nó a nó, uma parada total com entradas do log ainda não aplicadas e a entrada de um nó novo podiam deixá-los diferentes das linhas. A versão atual trata assim:

- Na primeira escrita de cada tabela com chave primária em cada mandato, o líder espera aplicar o log, compara os registros internos com as linhas e replica só a diferença, em lotes de até 500 chaves. Reiniciar o nó ou assumir a liderança repete a conferência.
- Essa primeira escrita percorre a tabela inteira, e as outras escritas da mesma tabela esperam. Em tabelas grandes ela pode estourar o tempo limite do cliente. Logo depois de uma troca de líder, vale fazer uma escrita de aquecimento em cada tabela grande. Os lotes já confirmados não se perdem se a conferência for interrompida.
- Sem diferença, nada vai para o log. Tabelas sem chave primária não pagam nada.
- Quando há diferença a corrigir, a transação que disparou a conferência e já tinha lido a tabela pode receber conflito de versão. Repita a transação.
- Um nó retirado do cluster e aberto sozinho não refaz essa conferência. Antes de usá-lo assim, restaure nele um backup, o que reconstrói os registros internos.
- Num índice `UNIQUE`, um registro interno que a linha não confirma é apagado quando atrapalha uma escrita. Isso não vale para tabela particionada.
- Um índice `UNIQUE` grande é construído por cada nó. Se a liderança passar a um nó que ainda não terminou a construção, ele recusa gravar valores desse índice, com a mensagem `unique index '...' is still building on this node, retry the write shortly` (no idioma configurado), até terminar. Se a construção parar por falha da camada fria, redefina o índice.

## Compatibilidade na atualização

O roteiro de atualização, a drenagem, a transferência de liderança e os riscos de misturar versões estão em [operacao-e-resiliencia.md](operacao-e-resiliencia.md). Da 0.10.x ou anterior, atualize só com os três nós parados: um nó de cada vez a partir dessas versões não é suportado, porque elas podem [confirmar escritas sem gravar](operacao-e-resiliencia.md#defeito-na-0101-e-anteriores-escrita-confirmada-sem-gravar). Entre versões novas, atualize um nó de cada vez. O motor ainda entende o protocolo anterior (um líder novo fala com um membro antigo uma entrada por conexão, e um membro novo aceita um líder antigo), o que reduz o dano de uma mistura de versões, mas não a torna suportada. Enquanto houver nó antigo, a vazão de replicação para ele é a da versão anterior. Com líder antigo, os seguidores novos não atendem `linearizable` nem `max-lag` (o líder antigo não responde à consulta); `stale`, o padrão, continua funcionando. Requisições com outro `AXON_CLUSTER_ID` são recusadas.

## Limites conhecidos

- A troca de membros continua sendo a das versões anteriores (uma mudança por vez, sem consenso conjunto), e a lista de membros não é recarregada em execução: um seguidor passa a usar a lista nova só depois de reiniciado. Não faça uma segunda mudança antes de reiniciar os seguidores. A lista gravada a partir de uma mudança feita nesta versão inclui o líder, e cada nó tira a si mesmo dela; uma lista gravada por versão anterior continua como estava até a próxima mudança de membros.
- Não há pré-voto: um nó que fica de 350 a 850 ms sem notícia do líder abre eleição, e o líder antigo deixa o cargo. As escritas pendentes nesse instante ficam sem desfecho. Disco lento no líder não basta para isso por até 10 segundos (os batimentos saem por uma conexão própria quando o envio das entradas para; depois desse prazo eles cessam e o cluster elege outro líder), nem um seguidor ainda recebendo requisições. Uma pausa longa da máquina virtual ou falta de CPU ainda bastam; o log do nó registra o atraso (`relógio interno atrasado`, `sem envio há`, `abre eleição`).
- O snapshot enviado pela rede monta o estado inteiro em memória.
- A porta do Raft não tem autenticação nem cifra, como nas versões anteriores: exponha-a só numa rede privada.
- Um learner não é guardado no log: depois de uma troca de líder ele se apresenta ao novo líder sozinho.
