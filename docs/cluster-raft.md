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

Com a base vazia, o servidor restaura o backup mais novo e pede ao líder só o que veio depois. Com uma base já existente, ela é usada como está. Se o log do líder não alcança mais o backup, o learner fica parado e o líder o lista em `learnersNeedingBackup`: apague a base do learner e entre com um backup mais novo.

Um learner no ar não pode ser promovido a votante pela API (Application Programming Interface) de membros. Reinicie-o sem `AXON_RAFT_LEARNER` antes de incluí-lo.

Restaurar ou importar um backup físico pela API continua recusado em cluster: trocaria a base de um membro por fora do consenso. Para repor um membro, apague a base e o diretório do Raft dele e entre pelo backup, como acima.

## Carimbo de tempo

Cada entrada do log leva um carimbo de um relógio híbrido, o HLC (Hybrid Logical Clock). O líder carimba ao propor, e seguidores e learners adotam o carimbo recebido, então a ordem do log é a ordem dos carimbos mesmo depois de uma troca de líder. Um lote que chegue mais de 500 ms à frente do relógio local é recusado e reenviado: sincronize os nós com o `chrony`. Com `AXON_PITR=on`, cada nó arquiva os lotes confirmados para a [recuperação de desastre](operacao-e-resiliencia.md#recuperação-de-desastre). Detalhes do carimbo em [pitr.md](pitr.md#o-carimbo-de-tempo).

## Compatibilidade na atualização

O roteiro de atualização, a drenagem, a transferência de liderança e os riscos de misturar versões estão em [operacao-e-resiliencia.md](operacao-e-resiliencia.md). Atualize um nó de cada vez. Um líder novo percebe um membro antigo e fala com ele o protocolo anterior (uma entrada por conexão), e um membro novo continua aceitando um líder antigo. Enquanto houver nó antigo, a vazão de replicação para ele é a da versão anterior. Com líder antigo, os seguidores novos não atendem `linearizable` nem `max-lag` (o líder antigo não responde à consulta); `stale`, o padrão, continua funcionando. Requisições com outro `AXON_CLUSTER_ID` são recusadas.

## Limites conhecidos

- A troca de membros continua sendo a das versões anteriores (uma mudança por vez, sem consenso conjunto), e a lista de membros gravada pelos seguidores não é recarregada em execução.
- Não há pré-voto: um nó que fica de 350 a 850 ms sem notícia do líder abre eleição, e o líder antigo deixa o cargo. As escritas pendentes nesse instante ficam sem desfecho. Disco lento no líder não basta para isso por até 10 segundos (os batimentos saem por uma conexão própria quando o envio das entradas para; depois desse prazo eles cessam e o cluster elege outro líder), nem um seguidor ainda recebendo requisições. Uma pausa longa da máquina virtual ou falta de CPU ainda bastam; o log do nó registra o atraso (`relógio interno atrasado`, `sem envio há`, `abre eleição`).
- O snapshot enviado pela rede monta o estado inteiro em memória.
- A porta do Raft não tem autenticação nem cifra, como nas versões anteriores: exponha-a só numa rede privada.
- Um learner não é guardado no log: depois de uma troca de líder ele se apresenta ao novo líder sozinho.
