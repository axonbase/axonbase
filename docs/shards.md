# Vários shards: `SHARD BY`, gateway e plano de controle

Este documento descreve a fase E3 do [plano de escala](plano-escala-e0-e5.md): como o motor divide uma tabela entre vários grupos de consenso, o que o gateway aceita e recusa, e como o backup e a restauração a um instante funcionam entre shards.

Um **shard** é um grupo Raft inteiro (três nós, com o próprio líder, log e base) que guarda uma parte dos registros. Um nó único e um cluster de um só grupo continuam funcionando como antes: nada aqui muda o comportamento de quem não usa o gateway.

## Visão geral

```text
cliente ──► gateway (sem estado) ──► shard 0 (grupo Raft, também guarda o plano de controle)
                                 ├─► shard 1 (grupo Raft)
                                 └─► shard 2 (grupo Raft)
```

- **Gateway:** recebe AxonQL, decide quais shards atendem cada instrução, envia, junta as respostas e devolve ao cliente. Não guarda dado nenhum: qualquer instância atende qualquer cliente, e derrubar uma não perde nada.
- **Shard:** um cluster AxonBase comum. Ele não sabe que faz parte de um conjunto, exceto por uma rota interna que o gateway usa.
- **Plano de controle:** o mapa dos shards, gravado como um registro comum no **grupo de metadados** (por padrão o próprio shard 0). Herda a replicação e o quórum do Raft, sem componente novo.

## Modelo de dados

### Hash e slots

O espaço de chaves é dividido em **4096 slots**. O slot de um registro é um hash estável do **valor da chave de shard**, na forma de texto, calculado com FNV-1a (Fowler-Noll-Vo) de 64 bits e uma mistura final:

| Chave | Texto usado no hash |
|---|---|
| número | decimal sem zeros à direita (`1` e `1.0` são o mesmo slot) |
| texto, UUID (Universally Unique Identifier) | o próprio texto |
| `SHARD BY id` | a parte da chave do record id (`pedido:42` usa `42`) |
| campo com record id | o texto completo (`cliente:5`) |

O nome da tabela **não** entra no hash. Assim, tabelas divididas pelo mesmo valor ficam no mesmo shard: `pedido SHARD BY cliente_id` e `item SHARD BY cliente_id` colocam o mesmo cliente junto.

Valor ausente, `NULL`, `NONE`, objeto ou lista não servem de chave de shard: a escrita é recusada.

### Mapa dos shards

O mapa tem uma **época** (cresce a cada mudança), a lista de shards com os endereços dos nós, o dono de cada slot, o **shard base** (o primeiro, onde moram as tabelas sem `SHARD BY`), as tabelas divididas com o campo de cada uma e, quando existe, a movimentação em andamento.

O gateway guarda uma cópia do mapa e **nunca usa uma cópia mais velha que o prazo de validade** (`AXON_GATEWAY_MAP_TTL_MS`, padrão 2000 ms). Sem conseguir renovar, ele recusa as requisições. Esse prazo é o que torna a movimentação de slots segura.

## Sintaxe

```text
DEFINE TABLE pedido SCHEMALESS SHARD BY cliente_id
DEFINE TABLE evento SHARD BY id
```

`SHARD BY` vem depois de `SCHEMAFULL` ou `SCHEMALESS` e antes de `PERMISSIONS`. Recebe um campo de primeiro nível ou `id`. Num nó único a cláusula só é guardada no catálogo, sem efeito na execução. Um binário anterior não entende a cláusula: atualize todos os nós antes de usá-la.

Pelo gateway:

- a tabela precisa estar vazia quando recebe `SHARD BY` (dados já gravados ficariam no shard base);
- o campo de shard não pode ser trocado depois;
- todo `DEFINE`, `DROP`, `ALTER` e `REMOVE` é repetido em **todos** os shards, em ordem. Se um falhar, o gateway informa quais receberam e a instrução pode ser repetida.

## Roteamento

O gateway decide pelo texto da instrução, sem consultar dados:

| Instrução | Destino |
|---|---|
| tabela sem `SHARD BY` | shard base |
| `WHERE campo = valor` ou `campo IN [..]` sobre a chave de shard, em conjunção | os shards desses valores |
| `FROM tabela:chave` ou `WHERE id = ...` com `SHARD BY id` | o shard da chave |
| `SELECT` sem a chave de shard | todos (consulta espalhada) |
| `CREATE` e `INSERT` | o shard da chave de cada registro |

Valores aceitos para rotear são literais, parâmetros e expressões sem acesso à base. Com `SHARD BY id` e um `CREATE` ou `INSERT` sem id, o gateway gera o id antes de enviar.

## Consultas em vários shards

Cada shard recebe a consulta original e a executa em modo **parcial**:

