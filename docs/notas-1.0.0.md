# Notas da versão 1.0.0

A 1.0.0 é a primeira versão final da linha 1.0. O código do motor é o da [1.0.0-rc2](notas-1.0.0-rc2.md): mudam o número da versão e a documentação. Em relação à 0.10.1, ela reúne as fases E2 a E5 do [plano de escala](plano-escala-e0-e5.md) e as correções das duas versões candidatas.

**Antes de usar em produção, leia as [limitações de segurança](#limitações-de-segurança-conhecidas) e a lista de [recursos experimentais](#recursos-experimentais).**

## Limitações de segurança conhecidas

> **Não dependa de `PERMISSIONS` para esconder dados na leitura por id, no `FETCH`, na leitura de um registro ligado nem em `LIVE SELECT`.** Nesses caminhos a condição de leitura não é aplicada. A correção é prevista para a 1.0.1.

| Limitação | O que fazer |
|---|---|
| `PERMISSIONS` de leitura não valem no acesso direto por id (`RETURN t:1.campo`), no `FETCH` e na leitura de um registro ligado | Não ofereça consultas AxonQL livres a quem não pode ler a tabela inteira. Faça o controle na aplicação, ou separe os dados em bancos e dê a cada usuário uma identidade com escopo só do banco dele |
| `LIVE SELECT` não aplica as `PERMISSIONS` de leitura aos avisos, e um `$parâmetro` do filtro é lido das variáveis de quem grava | Não exponha a conexão WebSocket a quem não pode ler a tabela. Um usuário com Data Rule não abre `LIVE SELECT` |
| `SHOW SAGA TRANSACTION` mostra o estado anterior dos registros a quem alcança a SAGA, sem aplicar `PERMISSIONS` de leitura, Data Rules ou máscaras | Em bancos com SAGA, dê acesso só a quem pode ver os dados |
| Quem alcança uma SAGA e pode gravar consegue cancelá-la, e o cancelamento repõe o estado anterior mesmo que as `PERMISSIONS` negassem essa escrita | Dê permissão de escrita nesses bancos só a identidades confiáveis |
| Informar `$saga_corr` ou o cabeçalho `X-Correlation-Id` não confere escopo, e uma SAGA aberta sem namespace e ainda sem passos pode ser concluída por qualquer identidade que grave | Não aceite a correlação vinda do cliente final. Coordene SAGAs com identidade de namespace ou root |
| `REEMBED` não exige administrador e regrava as linhas sem consultar as `PERMISSIONS` | Não dê escrita a quem não pode regravar a tabela inteira |
| Um `DATABASE LINK` vale para todo o namespace: um usuário de banco consegue consultá-lo com a credencial gravada na ligação | Use na ligação uma credencial com o mínimo necessário e não a crie em namespace com usuários restritos |
| Numa consulta `banco.tabela` feita por identidade de namespace ou root, as `PERMISSIONS` e as Data Rules aplicadas são as do banco da sessão | Para usuários sujeitos a regras, use identidade com escopo de banco |
| Um usuário com papel `viewer` ainda grava a definição de uma tabela ao consultar uma tabela que não existe | Revise o catálogo periodicamente com `INFO FOR DATABASE` |
| Data Rules não são uma fronteira completa de isolamento: a máscara não alcança campos aninhados, `WHERE` e `ORDER BY` permitem deduzir o valor, e a estimativa do `EXPLAIN` conta linhas ocultas | Leia a [cobertura e os limites das Data Rules](../README.md#data-rules-coverage-and-limits) antes de confiar nelas |
| Na auditoria por IA, quem recebe `AUDITED BY` depois de entrar só é alcançado no login seguinte, e dois usuários auditados com papel de operador ainda podem aprovar os casos `WARNING` um do outro | Atribua a auditoria antes de liberar o acesso |
| Pelo painel Enterprise, a pessoa auditada com papel de operador consegue decidir o próprio caso, porque o motor vê a conta do agente | Não dê o papel de operador no painel a pessoas auditadas |
| A porta do Raft, o protocolo de consenso do cluster, não tem autenticação nem cifra | Exponha-a só numa rede privada |
| Sem TLS (Transport Layer Security) entre o gateway e os shards, o segredo dos shards trafega em claro | Use TLS ou uma rede privada |

## Recursos experimentais

Experimental quer dizer que o recurso passou nos testes funcionais, mas as metas de carga e de falha dele não foram medidas em máquinas separadas. O comportamento e o formato gravado podem mudar numa versão 1.x. Teste com a sua carga e mantenha backup antes de depender dele.

| Recurso | Como se liga | O que falta |
|---|---|---|
| [Vários shards](shards.md): `SHARD BY`, gateway e backup de shards | Só com gateway e `SHARD BY` | A meta de ingestão com três shards nunca foi medida. O gateway atende só `/sql` |
| [Partições por tempo](particoes-e-camada-fria.md) | `DEFINE PARTITION` | Os portões nunca rodaram |
| [TTL](particoes-e-camada-fria.md) (Time To Live), a expiração de registros | `DEFINE TTL` | O efeito sobre a latência de escrita nunca foi medido |
| [Camada fria](particoes-e-camada-fria.md) | `AXON_COLD_DIR` ou `AXON_COLD_S3` | Nunca foi testada contra um MinIO ou outro serviço compatível com S3 (Simple Storage Service) real |
| [Prioridade por tipo de carga](operacao-e-resiliencia.md), ou QoS (Quality of Service) | `AXON_QOS`, desligada por padrão | A meta de latência nunca foi medida em máquinas separadas |

## Antes de atualizar

**Faça um backup.** Voltar para uma versão anterior exige restaurá-lo.

Vindo da 0.10.x ou anterior:

- **Em cluster, atualize somente com os três nós parados.** As versões 0.4.0 a 0.10.1, no modo cluster, podem confirmar escritas que nenhum nó guarda. O caso foi provado na 0.10.1. Parar o cluster não recupera o que já se perdeu. Veja [operação e resiliência](operacao-e-resiliencia.md#atualização-um-nó-de-cada-vez).

Vindo da 1.0.0-rc1 ou anterior:

- **Contêineres:** rode `scripts/migrar-estado-docker.sh <contêiner>` antes de recriá-los. Sem isso o estado do cluster se perde. Veja [cluster Raft](cluster-raft.md#contêiner-com-o-comando-padrão-copiar-antes-de-recriar).
- **Cluster na rc1:** atualize primeiro os seguidores e o líder por último. Enquanto um nó da rc1 liderar, a `PRIMARY KEY` não é conferida.
- **SAGAs:** conclua as abertas antes de atualizar e repita todos os `CREATE SAGA` depois.
- **Usuários e acessos:** rode `INFO FOR ROOT`, apague o que não reconhecer e repita os `REVOKE ACCESS` feitos antes.
- **Condições com parênteses:** declare-as de novo, porque versões anteriores as gravavam sem eles.

O que pode quebrar uma aplicação:

- `CREATE` e `INSERT` recusam um registro que já existe.
- `NOT NULL`, `PRIMARY KEY` e `CHECK` declarados nas tabelas passam a ser aplicados.
- Definir eventos, funções, procedures, pacotes, embeddings e analisadores, e alterar ou apagar tabelas existentes, exige administrador.
- Expressões de esquema e de política só leem o banco da tabela, e um `DATABASE LINK` só leva expressões sem efeito.
- Certificado revogado, ou sem como verificar a revogação, é recusado no login.
- `PERMISSIONS NONE` passa a negar o acesso.

A lista completa está nas mudanças de comportamento da [rc1](notas-1.0.0-rc1.md#mudanças-de-comportamento-em-relação-à-0101) e da [rc2](notas-1.0.0-rc2.md#mudanças-de-comportamento). Quem já está na rc2 não tem mudança de comportamento.

## O que há de novo desde a 0.10.1

- **Raft** com sincronização de disco em grupo, log em segmentos, leitura em seguidor com limite de defasagem e nó que acompanha sem votar.
- **Restauração a um instante**, ou PITR (Point-In-Time Recovery), ligada por `AXON_PITR=on`, com carimbo de tempo híbrido em todo lote. Ligá-la custa vazão de escrita (veja [PITR](pitr.md)).
- **Índice geográfico** com H3, a grade hexagonal hierárquica da Uber.
- **Operação em cluster:** transferência de liderança, drenagem de nó e verificação de versões antes de atualizar.
- **Correções** de segurança e de perda ou corrupção de dados, descritas nas notas das duas candidatas.
- **Experimental:** shards, partições por tempo, TTL, camada fria e prioridade por tipo de carga.

## Desempenho

- **A escrita de registros novos ficou mais lenta que na 0.10.1**, porque `CREATE` passou a conferir se o registro existe. Em um nó, medida três vezes no GCP (Google Cloud Platform), a perda foi de cerca de 12% com 1 thread e de 6% com 8 threads. Tabelas com índice, restrição, evento, permissão, Data Rule, SAGA ou auditoria, transações e clusters podem perder mais, e esses casos não foram medidos.
- **Índice geográfico:** as metas de latência com 10 milhões de pontos foram atendidas em uma rodada, em nó único, sem rede e sem sincronizar o disco a cada escrita. Veja [índice geográfico](indice-geografico.md#portão-de-latência).

## Outras limitações conhecidas

### Consultas e interfaces

- No `JOIN`, um filtro sobre a tabela ligada pode zerar o resultado, o `ORDER BY` é ignorado, `LEFT JOIN` se comporta como junção interna e o apelido devolve nulo.
- Uma consulta com `AT` devolve o valor atual quando não há histórico para o instante pedido.
- O GraphQL ignora os campos pedidos, os argumentos e as demais raízes da consulta, e devolve tudo.
- `FETCH` e o acesso por ponto não seguem uma ligação gravada num campo. Um record id guardado num campo volta como texto.
- `USE DB nome` troca o namespace em vez do banco, e `USE NS a DB b` dá erro de sintaxe. Para escolher os dois, use `USE a b`.
- O método `signin` pelo WebSocket (`/rpc/ws`) devolve o token sem autenticar a conexão. Em seguida, chame `authenticate` com esse token.
- Um erro de `LIVE SELECT` que não seja uma recusa direta de acesso ainda pode derrubar a escrita de quem grava.
- Uma Data Rule cuja subconsulta leia linhas sujeitas à própria regra entra em recursão e a consulta falha.

### SAGA

- `CANCEL SAGA` não desfaz a escrita num registro cuja chave seja um valor tipado (`t:u"..."`, `t:d"..."`), uma lista ou um objeto, nem uma aresta ligada a um registro assim. O comando devolve erro, a SAGA fica em `COMPENSATING` e os passos anteriores não são desfeitos. O estado anterior continua gravado no registro da SAGA.
- `CANCEL SAGA` não desfaz `UNRELATE` nem repõe as arestas que um `DELETE` removeu junto com o registro. Desfazer a criação de um registro remove também as arestas ligadas a ele.
- Um `ROLLBACK TO` não retira os passos de SAGA das escritas que ele desfez.
- `BEGIN SAGA` com um nome sem definição é aceito, sem participantes. Um erro de digitação faz o `CANCEL SAGA` informar sucesso sem desfazer nada nos bancos remotos.
- Uma escrita feita por `DATABASE LINK` continua sujeita às `PERMISSIONS` e às Data Rules do servidor remoto quando é compensada.
- Correlações que só diferem em caracteres que não sejam letras sem acento, números, `_` ou `-` podem dividir o mesmo registro de transação.

### Dados

- Num armazenamento em memória, um índice `UNIQUE` ainda pode aceitar duplicatas em escritas simultâneas fora de transação, e um comando que falha no meio de várias linhas mantém as anteriores.
- Numa tabela particionada, apagar uma partição não remove as chaves de `UNIQUE` das linhas dela, e uma entrada antiga sobre ligação entre registros pode recusar o mesmo valor como texto até um `DROP INDEX`.
- Uma `PRIMARY KEY` com muitas colunas cujos textos tenham a forma `tabela:chave` multiplica as conferências por gravação.
- Uma importação retida pela auditoria por IA e depois aprovada roda como um comando comum: não substitui registros existentes e, num nó único, não é tudo ou nada.

### Pacotes

- O artefato publicado para Spring Data não traz repositórios, template nem anotações.
- Dois testes de integração do SDK (Software Development Kit) Java exigem autenticação e falham sem ela, como já acontecia na 0.10.1.

As limitações de cluster, shards, partições, índice geográfico e recuperação listadas na [rc1](notas-1.0.0-rc1.md#limitações-conhecidas) e não tratadas na rc2 continuam valendo.

## O que não foi verificado

- Os portões de carga e de resiliência em máquinas dedicadas, das fases E2 a E5, não foram executados no código desta versão. Os da E2 passaram antes da integração das demais fases. Os da E3 a E5 nunca foram executados em máquinas separadas.
- A recuperação de desastre nunca foi executada contra um MinIO ou S3 real, e a meta de 10 gigabytes em menos de 10 minutos por esse caminho não foi medida.
- Não há medição consolidada de cobertura de testes.

## Validação

Antes da publicação, o código desta versão passa por: suíte completa do motor, reinício um nó de cada vez, atualização desde a 0.10.1 com parada total, atualização um nó de cada vez desde a rc2, suítes dos SDKs, conferência dos pacotes e o ponta a ponta do AxonBase Enterprise 0.10.0, tudo em máquinas Linux no GCP.
