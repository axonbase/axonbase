# Cluster Raft: escrita em grupo, leitura em seguidores e réplica de leitura

Este documento descreve o consenso Raft do motor a partir da fase E2 do [plano de escala](plano-escala-e0-e5.md): o que mudou, como configurar, o que é garantido e os limites. As medidas dos portões ficam em [escala-e-durabilidade.md](escala-e-durabilidade.md).

## Escrita: um fsync para várias propostas

O líder grava as propostas no log sem esperar o disco, e um único fsync (a ordem ao sistema operacional para levar os dados ao disco) atende todas as que chegaram antes dele. A replicação usa uma conexão TCP (Transmission Control Protocol) persistente por membro, junta várias entradas numa requisição e mantém várias requisições em voo, sem esperar a resposta da anterior.

A variável `AXON_STORAGE_SYNC` vale também para o log Raft:

| Valor | Quando um nó conta a entrada para o quórum | Perda possível |
|---|---|---|
| `always` | depois do fsync | nenhuma com quórum disponível |
| `batch` (padrão) | depois de gravar no sistema operacional, com fsync a cada 1 segundo | só se a maioria dos nós sofrer queda do sistema operacional ou de energia no mesmo segundo |
| `off` | depois de gravar no sistema operacional | o que ainda estiver em cache na maioria dos nós |

O termo e o voto de cada nó são sincronizados em disco nos três modos.

**Mudança em relação à 0.10.1:** antes o log Raft fazia fsync a cada entrada, qualquer que fosse o modo. Agora o padrão `batch` troca essa garantia por nó pela garantia do quórum. Quem precisa de zero perda mesmo com queda de energia simultânea deve usar `always`.

O armazenamento não faz fsync por entrada aplicada, em nenhum modo: a durabilidade é a do log, e o nó sincroniza o armazenamento antes de descartar entradas. Cada entrada aplicada grava, no mesmo lote atômico, a chave `__raft/applied` com o índice e o termo. Depois de um reinício o nó continua exatamente desse ponto.

### Escrita sem desfecho

Se o quórum some depois que a entrada já está no log do líder, o cliente recebe o erro de quórum indisponível, mas a escrita **pode** ser confirmada quando o quórum voltar: o resultado é desconhecido, e o cliente deve conferir antes de repetir. Se o líder já sabe que não há maioria, ele recusa antes de gravar, e a escrita certamente não aconteceu. A versão anterior apagava a entrada do próprio log, o que no Raft permite que dois nós guardem lotes diferentes no mesmo índice e termo.

### Transações concorrentes

No cluster, transações que tocam chaves diferentes são confirmadas em paralelo (e dividem o fsync). Duas que tocam a mesma chave entram em fila: se o que a segunda leu mudou, um comando avulso é refeito pelo próprio motor (até 100 vezes), e uma transação explícita (`BEGIN` e `COMMIT`) recebe o conflito de versão, como no nó único. A versão anterior podia perder uma das duas escritas em silêncio e deixar o líder com dados diferentes dos seguidores. Um comando avulso refeito executa de novo as funções que chama, inclusive as que tenham efeito fora do banco.

## Log em segmentos

O log fica em `<diretório raft>/raft-log/`, em segmentos de 64 MiB (mebibytes):

- cada entrada tem um CRC (Cyclic Redundancy Check), um código que detecta bytes alterados;
- truncar apaga os segmentos posteriores e corta um só; compactar apaga segmentos inteiros;
- uma entrada incompleta no fim do último segmento (queda no meio da escrita) é descartada na abertura;
- corrupção em dado já confirmado, ou num segmento que não é o último, **impede o nó de abrir**, com o nome do arquivo na mensagem. A saída é apagar o diretório do Raft para o líder reenviar o estado, ou restaurar o nó de um backup físico;
- bytes ilegíveis numa cauda ainda não confirmada são cortados e guardados num arquivo `.corrupt`;
- o `raft.log` das versões anteriores é convertido na primeira abertura e renomeado para `raft.log.migrated`.

Voltar para uma versão antiga no mesmo diretório não é suportado: ela não lê os segmentos, começa com o log vazio e depende do líder para alcançar o estado. Se depois disso o nó for atualizado de novo, ele se recusa a abrir (há um `raft.log` novo ao lado dos segmentos velhos): apague o diretório do Raft para o líder reenviar o estado.

### O que o log guarda

| Variável | Padrão | Efeito |
|---|---|---|
| `AXON_RAFT_RETAIN_ENTRIES` | 100000 | entradas já aplicadas mantidas no log |
| `AXON_RAFT_RETAIN_MINUTES` | 30 | um segmento só sai do log depois desse tempo sem escrita |