- **Linhas:** o shard filtra, ordena e corta em `START + LIMIT`, e devolve cada linha com os valores do `ORDER BY` e a chave do registro. O gateway intercala por esses valores, desempata pela chave (a mesma ordem de um nó único) e só então aplica `START` e `LIMIT`.
- **Agregação:** o shard agrupa e devolve os **acumuladores** de cada grupo (contagem, soma, soma e contagem da média, conjunto de valores distintos, mínimo, máximo), não o resultado final. O gateway funde os acumuladores com o mesmo código que o motor usa na varredura paralela e só então calcula as colunas, o `HAVING`, o `ORDER BY`, o `START` e o `LIMIT`.

Assim o resultado de `count`, `sum`, `avg`, `min`, `max`, `count(DISTINCT x)`, `GROUP BY`, `GROUP ALL` e `HAVING` é o de um nó único. Duas diferenças possíveis, ambas fora da garantia da linguagem: sem `ORDER BY`, um nó único que use um índice pode devolver outra ordem, e a soma de números de ponto flutuante pode diferir na última casa porque a ordem das parcelas muda.

`COUNT(DISTINCT x)` transfere o conjunto de valores de cada shard: a memória cresce com o número de valores distintos.

### Recusado em tabelas divididas

O gateway recusa, com uma mensagem que diz o motivo, em vez de devolver um resultado incompleto:

| Recurso | Motivo |
|---|---|
| `JOIN`, `FETCH`, subconsulta, travessia de grafo | o outro lado pode estar em outro shard |
| `RELATE`, `UNRELATE`, `INSERT RELATION` | as duas direções da aresta ficariam num shard só |
| busca textual `@@` e busca híbrida | a pontuação depende das estatísticas de cada shard |
| viagem no tempo (`AT`, `BEFORE`) | o ponto histórico é local de cada shard |
| `LIVE SELECT`, `KILL` | assinatura em vários shards não existe |
| `DEFINE EVENT`, campo com `REFERENCES` | rodariam só contra os dados do próprio shard |
| `CALL`, `fn::`, `IF` com corpo de escrita | o programa rodaria num shard só |
| índice `UNIQUE` sem o campo de shard | a unicidade só vale dentro de um shard |

Numa base que tenha ao menos uma tabela dividida, `FETCH`, travessia de grafo, chamadas de programa e subconsultas que leiam uma tabela dividida são recusados também nas tabelas do shard base.

## Escrita

- **Um shard:** a instrução segue como veio, com as garantias de um cluster comum.
- **`INSERT` em vários shards:** permitido. É atômico em cada shard e **não** entre eles. Em falha, a mensagem diz quais shards gravaram.
- **`UPDATE` ou `DELETE` por record id em tabela dividida por outro campo:** vai a todos, porque no máximo um registro existe.
- **Outras escritas em mais de um shard:** só dentro de uma saga. Fora dela são recusadas.
- **`UPSERT`:** precisa cair num shard só, e os dados precisam trazer a chave de shard.
- **Chave de shard é imutável:** um `UPDATE` que a mude é recusado. Para mudar, apague e crie de novo, numa saga.
- **Record id explícito em tabela dividida por outro campo:** recusado por padrão, porque dois shards poderiam guardar o mesmo id. `AXON_GATEWAY_EXPLICIT_IDS=check` consulta todos os shards antes de criar. A consulta não é atômica: duas criações simultâneas do mesmo id com chaves de shard diferentes passam as duas. Quem precisa de id único e escolhido usa `SHARD BY id`.

### Transações e sagas

`BEGIN` e `COMMIT` valem quando todas as instruções do bloco caem no **mesmo shard**: o bloco inteiro é enviado a ele. Caso contrário, é recusado.

Entre shards vale a saga que já é nativa:

```text
BEGIN SAGA transferencia WITH CORRELATION 'p-1';
CREATE pedido SET cliente_id = 'ana', total = 10;
CREATE pedido SET cliente_id = 'bia', total = 20;
COMMIT SAGA transferencia WITH CORRELATION 'p-1';
```

Cada shard registra os próprios passos. `COMMIT` e `CANCEL` vão a todos os shards em que a saga está aberta, então qualquer gateway consegue terminar uma saga que outro começou. `CANCEL` compensa cada shard. A saga **não é atômica**: leitores veem estados intermediários, e uma queda no meio deixa a saga aberta até alguém concluí-la ou cancelá-la. `INSERT` dentro de saga é recusado porque a saga nativa não registra os passos dele.

## Carimbo de tempo entre shards

