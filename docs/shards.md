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
| número ou texto numérico | decimal sem zeros à direita (`1`, `1.0` e `"001"` são o mesmo slot, porque o motor os trata como iguais) |
| outro texto, UUID (Universally Unique Identifier) | o próprio texto |
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

- os nomes da tabela e do campo de shard usam só letras, números e `_`;
- `SHARD BY` exige credencial root, porque a conferência de tabela vazia ignora permissões;
- o campo de shard aceita só os tipos `string`, `int`, `number`, `decimal` ou nenhum, sem `VALUE`;
- a tabela não pode ter `PERMISSIONS`, evento, Data Rule que use o campo de shard, nem ser citada por um evento de outra tabela;
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

Valores aceitos para rotear são literais, parâmetros, record ids e listas deles. Com `SHARD BY id` e um `CREATE` ou `INSERT` sem id, o gateway gera o id antes de enviar.

## Consultas em vários shards

Cada shard recebe a consulta original e a executa em modo **parcial**:

- **Linhas:** o shard filtra, ordena e corta em `START + LIMIT`, e devolve cada linha com os valores do `ORDER BY` e a chave do registro. O gateway intercala por esses valores, desempata pela chave (a mesma ordem de um nó único) e só então aplica `START` e `LIMIT`.
- **Agregação:** o shard agrupa e devolve os **acumuladores** de cada grupo (contagem, soma, soma e contagem da média, conjunto de valores distintos, mínimo, máximo), não o resultado final. O gateway funde os acumuladores com o mesmo código que o motor usa na varredura paralela e só então calcula as colunas, o `HAVING`, o `ORDER BY`, o `START` e o `LIMIT`.

Assim o resultado de `count`, `sum`, `avg`, `min`, `max`, `count(DISTINCT x)`, `GROUP BY`, `GROUP ALL` e `HAVING` é o de um nó único. Duas diferenças possíveis, ambas fora da garantia da linguagem: sem `ORDER BY`, um nó único que use um índice pode devolver outra ordem, e a soma de números de ponto flutuante pode diferir na última casa porque a ordem das parcelas muda.

Cada parte passa pela auditoria por IA do shard que a executa, como uma consulta em `/sql`. Com Data Rule que mascara colunas, a agregação espalhada é recusada, e o `ORDER BY` só aceita colunas sem máscara: a resposta de cada shard leva a chave do registro e os valores do `ORDER BY`. São recusados o `ORDER BY` que cite uma coluna mascarada (direta, por apelido ou dentro de expressão), construções que o motor não consegue conferir (subconsulta, grafo, `*`, programas) e qualquer consulta espalhada de um usuário cuja máscara alcance `id`.

O catálogo de casos e de usuários bloqueados da auditoria é de cada shard. Um bloqueio num shard não vale nos outros, e `SHOW AI AUDIT`, `AUDIT_CASES` e a decisão de um caso vão ao shard base. Aprovar um caso executa a consulta só nele.

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
| blocos `{ }` | as instruções de dentro escapariam das recusas |
| `PERMISSIONS` em tabela dividida | a cópia de uma movimentação deixaria registros para trás |
| [partição por tempo](particoes-e-camada-fria.md) e TTL (Time To Live) em tabela dividida, inclusive `SHARD BY` numa tabela que já os tenha | a regra ficaria só no shard base |
| `LET` dentro de `BEGIN` | o gateway não saberia o valor que decide o shard |
| cabeçalho `Axon-Role-Cap` | o gateway não o aplica, e ignorá-lo daria mais poder do que o pedido |

Numa base que tenha ao menos uma tabela dividida, `FETCH`, travessia de grafo, blocos, chamadas de programa e subconsultas que leiam uma tabela dividida são recusados também nas tabelas do shard base. A origem de um `SELECT`, `CREATE`, `UPDATE` ou `DELETE` precisa dizer a tabela pelo texto: o nome, um record id ou uma variável com um record id de tabela sem `SHARD BY`. Links para outra base são recusados quando o conjunto tem alguma tabela dividida. Um campo alcançado por um record link que aponte para uma tabela dividida (`SELECT ref.total FROM cidade`) **não** é recusado e vem vazio.

### O que o gateway calcula sozinho