As duas condições precisam valer para um segmento sair. O tempo existe para que um backup de poucos minutos atrás, mais o log, bastem para um nó novo entrar. Quem escreve muito deve dimensionar o disco para 30 minutos de log. Um membro votante atrasado além do que o log guarda recebe o estado inteiro pela rede (snapshot), que é adequado só para bases pequenas.

## Leitura em seguidores

Qualquer nó atende leituras. A consistência é pedida por requisição:

- no `/sql` e no `/table`, no cabeçalho HTTP (HyperText Transfer Protocol) `Axon-Read-Consistency`;
- no RPC (Remote Procedure Call), no campo `consistency`, ao lado de `method`.

| Valor | Garantia |
|---|---|
| `stale` | o que o nó tiver |
| `max-lag=<ms>` | o nó esteve em dia com o líder há no máximo esse tempo |
| `linearizable` | a leitura enxerga toda escrita confirmada antes dela |

Sem o pedido, vale `AXON_RAFT_MAX_READ_LAG_MS`; o padrão `0` não impõe limite, como nas versões anteriores. Quando o nó não alcança a consistência pedida, a resposta é `503` (ou o erro `-32012` no RPC) com `kind: STALE_READ` e o endereço do líder.

A leitura linearizável usa o ReadIndex do Raft: o líder anota o índice confirmado, confirma com uma maioria que ainda é líder, e o nó espera aplicar esse índice (e atualizar o catálogo) antes de ler. Várias leituras dividem a mesma consulta.

Para `max-lag`, o seguidor não confia na hora em que uma mensagem chegou, porque ela pode ter ficado parada na rede. Ele mede pelo próprio relógio: vale uma consulta ao líder iniciada há no máximo o limite pedido. Se não houver uma recente, faz a consulta na hora. Com o padrão sem limite, a leitura não consulta o líder.

Os endpoints `/graphql` e `/mcp` seguem as mesmas regras do `/sql`.

## Réplica de leitura (learner)

Um learner recebe o log e atende leituras, mas não vota e não conta para o quórum. Serve para consultas analíticas e para tirar backups sem pesar no líder.

Para subir um:

1. Tire um backup físico de qualquer membro (veja [backup-fisico.md](backup-fisico.md)).
2. Leve o diretório de backup até o nó novo.
3. Em **todos os votantes**, liste o endereço do learner em `AXON_RAFT_LEARNERS` (por exemplo `10.0.0.9:9000`, separados por vírgula) e reinicie-os, um de cada vez. O learner recebe o banco inteiro, então sem essa lista nenhum é aceito. O valor `*` aceita qualquer endereço e só serve para ambientes de teste.
4. Suba o servidor com a mesma `AXON_CLUSTER_ID`, o próprio `AXON_NODE_ID` e `AXON_RAFT_BIND`, os endereços dos votantes em `AXON_RAFT_PEERS` e:

```bash
AXON_RAFT_LEARNER=true
AXON_RAFT_BOOTSTRAP_BACKUP=/caminho/do/diretorio/de/backup
```

Com a base vazia, o servidor restaura o backup mais novo e pede ao líder só o que veio depois. Com uma base já existente, ela é usada como está. Se o log do líder não alcança mais o backup, o learner fica parado e o líder o lista em `learnersNeedingBackup`: apague a base do learner e entre com um backup mais novo.

Um learner no ar não pode ser promovido a votante pela API (Application Programming Interface) de membros. Reinicie-o sem `AXON_RAFT_LEARNER` antes de incluí-lo.

**Limite atual:** a API de administração do motor recusa criar backup físico em cluster (409). Até essa restrição cair, o backup de um membro precisa ser feito fora dela.

## Compatibilidade na atualização

Atualize um nó de cada vez. Um líder novo percebe um membro antigo e fala com ele o protocolo anterior (uma entrada por conexão), e um membro novo continua aceitando um líder antigo. Enquanto houver nó antigo, a vazão de replicação para ele é a da versão anterior. Com líder antigo, os seguidores novos não atendem `linearizable` nem `max-lag` (o líder antigo não responde à consulta); `stale`, o padrão, continua funcionando. Requisições com outro `AXON_CLUSTER_ID` são recusadas.

## Limites conhecidos

- A troca de membros continua sendo a das versões anteriores (uma mudança por vez, sem consenso conjunto), e a lista de membros gravada pelos seguidores não é recarregada em execução.
- Não há pré-voto: um nó que fica mais de 350 ms sem notícia do líder (máquina muito carregada, pausa longa) abre eleição, e o líder antigo deixa o cargo. As escritas pendentes nesse instante ficam sem desfecho.
- O snapshot enviado pela rede monta o estado inteiro em memória.
- A porta do Raft não tem autenticação nem cifra, como nas versões anteriores: exponha-a só numa rede privada.
- Um learner não é guardado no log: depois de uma troca de líder ele se apresenta ao novo líder sozinho.