Toda mensagem entre gateway e shard leva o carimbo do relógio híbrido, o HLC (Hybrid Logical Clock), nos dois sentidos, com a regra de recebimento do [plano](plano-escala-e0-e5.md#carimbo-de-tempo-dos-lotes). Se uma escrita B é enviada depois que o mesmo gateway recebeu a confirmação de A, o carimbo de B é maior, mesmo em shards diferentes. Entre gateways diferentes, o cliente preserva a ordem devolvendo o cabeçalho `Axon-HLC` da última resposta. Sem ele, a ordem entre gateways vale até o desvio dos relógios.

## Plano de controle e movimentação de slots

Mover slots do shard de origem para o de destino:

1. **Anunciar:** o mapa recebe a movimentação (fase de cópia). Os gateways passam a recusar escritas nos slots em movimento, com um erro que pode ser repetido, e escritas em vários shards.
2. **Esperar** o prazo de validade do mapa somado ao tempo máximo de uma requisição.
3. **Copiar** os registros dos slots para o destino e conferir as contagens.
4. **Trocar o dono:** o mapa passa a apontar o destino (fase de limpeza).
5. **Esperar** de novo, **apagar** os registros da origem e **encerrar** a movimentação.

As leituras seguem durante todo o processo. Enquanto há movimentação, cada shard recebe junto da consulta a lista dos slots que lhe pertencem e ignora o resto, então a cópia parcial nunca é contada duas vezes.

Cada passo fica gravado no mapa. Uma queda no meio se resolve repetindo a movimentação, que continua de onde parou. Antes da troca de dono dá para desistir, o que apaga a cópia do destino.

**Pré-condições:** todo acesso passa pelo gateway (escrever direto num shard fura a proteção), e nenhuma escrita dura mais que o tempo máximo configurado.

## Falhas

| Falha | O que o gateway faz |
|---|---|
| troca de líder num shard | procura o novo líder e repete, até o prazo |
| shard sem quórum | a requisição que depende dele falha. As que não dependem seguem |
| consulta espalhada com um shard fora | falha inteira: nunca há resultado parcial |
| desfecho desconhecido (`OUTCOME_UNKNOWN`) | repassa ao cliente e não repete |
| grupo de metadados fora | atende só até o mapa em cache vencer, depois recusa |
| queda do gateway | o cliente usa outro. Sagas abertas podem ser concluídas por ele |
| relógio adiantado além do limite | o shard recusa o carimbo e a requisição falha |

## Backup do cluster e restauração a um instante

### Backup

1. Cada shard tira um backup físico, de preferência num seguidor.
2. **Barreira:** o coordenador toma o maior relógio entre os shards como corte, avança todos até ele e grava em cada um uma entrada de barreira no log do Raft. Todo lote confirmado depois dela tem carimbo maior que o corte.
3. Cada shard fecha o segmento do journal, que precisa conter a barreira.
4. O **manifesto do cluster** guarda o corte e, por shard, o backup, o índice do log que ele contém, o índice da barreira e o journal. O mapa dos shards vai junto.

O backup é recusado durante uma movimentação.

### Journal em cluster

A E2 deixou o journal desligado em cluster. Nos shards ele passa a arquivar cada entrada aplicada do Raft, com o índice do log e o carimbo do líder. Um nó que receba o estado inteiro pela rede fica com um buraco no journal, e a restauração se recusa a atravessá-lo.

### Restauração

Restaurar a um instante `T`, igual ou posterior ao corte do manifesto:

- cada shard parte do backup físico e reaplica, em ordem de índice, os lotes com carimbo até `T`;
- o journal precisa ter, sem falhas, todos os índices desde o backup até uma entrada **posterior** a `T`, que prova que nada ficou de fora. Uma barreira periódica mantém essa prova em shards sem escrita;
- ou todos os shards ficam preparados ou nenhum;
- a base preparada perde a posição do Raft. Cada membro do shard recebe a mesma cópia e começa com o diretório do Raft vazio.

O resultado é consistente para tudo que é causalmente relacionado. Uma saga não é atômica entre shards, então o corte pode cair no meio dela.

## Compatibilidade

| Instalação | Efeito |
|---|---|
| nó único | nenhum. `SHARD BY` é aceito e ignorado na execução |
| cluster de um grupo | nenhum. Pode virar o shard base de um conjunto |
| conjunto de shards | exige gateway, o mesmo `AXON_SECRET` em todos os nós e relógios sincronizados |

Transformar um cluster existente em vários shards não redistribui dados de tabelas já criadas: elas ficam no shard base.

## Limites conhecidos

- O gateway atende só `/sql`, por HTTP (HyperText Transfer Protocol). Os SDKs (Software Development Kits) que usam RPC (Remote Procedure Call) sobre WebSocket, o `/table`, o `/graphql` e o `/mcp` ainda não passam por ele.
- O tráfego do gateway chega a cada nó por um endereço só: deixe `AXON_RATE_LIMIT` desligado nos shards, ou alto.
- Funções AxonPL chamadas sem o prefixo `fn::` rodam em cada shard contra os dados dele.
- A movimentação copia os registros de cada tabela de uma vez, em memória, e depende do prazo de validade do mapa em vez de uma trava no shard.
- O gateway não divide nem junta shards sozinho: a movimentação é pedida pelo operador.
- Uma consulta espalhada traz até `START + LIMIT` linhas de cada shard.
- Sem `LIMIT`, o resultado inteiro de cada shard passa pela memória do gateway.