O gateway calcula localmente só expressões puras: literais, variáveis definidas pelo cliente, operadores e funções que não leem dados, sessão nem executam instruções. Um `LET` com qualquer outra coisa é enviado ao shard base, com a credencial do cliente. Nos dados de um `INSERT`, na chave de um `CREATE`, em `START`, `LIMIT` e nas colunas de grupos de uma consulta espalhada, uma expressão não pura (por exemplo `$auth` ou `record::exists`) é recusada.

Antes de calcular qualquer coisa além de literais, e antes de responder uma instrução sem consultar shard algum, o gateway pede ao shard base que confira a credencial do cliente (uma ida a mais por requisição). Qualquer nó do shard base responde, sem precisar de líder. Sem credencial válida num conjunto com autenticação, a requisição é recusada. Cada requisição tem um orçamento de 64 Mi caracteres para os valores que o gateway produz ou consome nesses cálculos: um `string::repeat` que passaria dele é recusado antes de ser executado. As colunas calculadas ao fundir os grupos de uma agregação espalhada não entram nesse orçamento.

## Escrita

- **Um shard:** a instrução segue como veio, com as garantias de um cluster comum.
- **`INSERT` em vários shards:** permitido. É atômico em cada shard e **não** entre eles. Em falha, a mensagem diz quais shards gravaram. Um id que já existe faz o shard dono recusar a parte dele (desde a 1.0.0-rc2), e os outros shards podem já ter gravado. `INSERT IGNORE` pula o id existente.
- **`UPDATE` ou `DELETE` por record id em tabela dividida por outro campo:** vai a todos, porque no máximo um registro existe.
- **Outras escritas em mais de um shard:** só dentro de uma saga. Fora dela são recusadas.
- **`UPSERT`:** precisa cair num shard só, e os dados precisam trazer a chave de shard.
- **Chave de shard é imutável:** um `UPDATE` que a mude é recusado, inclusive um `SET` num caminho dentro dela. Para mudar, apague e crie de novo, numa saga.
- **`PRIMARY KEY` em tabela dividida:** precisa conter a chave de shard (ou a coluna `id`), porque só é conferida dentro de cada shard.
- **Chave numérica é inteira:** um valor fracionário na chave é recusado, porque a conversão de tipo mudaria o shard.
- **Escrita em vários shards:** o gateway relê o mapa antes de cada shard seguinte e para, informando quem gravou, se a época mudou.
- **Record id explícito em tabela dividida por outro campo:** recusado por padrão, porque dois shards poderiam guardar o mesmo id. `AXON_GATEWAY_EXPLICIT_IDS=check` consulta todos os shards antes de criar, e então o `INSERT IGNORE` pula o id que existe em qualquer shard. A consulta não é atômica: duas criações simultâneas do mesmo id com chaves de shard diferentes passam as duas. Quem precisa de id único e escolhido usa `SHARD BY id`.

### Transações e sagas

`BEGIN` e `COMMIT` valem quando todas as instruções do bloco caem no **mesmo shard**: o bloco inteiro é enviado a ele. Caso contrário, é recusado.

Entre shards vale a saga que já é nativa:

```text
BEGIN SAGA transferencia WITH CORRELATION 'p-1';
CREATE pedido SET cliente_id = 'ana', total = 10;
CREATE pedido SET cliente_id = 'bia', total = 20;
COMMIT SAGA transferencia WITH CORRELATION 'p-1';
```

Cada shard registra os próprios passos. `COMMIT` e `CANCEL` vão a todos os shards em que a saga está aberta, então qualquer gateway consegue terminar uma saga que outro começou. `CANCEL` compensa cada shard e é recusado durante uma movimentação de slots. A saga **não é atômica**: leitores veem estados intermediários, e uma queda no meio deixa a saga aberta até alguém concluí-la ou cancelá-la. `INSERT` dentro de saga é recusado porque a saga nativa não registra os passos dele.

## Carimbo de tempo entre shards

Toda mensagem entre gateway e shard leva o carimbo do relógio híbrido, o HLC (Hybrid Logical Clock), nos dois sentidos, com a regra de recebimento do [plano](plano-escala-e0-e5.md#carimbo-de-tempo-dos-lotes). Se uma escrita B é enviada depois que o mesmo gateway recebeu a confirmação de A, o carimbo de B é maior, mesmo em shards diferentes. Entre gateways diferentes, o cliente preserva a ordem devolvendo o cabeçalho `Axon-HLC` da última resposta. Sem ele, a ordem entre gateways vale até o desvio dos relógios.

## Plano de controle e movimentação de slots

Mover slots do shard de origem para o de destino:

1. **Anunciar:** o mapa recebe a movimentação (fase de cópia). Os gateways passam a recusar escritas nos slots em movimento, com um erro que pode ser repetido, e escritas em vários shards.
2. **Esperar** duas validades do mapa somadas a dois tempos máximos de requisição.
3. **Copiar** os registros dos slots para o destino e conferir o conteúdo.
4. **Trocar o dono:** o mapa passa a apontar o destino (fase de limpeza).
5. **Esperar** de novo, **apagar** os registros da origem e **encerrar** a movimentação.

As leituras seguem durante todo o processo. Enquanto há movimentação, cada shard recebe junto da consulta a lista dos slots que lhe pertencem e ignora o resto, então a cópia parcial nunca é contada duas vezes.

Cada passo fica gravado no mapa. Uma queda no meio se resolve repetindo a movimentação, que continua de onde parou. Antes da troca de dono dá para desistir, o que apaga a cópia do destino.

Uma movimentação não começa enquanto o shard de origem tiver saga aberta, porque a compensação gravaria no dono antigo. Como toda saga é aberta também no shard base, mover slots dele exige que não haja saga nenhuma aberta.

Cada execução (mover, retomar ou desistir) registra no mapa quem conduz a movimentação. Quem perde esse lugar para antes do passo seguinte, então dois operadores não apagam as duas cópias.

A cópia, a conferência e a limpeza rodam em modo de manutenção, sem usuário, para que Data Rules e máscaras não deixem registros para trás.

**Pré-condições:** todo acesso passa pelo gateway (escrever direto num shard fura a proteção), e nenhuma escrita dura mais que o tempo máximo configurado. A proteção é por tempo, não por uma trava no shard: uma requisição que ultrapasse esse limite (processo pausado, rede que entrega muito depois) pode gravar no dono antigo.

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

A E2 deixou o journal desligado em cluster. Nos shards ele passa a arquivar cada entrada aplicada do Raft, com o índice do log e o carimbo do líder. É o mesmo journal da [recuperação de desastre](operacao-e-resiliencia.md#journal-em-cluster): ao voltar de uma queda, o nó reentrega a ele o que aplicou depois do último lote arquivado e que o log ainda guarda. Um nó que receba o estado inteiro pela rede, ou que já tenha descartado do log esse trecho, fica com um buraco no journal, e a restauração se recusa a atravessá-lo.

### Restauração

Restaurar a um instante `T`, igual ou posterior ao corte do manifesto:

- cada shard parte do backup físico e reaplica, em ordem de índice, os lotes com carimbo até `T`;
- o journal precisa ter, sem falhas, todos os índices desde o backup até uma entrada **posterior** a `T`, que prova que nada ficou de fora. Uma barreira periódica mantém essa prova em shards sem escrita;
- ou todos os shards ficam preparados ou nenhum;
- se os slots mudaram de dono entre o backup e `T`, ou havia movimentação em `T`, a restauração é recusada: o shard que recebeu os registros pode não estar no manifesto. Faça um backup novo depois de cada movimentação;
- a base preparada perde a posição do Raft. Cada membro do shard recebe a mesma cópia e começa com o diretório do Raft vazio.

O resultado é consistente para tudo que é causalmente relacionado. Uma saga não é atômica entre shards, então o corte pode cair no meio dela.

## Operação

### Nós de shard

Cada shard é um cluster comum (veja [cluster-raft.md](cluster-raft.md)). O gateway usa a rota interna `/shard/v1/` dos nós, servida por HTTP (HyperText Transfer Protocol). A rota só existe num nó com `AXON_SHARD_SECRET` definido: sem a variável, ela responde 404, como num nó comum. Com a variável, toda chamada precisa trazer o mesmo segredo no cabeçalho `Axon-Shard-Secret` e, além dele, passa pela mesma autenticação do `/sql`. Quem tem o segredo fala com o shard como o gateway, então guarde-o como uma senha, use um valor diferente de `AXON_SECRET` e, sem TLS (Transport Layer Security) nos nós, mantenha o tráfego em rede privada. As operações de manutenção (definições, movimentação, conferências de `SHARD BY` e backup) exigem um administrador root. As consultas que chegam por ela seguem as regras do `/sql`: aparecem na tela de atividade (protocolo `shard`), podem ser canceladas e respeitam `AXON_QUERY_TIMEOUT`. Uma escrita (bloco atômico ou comando que grava) que estoura esse prazo, ou é interrompida na espera, responde desfecho desconhecido (`OUTCOME_UNKNOWN`), porque ainda pode ser confirmada: confira o estado antes de repetir. A manutenção (movimentação e conferências do gateway) não tem esse prazo, porque a cópia de uma movimentação lê tabelas inteiras, mas também pode ser cancelada. O shard não sabe quando o gateway desiste: mantenha `AXON_QUERY_TIMEOUT` próximo de `AXON_GATEWAY_DEADLINE_MS` para a consulta não continuar muito além do que o cliente esperou. Para o backup do cluster, ligue o journal em cada nó:

| Variável | Padrão | O que faz |
|---|---|---|
| `AXON_SHARD_SECRET` | vazio | torna o nó um shard e é o segredo exigido do gateway |
| `AXON_PITR` | desligado | `on` liga o journal de shard num nó de cluster com RocksDB |
| `AXON_PITR_DIR` | `<dados>/.pitr-journal` | onde ficam os segmentos |
| `AXON_SHARD_BACKUP_DIR` | `<dados>/.shard-backups` | onde ficam os backups físicos pedidos pelo coordenador |

### Gateway

```bash
AXON_GATEWAY_SHARDS='s0=http://a1:8000,http://a2:8000,http://a3:8000;s1=http://b1:8000,http://b2:8000,http://b3:8000' AXON_GATEWAY_SHARD_USER=root AXON_GATEWAY_SHARD_PASS=<senha> AXON_GATEWAY_ADMIN_TOKEN=<segredo> AXON_SHARD_SECRET=<segredo dos shards> axonbase gateway
```

| Variável | Padrão | O que faz |
|---|---|---|
| `AXON_GATEWAY_SHARDS` | vazio | shards para criar o mapa na primeira subida. Depois disso vale o mapa gravado |
| `AXON_GATEWAY_META` | nós do primeiro shard | nós do grupo de metadados |
| `AXON_SHARD_SECRET` | obrigatória | o mesmo segredo dos nós de shard. Sem ela, o gateway não sobe |
| `AXON_GATEWAY_SHARD_USER` e `AXON_GATEWAY_SHARD_PASS` | vazios | credencial do próprio gateway, usada no plano de controle, na movimentação e no backup, nunca em requisições de clientes |
| `AXON_GATEWAY_ADMIN_TOKEN` | vazio | credencial das rotas de administração. Vazia, elas ficam desligadas |
| `AXON_GATEWAY_BIND` e `AXON_GATEWAY_PORT` | `127.0.0.1` e 8080 | onde o gateway escuta |
| `AXON_GATEWAY_MAP_TTL_MS` | 2000 | validade da cópia do mapa |
| `AXON_GATEWAY_DEADLINE_MS` | 15000 | tempo máximo de uma requisição a um shard |
| `AXON_GATEWAY_EXPLICIT_IDS` | `refuse` | `check` aceita record id escolhido em tabela dividida por outro campo |

O gateway não tem TLS próprio: ponha-o atrás de um balanceador que termine a conexão cifrada, ou numa rede privada. Rode **uma** instância das rotas de administração por vez.

O cliente usa `POST /sql` com os cabeçalhos `Axon-Ns`, `Axon-Db` e `Authorization`, como num nó. A resposta traz `Axon-HLC`; devolva-o na próxima requisição para preservar a ordem entre gateways.

### Rotas de administração

Todas são `POST`, com `Authorization: Bearer <AXON_GATEWAY_ADMIN_TOKEN>`.

| Rota | Corpo | O que faz |
|---|---|---|
| `/admin/shard/add` | `{"id":"s3","endpoints":["http://..."]}` | copia as definições para um shard vazio e o põe no mapa, sem slots |
| `/admin/shard/move` | `{"slots":"0-511","target":"s3"}` | move slots de um mesmo dono |
| `/admin/shard/resume` | vazio | retoma uma movimentação interrompida |
| `/admin/shard/abort` | vazio | desiste antes da troca de dono |
| `/admin/backup/create` | vazio | faz o backup do cluster e devolve o manifesto: guarde-o |
| `/admin/backup/barrier` | vazio | grava uma barreira em todos os shards e devolve o instante |
| `/admin/backup/seal` | o manifesto | fecha os journals que o manifesto usa |

`GET /shard/v1/map` devolve o mapa.

Para a restauração a um instante alcançar o presente, agende a barreira (por exemplo a cada minuto). Os segmentos fecham sozinhos em até 30 segundos. Copie os diretórios de backup e de journal para fora das máquinas: o manifesto só anota os caminhos em cada nó. Um nó que caiu ou recebeu o estado inteiro pela rede pode deixar um buraco no journal, então tire um backup novo depois desses eventos. A retenção dos backups de shard é manual. Se o shard base tiver tabelas na [camada fria](particoes-e-camada-fria.md), cada backup bloqueia sem prazo os objetos que referencia, e eles precisam continuar no armazenamento para a restauração.

### Restaurar

Junte numa máquina os diretórios de backup e de journal anotados no manifesto e rode:

```bash
axonbase restore-shards manifesto.json /dados/restaurados --at 2026-11-26T14:32:10Z --source s0=/copias/s0/backup,/copias/s0/journal --source s1=/copias/s1/backup,/copias/s1/journal
```

Se alguma base tiver partições na camada fria, defina `AXON_COLD_DIR` ou `AXON_COLD_S3` no ambiente do comando, como nos nós. Sem isso, ou faltando objeto, nada é restaurado. O comando não abre um motor sobre as bases: uma construção de índice interrompida continua interrompida, e o servidor a retoma ao subir, o que pede a mesma configuração fria nele.

`--at` usa o formato ISO (International Organization for Standardization) 8601 e, sem ele, vale o corte do manifesto. Sem `--source`, valem os caminhos do manifesto. O comando cria um diretório por shard. Suba cada membro do shard com uma cópia dele e o diretório do Raft vazio, e depois o gateway: o mapa volta junto com o grupo de metadados, desde que os endereços sejam os mesmos.

### Portão de carga

O roteiro opt-in em [scripts/shard-gates/run.sh](../scripts/shard-gates/run.sh) mede a ingestão com um shard e com três, em máquinas separadas, e aprova quando a razão é de pelo menos 2,4.

## Compatibilidade

| Instalação | Efeito |
|---|---|
| nó único | nenhum. `SHARD BY` é aceito e ignorado na execução |
| cluster de um grupo | nenhum. Pode virar o shard base de um conjunto |
| conjunto de shards | exige gateway, o mesmo `AXON_SECRET` em todos os nós, o mesmo `AXON_SHARD_SECRET` nos nós e no gateway, e relógios sincronizados |

Transformar um cluster existente em vários shards não redistribui dados de tabelas já criadas: elas ficam no shard base.

## Limites conhecidos

- O gateway atende só `/sql`, por HTTP. Os SDKs (Software Development Kits) que usam RPC (Remote Procedure Call) sobre WebSocket, o `/table`, o `/graphql` e o `/mcp` ainda não passam por ele.
- O tráfego do gateway chega a cada nó por um endereço só: deixe `AXON_RATE_LIMIT` desligado nos shards, ou alto.
- Funções AxonPL chamadas sem o prefixo `fn::` rodam em cada shard contra os dados dele.
- A movimentação copia os registros de cada tabela de uma vez, em memória, e depende do prazo de validade do mapa em vez de uma trava no shard.
- O mapa guarda os endereços dos shards: não há rota para trocá-los num conjunto já criado.
- O backup cobre o grupo de metadados só quando ele é um dos shards (o padrão).
- As escritas de um `INSERT` em vários shards vão uma de cada vez.
- O gateway não divide nem junta shards sozinho: a movimentação é pedida pelo operador.
- Uma consulta espalhada traz até `START + LIMIT` linhas de cada shard.
- Sem `LIMIT`, o resultado inteiro de cada shard passa pela memória do gateway.
