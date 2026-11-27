# Notas da versão 1.0.0-rc2

A 1.0.0-rc2 é a segunda RC (release candidate), ou versão candidata, da 1.0.0. Ela corrige falhas de segurança e falhas que perdiam ou corrompiam dados, e muda o comportamento de escritas e de acesso. Leia o aviso sobre contêineres e as mudanças de comportamento antes de atualizar. O restante da versão está nas [notas da 1.0.0-rc1](notas-1.0.0-rc1.md).

Tire um backup antes de atualizar.

## Atenção: contêineres da rc1 ou da 0.10.x

> **Antes de recriar um contêiner vindo da 1.0.0-rc1, da 0.10.x ou de versão anterior, rode `scripts/migrar-estado-docker.sh <contêiner>`.** Com o comando padrão da imagem (`--path /data`), o estado do Raft, o protocolo de consenso do cluster, e os truststores estão na camada do contêiner. Recriá-lo (trocar a imagem, `docker compose up` com outra tag, `down` e `up`) **apaga esse estado, e a versão nova não consegue recuperá-lo**.

- O script para o contêiner e copia o estado para dentro do volume em `/data`. Só depois troque a imagem e recrie, sem voltar a iniciar o contêiner antigo.
- Num cluster vindo da 0.10.x ou anterior, pare os três nós, rode o script em cada um e só então suba os três com a imagem nova.
- Instalações fora de contêiner e as que usam `--path /data/db` com volume em `/data` não precisam desse passo.
- O passo a passo, inclusive como voltar atrás, está em [cluster Raft](cluster-raft.md#contêiner-com-o-comando-padrão-copiar-antes-de-recriar).

## Mudanças de comportamento

### `CREATE` e `INSERT` recusam um registro que já existe

- `CREATE` e `INSERT` sobre um id existente devolvem erro. Antes sobrescreviam o registro.
- `INSERT IGNORE` pula o id existente e devolve só o que gravou. Ele não ignora violação de `UNIQUE`, de `PRIMARY KEY` ou de outra restrição. Antes de pular, confere se o usuário poderia criar a linha: sem essa permissão, responde `permission denied`, exista o id ou não. Com `PERMISSIONS` de criação ou Data Rule, a linha pulada também passa pelas validações de campo.
- `UPSERT` continua alterando o registro que o `WHERE` e as permissões alcançam, e criando o que não existe. Sobre um registro existente ele já não o regrava quando o `WHERE` não casa (devolve lista vazia) nem quando as `PERMISSIONS` de alteração ou uma Data Rule o barram (responde `permission denied`). Antes o registro era substituído nos dois casos.
- Para um usuário com Data Rule, `CREATE`, `INSERT`, `INSERT IGNORE` e `UPSERT` sobre um registro que a regra esconde respondem só `permission denied`. A resposta não diz que o registro existe, e `INSERT IGNORE` não o pula em silêncio.
- Vale para os métodos `create` e `insert` dos SDKs (Software Development Kits), para a rota de tabela, para o driver JDBC (Java Database Connectivity) e para eventos. Um evento que cria sempre o mesmo id passa a fazer falhar a escrita que o disparou.
- Importação (`POST /import`), movimentação de shards e compensação de saga continuam repondo registros existentes.
- Scripts que podiam ser repetidos com `CREATE` precisam usar `UPSERT`. Os exemplos do repositório foram ajustados.
- Em `INSERT` por vários shards, a recusa vale por shard: os outros podem já ter gravado.

### `NOT NULL`, `PRIMARY KEY` e `CHECK` de tabela passam a valer

- As restrições valem nas escritas novas. O que já estava gravado não é conferido.
- Um `UPDATE` confere o registro inteiro que vai gravar. Uma linha antiga que viola `NOT NULL` ou `CHECK` precisa ser corrigida na mesma escrita.
- `PRIMARY KEY` exige valor presente e não repetido. Linhas antigas repetidas continuam regraváveis enquanto a chave delas não muda.
- Quando a chave primária inclui a coluna `id`, a unicidade é a do próprio registro, e as demais colunas da chave só não aceitam nulo.
- O `CHECK` de tabela segue o de coluna: não é avaliado quando um campo citado está ausente.
- `ALTER TABLE ... MODIFY COLUMN` mantém o `NOT NULL` e a chave primária que a coluna já tinha. `DEFINE FIELD` sobre uma coluna a redefine por inteiro, sem essas restrições.
- Em tabela dividida por shards, a chave primária precisa conter a chave de shard.
- Ao abrir uma base da rc1, o motor percorre uma vez cada tabela com chave primária para registrar os valores existentes.
- A rc1 não guardava o `CHECK` de tabela. Declare-o de novo com `CREATE TABLE`.
- A tradução de SQL (Structured Query Language) do driver JDBC (`sql.translate=true`) continua descartando as restrições do `CREATE TABLE`.

### `SET`, `PATCH` e `MERGE`

- `SET endereco.cidade = 'RJ'` altera o campo dentro de `endereco`. Antes gravava `cidade` na raiz. Objetos que faltam são criados, posições de lista podem ser alteradas ou acrescentadas, e um caminho incompatível com o valor atual é recusado.
- O apelido ou o nome da tabela antes do campo (`SET t.nome = ...`) continuam indicando a coluna.
- `PATCH` com lista aplica as operações do JSON (JavaScript Object Notation) Patch (`add`, `remove`, `replace`, `move`, `copy` e `test`). Antes não fazia nada. Uma operação inválida recusa a escrita, inclusive `replace` e `remove` sobre um caminho que não existe. `PATCH` com objeto continua incorporando os campos.
- Para um usuário com campo mascarado, `test`, `copy` e `move` são recusados sobre esse campo e sobre o registro inteiro, e nenhuma operação entra nele.
- `MERGE` junta objetos internos em qualquer profundidade. Antes substituía o objeto inteiro. Listas continuam sendo substituídas, e `MERGE {campo: {}}` já não esvazia o objeto.
- Os métodos `update` e `upsert` dos SDKs usam `MERGE` e passam a juntar objetos internos. Para substituir um objeto, use `SET` ou `CONTENT`.

### Importação tudo ou nada

- A importação roda numa transação. Se uma sentença falha, dados, definições, arestas e chave-valor voltam a ficar como antes.
- `POST /import` passa a exigir administrador do banco. Cada comando roda com a identidade, o teto de papel (cabeçalho `Axon-Role-Cap`) e as Data Rules da requisição. Um comando acima do que o chamador pode faz a importação inteira ser recusada.
- Um dump com definição de SAGA exige administrador root. A exportação inclui as SAGAs cujas ligações existem no namespace, então um administrador de banco precisa retirar essas linhas ou pedir a importação ao root.
- O arquivo e a transação têm limite de 256 MB (megabytes), o teto de uma entrada do log Raft. Acima disso a importação é recusada com erro. Num nó fora de cluster, `AXON_IMPORT_MAX_MB` muda o limite. Em cluster, a variável só pode reduzi-lo.
- Um dump com `BEGIN`, `COMMIT` ou `CANCEL` é recusado.
- Escritas concorrentes nas mesmas chaves podem fazer a importação falhar por conflito.
- `axon import`, da CLI (Command Line Interface), envia o arquivo como consulta comum e não usa esse caminho: não é atômico e recusa registros existentes. Use `POST /import`.

### `DELETE`

- `DELETE FROM t [WHERE ...]` apaga como `DELETE t`. Antes criava a tabela `from`.
- `DELETE TABLE t` é recusado, com a indicação de `DROP TABLE t` ou `DELETE t`.
- Nenhuma forma de `DELETE` cria tabela. Uma tabela `from` deixada pela rc1 pode ser removida com `DROP TABLE from`.

### `id` do registro

- `UPDATE` e `UPSERT` preservam o `id`. Um conteúdo com id diferente é recusado.
- Um registro que perdeu o campo `id` na rc1 o recebe de volta na próxima atualização, e a exportação o inclui.

### Índices, analisadores e `UNIQUE`

- `DROP INDEX nome ON TABLE tabela` (ou `ON tabela`) apaga o índice só daquela tabela e devolve erro quando ela não o tem. Antes a tabela era ignorada e o índice de mesmo nome de outra tabela podia ser apagado. Sem a cláusula `ON`, continua valendo o primeiro índice encontrado com o nome.
- `DROP ANALYZER` é recusado enquanto um índice usa o analisador. Apague o índice antes.
- Escritas simultâneas do mesmo valor `UNIQUE`, fora de transação, passam a aceitar uma só. As demais recebem a violação de índice único. Duplicatas que a rc1 já aceitou continuam gravadas e precisam ser corrigidas à mão.
- Em cluster, `UNIQUE` e `PRIMARY KEY` também aceitavam duplicatas em escritas simultâneas. A escrita agora fica presa ao estado do valor no momento da conferência.
- Cada valor `UNIQUE` gravado ou trocado passa a custar uma leitura de versão e uma chave a mais no armazenamento. Trocar ou apagar o valor remove a chave antiga. Uma atualização que não muda o valor só paga a leitura. A construção de um índice sobre dados existentes grava a mesma chave por valor. Índices criados antes desta versão não precisam ser reconstruídos.

### Segurança do servidor

- O armazenamento de chave e valor, ou KV (Key-Value), passa a conferir o escopo do token contra o namespace e o banco informados na chamada.
- Essa conferência vale para as chamadas por RPC (Remote Procedure Call), o protocolo de chamadas do servidor.
- O método `signup` exige um administrador cujo escopo cubra o destino, é recusado em sessão somente leitura e não altera um usuário existente.
- A entrada e a saída de membros do cluster exigem administrador root.
- A API (Application Programming Interface) de gestão aplica o teto de papel pedido em `Axon-Role-Cap`. Registrar um truststore passa a exigir papel de administrador.
- No login por certificado, o usuário vem do próprio certificado, e um usuário informado diferente dele é recusado. Quando o certificado não identifica ninguém, só entra o usuário definido com o `FINGERPRINT` desse certificado.
- A credencial temporária vale por 60 segundos e fica presa ao usuário e ao escopo do primeiro uso.
- Uma senha de root configurada em branco impede a subida. Antes virava a senha padrão.
- O limite de requisições só lê `X-Forwarded-For` dos proxies listados em `AXON_TRUSTED_PROXIES`. Atrás de um proxy fora da lista, todos os clientes dividem o limite do endereço dele.
- O certificado do cliente continua opcional. `AXON_TLS_CLIENT_AUTH=require` o exige, e só é aceito com certificado, chave e autoridade do TLS (Transport Layer Security) configurados e a porta sem TLS desligada.

### Revogação de certificado

Atenção na atualização: certificado revogado passa a ser sempre recusado no login por certificado, tanto por HTTP (HyperText Transfer Protocol) quanto por RPC. Quando não é possível verificar a revogação (serviço fora do ar, sem rede, certificado sem endereço de consulta), o login também é recusado; `AXON_CERT_ALLOW_UNKNOWN_REVOCATION=true` ou `cert_allow_unknown_revocation = true` volta a aceitar só esse caso, com aviso no log. Cada consulta espera até 5 segundos. Detalhes em [operação e resiliência](operacao-e-resiliencia.md#revogação-de-certificado-no-login).

### Segurança do motor

- Remover um usuário ou um acesso exige escopo que cubra o alvo. Definir um acesso segue a mesma regra.
- Criar ou remover uma ligação entre bancos exige escopo de namespace ou maior. Criar ou remover uma definição de SAGA exige escopo root.
- O root inicial do servidor não pode ser apagado. Uma sessão autenticada recebe erro, e o modo sem autenticação ignora o pedido.
- `DEFINE EVENT`, `CREATE EVENT`, `ALTER EVENT` e `DROP EVENT` passam a exigir administrador, também numa importação. Tokens sem papel reconhecido, como os de escopo de banco sem `ROLES`, não definem mais eventos. Reinício e replicação não mudam.
- Eventos que um editor criou antes continuam gravados e continuam disparando, com a identidade de quem faz a escrita, agora [confinados ao banco da tabela](#corpo-de-evento-confinado-ao-banco-da-tabela). Revise cada tabela com `INFO FOR TABLE` antes de atualizar e remova o que não reconhecer.
- Um usuário auditado, mesmo administrador, não aprova nem nega o próprio caso, não libera um caso `DANGER` de outro usuário (o que o desbloquearia) e só justifica os próprios casos. Também não executa `CREATE AI AUDIT`, `DROP AI AUDIT`, `CREATE USER`, `DEFINE USER` ou `DROP USER`, não usa `signup`, não desbloqueia usuários, não remove usuários nem emite tokens de serviço pela gestão. Ninguém decide um caso aberto em seu próprio nome.
- Uma AI AUDIT atribuída ao usuário e inexistente no banco corrente passa a negar o comando. Antes liberava. Só o `USE` passa, para sair do banco. Antes de apagar uma auditoria, retire a atribuição dos usuários.
- Uma condição de `PERMISSIONS` que cita um dado ausente em `$auth` ou `$token` passa a negar a condição inteira, inclusive os outros ramos de um `OR`. Antes, a falta casava com um campo ausente. Vale também para sessões sem token (modo embutido e servidor sem autenticação) e para testes como `$auth = NONE`. Condições que não citam `$auth` nem `$token` não mudam.
- Um usuário com Data Rule não lê `AUDIT_LOG` nem `AUDIT_CASES`.
- `$auth` e `$token` passam a vir do token da sessão. Numa sessão autenticada, o cliente não os define por `LET`, por variável da requisição nem pelo método `let`. Quem passava `auth` como variável para simular um usuário precisa mudar.
- A auditoria por IA passa a interceptar pelo servidor os usuários definidos com `AUDITED BY`: eles passam a acionar o provedor de IA e podem ser bloqueados.
- Um comando aprovado roda com a identidade do autor, nunca com o poder de quem aprovou. Autor removido não executa nada, e um caso aberto sem o autor registrado continua pendente.
- Uma Data Rule que não existe no banco atual, ou que precisa de um valor ausente na sessão, nega o acesso. Antes liberava.
- As máscaras passam a valer no retorno das escritas, em `SELECT VALUE`, em apelidos, no acesso por id, no `FETCH` e no `JOIN`.
- Um usuário com Data Rule não usa `LIVE SELECT` nem `/export`.
- Data Rules não são uma fronteira completa de isolamento. A lista do que elas não cobrem está no [README](../README.md#data-rules-coverage-and-limits).

### Corpo de evento confinado ao banco da tabela

- A condição e o corpo de um evento continuam rodando com a identidade de quem grava, mas só leem e gravam dados do banco da tabela. Vale para qualquer escritor (root, administrador, editor, modo sem autenticação, expiração por TTL (Time To Live), importação e compensação de saga) e para os programas chamados pelo evento, inclusive `SECURITY DEFINER`.
- Continuam aceitos: `SELECT`, `CREATE`, `INSERT`, `UPDATE`, `UPSERT`, `DELETE`, `RELATE`, `UNRELATE`, `LET`, `RETURN`, `IF`, `ERROR`, `EXPLAIN`, `DESCRIBE`, `SHOW DATA RULES`, `CALL`, pontos de salvamento e `INFO` do banco ou de seus objetos.
- São recusados: definições e remoções (`DEFINE`, `CREATE TABLE`, `ALTER`, `DROP`), usuários, acessos, auditoria, comandos de SAGA, `USE`, `LIVE`, `KILL`, `BEGIN`, `COMMIT`, `CANCEL`, outro banco por `banco.tabela`, `DATABASE LINK` e `INFO` de root ou de namespace.
- Um evento antigo, mesmo definido pelo root, que faça algo recusado continua gravado e passa a fazer falhar a escrita que o dispara, com a mensagem `an event body only reads and writes data of the table's database` (no idioma configurado). Em disco ou em cluster, fora de transação explícita, nada dessa escrita é gravado. Numa expiração por TTL, as linhas deixam de expirar até o evento ser corrigido. O que o evento fazia fora do banco precisa passar para a aplicação.
- As `PERMISSIONS` e as Data Rules de quem grava continuam valendo. O que elas podem ler está na seção [Expressões de esquema e de política só leem](#expressões-de-esquema-e-de-política-só-leem).

### Definição de tabela exige administrador do banco

Numa sessão autenticada, as ações abaixo exigem papel de administrador com escopo que cubra o banco. O modo sem autenticação não muda.

- `DROP TABLE` e `REMOVE TABLE`.
- Declarar `PERMISSIONS`, mesmo numa tabela nova.
- Sobre uma tabela existente: `DEFINE TABLE` que mude o tipo de esquema, a opção `DROP`, o `SHARD BY` ou as `PERMISSIONS`; `CREATE TABLE` sem `IF NOT EXISTS`; `ALTER TABLE`; `DROP INDEX`; definir ou remover partição e TTL, e mover partição.
- `DEFINE FIELD`, `DEFINE INDEX` e `CREATE INDEX` que troquem uma definição por outra diferente, e qualquer campo ou índice novo numa tabela com `PERMISSIONS`.

Continuam como antes: criar tabela sem `PERMISSIONS` (também ao gravar um registro), acrescentar campo ou índice novo a uma tabela sem `PERMISSIONS`, `CREATE TABLE IF NOT EXISTS` e repetir sem mudança um `DEFINE TABLE`, `DEFINE FIELD` ou `DEFINE INDEX`, como fazem os migradores dos SDKs. Repetir `DEFINE PARTITION` ou `DEFINE TTL` sobre uma tabela existente exige administrador mesmo sem mudança.

- Perdem essas ações o papel `operator` (inclusive o teto `Axon-Role-Cap: operator`) e os tokens sem papel reconhecido, como os de escopo de banco sem `ROLES`.
- No driver JDBC com tradução de SQL, `DROP TABLE` exige administrador, e `CREATE TABLE` sobre uma tabela que já existe com outra definição é recusado.
- A importação embutida por quem não é administrador completa as tabelas que ela mesma cria. Um dump com `PERMISSIONS`, ou que mude uma tabela já existente, é recusado por inteiro. `POST /import` já exigia administrador.

### Expressões de esquema e de política só leem

Antes, uma subconsulta podia ter várias sentenças, por exemplo `(SELECT ...; CREATE USER ... ON ROOT ...)`, e rodava na sessão de quem gravava ou consultava. Quem escrevia um `DEFAULT` podia fazer o root criar usuários.

Agora, as expressões abaixo só leem, e só o banco da tabela:

- `DEFAULT` e `ASSERT` de campo, `CHECK` de coluna e de tabela;
- `PERMISSIONS`, o filtro e o valor injetado de uma Data Rule;
- a condição `WHEN` de um evento;
- o valor padrão de um parâmetro de função ou procedure;
- o filtro e as colunas de um `LIVE SELECT`.

A regra alcança subconsultas, blocos e os programas chamados pela expressão, inclusive `SECURITY DEFINER`:

- Passam `SELECT`, `RETURN`, `IF`, `ERROR`, `DESCRIBE` e `INFO` do banco ou de seus objetos.
- São recusados escritas, definições, usuários, acessos, comandos de SAGA, `USE`, transações, `LET`, `CALL`, `LIVE`, `KILL`, `kv::set`, `kv::del`, outro banco por `banco.tabela`, `DATABASE LINK`, `AUDIT_LOG` e `AUDIT_CASES`.
- Uma leitura feita ali não cria a tabela que ainda não existe.

O que muda ao atualizar:

- Definições já gravadas continuam abrindo no reinício. A recusa aparece quando a expressão é avaliada, com a mensagem `a schema, policy or LIVE expression only reads data of the table's database` (no idioma configurado), e a escrita ou a consulta falha.
- Um `DEFAULT` que gerava sequência com `kv::set`, ou que chamava uma procedure, precisa passar para a aplicação.
- Uma `PERMISSIONS` ou Data Rule que consultava outro banco (por exemplo, uma tabela central de acessos) precisa ler uma cópia no próprio banco.
- Um programa cujo padrão de parâmetro seja recusado abre marcado como quebrado: a chamada devolve o erro, e a exportação não o inclui. Recrie-o.
- No `LIVE SELECT`, o filtro e as colunas passam a ser calculados com a identidade de quem assinou. Se pedirem algo recusado, o assinante fica sem o aviso, o log registra `live_query_refused` e a escrita vale.

### Funções só leem, também em subconsultas

A AxonPL já recusava escrita em função, mas conferia só a primeira camada do texto passado a `db.query`. Uma subconsulta escrevia com o poder de quem chamava a função. Agora a recusa vale na execução de cada sentença, com a mensagem `a function only reads data`. Procedures chamadas por `CALL` não mudam.

Uma subconsulta de várias sentenças na consulta do próprio usuário continua aceita: cada sentença passa pelas mesmas conferências de papel e escopo.

### Outro banco exige escopo que o alcance

- `banco.tabela` apontando para outro banco do namespace exige identidade de escopo de namespace ou root. Um usuário de banco recebe `the token scope does not reach the database`, em `SELECT`, `JOIN`, subconsulta, contagem, `EXPLAIN` e nas escritas que leem outro banco.
- O banco citado deixa de ser criado por uma leitura recusada.
- Citar o próprio banco, o modo sem autenticação e as ligações entre bancos (`DATABASE LINK`) não mudam.

### Embedding e analisador exigem administrador do banco

- `DEFINE EMBEDDING`, `DROP EMBEDDING`, `DEFINE ANALYZER`, `CREATE ANALYZER` e `DROP ANALYZER` exigem administrador com escopo que cubra o banco. A URL (Uniform Resource Locator) de um embedding decide para onde o texto é enviado.
- Repetir sem mudança a definição em vigor continua aberto a quem grava.
- Perdem essas ações o papel `operator` (inclusive o teto `Axon-Role-Cap: operator`, usado pelo painel Enterprise) e os tokens sem papel reconhecido.
- A importação embutida por quem não é administrador é recusada quando o dump traz um embedding ou analisador que o banco ainda não tem. `POST /import` já exigia administrador. Reinício e replicação não mudam.

### Funções, procedures e pacotes exigem administrador do banco

Antes, um editor podia trocar uma procedure que o root chamava com `CALL`, e o corpo rodava com o poder do root. Também podia trocar uma função citada numa `PERMISSIONS` e abrir a tabela.

- `CREATE FUNCTION`, `CREATE PROCEDURE` e `CREATE PACKAGE`, com ou sem `OR REPLACE`, e `DROP FUNCTION`, `DROP PROCEDURE` e `DROP PACKAGE` exigem administrador com escopo que cubra o banco, inclusive numa importação e dentro de um programa.
- Repetir com `CREATE OR REPLACE` a definição em vigor, sem mudança, continua aberto a quem grava e não altera versão, datas nem dono. Um programa `SECURITY DEFINER` já exigia papel de administrador.
- Perdem essas ações o papel `operator` (inclusive o teto `Axon-Role-Cap: operator`, usado pelo painel Enterprise) e os tokens sem papel reconhecido. No painel, o operador passa a receber recusa ao salvar ou apagar um programa.
- Chamar (`CALL` e chamada em expressão) e depurar não mudam.
- A importação embutida por quem não é administrador é recusada quando o dump traz um programa que o banco não tem ou que difere do atual. `POST /import` já exigia administrador.
- Reinício, replicação, restauração de backup, movimentação de shards, modo embutido e servidor sem autenticação não mudam.

Programas que editores criaram ou trocaram antes continuam gravados e continuam rodando, com a identidade de quem chama. Revise cada banco antes de liberar o uso:

1. `INFO FOR DATABASE` lista funções, procedures e pacotes, com a versão de cada um.
2. `INFO FOR FUNCTION nome`, `INFO FOR PROCEDURE nome` e `INFO FOR PACKAGE nome` mostram o código (`source`), a versão, as datas e o modo (`security`). O resultado não informa quem escreveu: compare o código com o que está no controle de versão da aplicação.
3. `INFO FOR TABLE tabela` mostra as `PERMISSIONS` e os eventos que citam esses programas.
4. Como administrador, substitua com `CREATE OR REPLACE` ou apague com `DROP` o que não reconhecer.

### `CREATE TABLE IF NOT EXISTS`

A forma era recusada pelo analisador e passa a ser aceita. `exists` não virou palavra reservada.

### `PERMISSIONS NONE` passa a negar

- `DEFINE TABLE t ... PERMISSIONS NONE` equivale a `WHERE false` em leitura, criação, alteração e remoção. Antes o comando era aceito e não restringia nada.
- Como qualquer `WHERE false`, vale para todas as sessões, inclusive o root e o modo embutido. Para reabrir a tabela, um administrador a redefine com `PERMISSIONS FULL`.
- Valem os mesmos limites das demais `PERMISSIONS`, descritos nas [limitações conhecidas](#limitações-conhecidas).
- A rc1 e as anteriores gravavam essas tabelas sem restrição alguma. Atualizar não as fecha: repita o `DEFINE TABLE` com `PERMISSIONS NONE`.
- Um script que repete `PERMISSIONS NONE` passa a exigir administrador, como toda declaração de `PERMISSIONS`. Em tabela dividida por shards ele é recusado.

### Agregação com `math::`

- Numa consulta com `GROUP ALL`, `GROUP BY` ou outro agregado, `math::sum`, `math::min`, `math::max`, `math::mean`, `math::median` e `math::product` com um argumento calculam sobre o grupo inteiro. Antes devolviam o valor do primeiro registro. Um campo lista contribui com todos os itens, e só números contam.
- Sem agrupamento, continuam calculando a lista de cada linha. A expressão usada no próprio `GROUP BY` também mantém o valor da linha.
- `math::median` e `math::product` guardam em memória todos os valores do grupo.

### Busca textual combinada

- `campo @@ 'texto' AND outra_condição`, sobre um campo com índice `SEARCH`, passa a usar o índice, o analisador e a pontuação de `search::score()`. Antes virava comparação de trecho de texto, com pontuação zero, e podia devolver outras linhas.
- Com várias buscas ligadas por `AND`, a pontuação é a soma delas. Junto de `OR`, de `NOT` ou de `JOIN`, o comportamento não mudou.

### Consulta ao vivo sobre um registro

- `LIVE SELECT * FROM t:chave` passa a notificar só as mudanças desse registro. Antes notificava as da tabela inteira. Quem dependia disso precisa assinar a tabela (`FROM t`).

### `CREATE ... RETURN NONE`

- `CREATE` com `RETURN NONE` grava e devolve nulo. Antes devolvia o registro criado.

### Conversões

As conversões por função (`type::`) e as formas `<int>`, `<decimal>` e `<bool>` passam a recusar entrada inválida em vez de gravar o instante atual, zero ou um texto com marcador. Um campo `int` recusa número com fração.

- As formas `<datetime>` e `<uuid>` **não** validam: devolvem o texto recebido como está. Para converter e validar uma data, use `type::datetime`.
- As formas `<float>` e `<number>` dão erro com um texto, mesmo numérico.

- Converter um valor nulo ou ausente devolve o próprio valor.
- Um texto só vira booleano quando é `true` ou `false`.
- Um texto só com a data vira meia-noite em UTC (Coordinated Universal Time). Data com hora e sem fuso é erro.
- Converter uma data para texto sai sem o marcador `d'...'`.

## Conectores e ferramentas

**Segurança:** parâmetros de texto no driver JDBC e no conector Python (DBAPI, a interface padrão de banco de dados do Python) não conseguem mais virar comando. Quem usa a rc1 com texto vindo de usuários deve atualizar.

- Driver JDBC: `executeBatch` executa o lote e lança `BatchUpdateException` na primeira falha. Antes nada era enviado.
- Com auto-commit desligado, cada comando depois de `commit` ou `rollback` abre uma nova transação. Antes as escritas seguintes eram confirmadas sozinhas.
- `DELETE` informa a quantidade apagada. Exclusões muito grandes devem ser feitas em partes.
- Passam a dar erro: chaves geradas, `cancel`, troca de banco ou de namespace, parâmetros binários ou em fluxo e leitura numérica de um texto não numérico.
- O SDK Java aceita respostas de até 256 MiB.
- `axon export` gera a cópia pela rota `/export` e sai com código diferente de zero quando falha. Um usuário com Data Rules recebe recusa.
- Na CLI, `--user` e `--password` autenticam a sessão, `--vars` é aplicado e um erro de uso sai com código 2.
- Hibernate com `sql.translate=true`: busca, alteração e remoção pelo identificador funcionam em entidade de tabela única. Relacionamentos e paginação seguem sem suporte.
- O servidor MCP (Model Context Protocol) atende `initialize` e responde no formato JSON-RPC. A autenticação continua exigida, e mensagens sem o campo `jsonrpc` mantêm o formato anterior.
- O script da demonstração de SAGA com SQLAlchemy monta o caminho de classes completo.

## Correções de perda ou corrupção de dados

### Catálogo e reinício

- **`DROP INDEX` impedia o servidor de subir.** Uma base afetada volta a abrir. Um índice apagado e recriado na rc1 é mantido quando suas entradas ainda existem. Sem entradas, ele fica apagado. Um índice comum não grava entradas, então precisa ser criado de novo. Um índice `UNIQUE` sem nenhuma linha a indexar é mantido. Confira com `INFO FOR TABLE`.
- **`CANCEL` depois de um `DROP INDEX` derrubava a autenticação.** Uma falha ao reconstruir o catálogo também já não deixa o nó sem usuários.
- **O reinício perdia partes do esquema.** `ALTER TABLE`, `DEFINE TABLE` ou um segundo `CREATE TABLE` substituíam a definição gravada, e `ALTER TABLE ... DROP COLUMN` podia impedir o servidor de subir. A base volta a abrir, mas o que a rc1 já tinha substituído não pode ser recuperado: confira o esquema.
- **Eventos e analisadores apagados voltavam.** Os que foram apagados na rc1 continuam gravados e precisam ser apagados de novo. Um índice cujo analisador já tinha sido apagado continua abrindo.
- **`REVOKE ACCESS` não era gravado.** O acesso voltava no reinício, nas réplicas e na restauração de backup. Repita todo `REVOKE ACCESS` feito em versão anterior, inclusive os feitos antes desta atualização: ela reinicia o servidor e traz o acesso de volta.
- **`CREATE TABLE` sobre uma tabela existente apagava as permissões** e a opção `DROP` dela. As duas passam a ser mantidas. Permissões já perdidas precisam ser declaradas de novo com `DEFINE TABLE`.
- **Uma tabela definida com a opção `DROP` impedia a base de reabrir** e o dump dela de ser importado. As duas formas, antes ou depois de `SCHEMAFULL` ou `SCHEMALESS`, são aceitas.
- Definições de SAGA, remoção de ligações entre bancos, de usuários e de Data Rules, e a auditoria por IA, deixaram de se perder ou de impedir o reinício.

### Dados

- `RELATE ... SET` grava as propriedades da aresta.
- A exportação passa a levar arestas e suas propriedades, entradas de chave-valor com o prazo restante, embeddings, eventos, restrições de tabela e todas as opções de campo. Campos e eventos vão depois dos dados, para a importação não aplicar `DEFAULT`, `ASSERT` ou `REFERENCES` sobre o que já existia nem disparar eventos de novo. A exportação falha, com erro, diante de uma aresta cuja chave não possa ser escrita (por exemplo, com `|`).
- A exportação passa a levar as `PERMISSIONS` das tabelas e as Data Rules, depois dos dados, para o banco importado não ficar mais aberto que o de origem. Importar uma Data Rule continua exigindo administrador.
- Usuários, senhas, tokens, acessos, auditorias e ligações entre bancos, com as credenciais delas, continuam fora da exportação. Atribua de novo as Data Rules aos usuários do banco de destino.
- Reimportar sobre um banco cujas `PERMISSIONS` negam a identidade de quem importa é recusado.

### SAGA e arestas

- `LEAVE SAGA` passa a ser aceito e desvincula a sessão sem encerrar a transação. Antes dava erro de sintaxe, a sessão continuava na SAGA e as escritas seguintes podiam ser desfeitas por um `CANCEL SAGA`. A palavra `leave` continua livre para nomes de tabela e campo.
- A correlação aceita hífen, como num UUID (Universally Unique Identifier), nos comandos de SAGA e no cancelamento pela API de gestão. Antes era recusada.
- `INSERT`, `UPSERT` que cria um registro e `RELATE` passam a registrar um passo, e `CANCEL SAGA` os desfaz. Antes o cancelamento informava sucesso e deixava os dados. Um `RELATE` sobre uma aresta existente volta às propriedades anteriores. Escritas já feitas sem passo por uma versão anterior não são desfeitas. Os passos que ela gravou continuam valendo.
- O passo de uma escrita que não chegou a ser gravada (comando em lote recusado ou transação cancelada ou em conflito) sai do registro da SAGA.
- Dentro de uma SAGA, `RELATE` por `DATABASE LINK` é recusado, porque não haveria como desfazer a aresta remota.
- Um `UPSERT` por `DATABASE LINK` que cria o registro é desfeito apagando esse registro.
- A compensação preserva a chave, com o tipo dela, e o `id` do registro, e segue a ordem dos passos também depois de um reinício ou de uma troca de líder.
- `CANCEL SAGA` retoma uma compensação que parou no meio.
- Confirmar ou cancelar uma SAGA sem transação ativa, sem definição ou com o nome de outra devolve erro. Antes informava sucesso.
- O cancelamento pela API de gestão alcança os participantes pelas ligações do namespace de quem abriu a SAGA.
- A leitura `->aresta.campo` alcança a propriedade da aresta quando o registro de destino não tem o campo.

## Publicação

- O script `scripts/publish-docker.sh` deixa de mover a tag `latest` quando a versão tem sufixo, como `1.0.0-rc2`. O fluxo de publicação do repositório já fazia assim.

## Desempenho

- **`EXPLAIN`.** Sem `ANALYZE`, as linhas de uma tabela são contadas em fluxo, sem carregar os registros. Dentro de uma transação, essa contagem não conta como leitura. Subconsulta e `DATABASE LINK` ainda materializam o resultado para contar.
- **Índice geográfico.** As metas de latência com 10 milhões de pontos foram atendidas em uma rodada, em nó único, sem rede e sem sincronizar o disco a cada escrita. O p99 (percentil 99) foi de 33,88 ms nos 20 vizinhos mais próximos e de 12,45 ms no raio de 500 m com `LIMIT 100`, para uma meta de 50 ms. As condições estão em [índice geográfico](indice-geografico.md#portão-de-latência).

## Limitações conhecidas

- Num armazenamento em memória, um índice `UNIQUE` ainda pode aceitar duplicatas em escritas simultâneas feitas fora de transação.
- Numa tabela particionada, apagar uma partição não remove as chaves por valor `UNIQUE` das linhas dela. Elas não recusam escritas, mas ocupam espaço até um `DROP INDEX` ou `DROP TABLE`.
- `CANCEL SAGA` ainda não desfaz `UNRELATE` nem repõe as arestas que um `DELETE` removeu junto com o registro. Desfazer a criação de um registro remove também as arestas ligadas a ele.
- Um `ROLLBACK TO` de ponto de salvamento não retira os passos de SAGA das escritas que ele desfez.
- Num armazenamento em memória, um comando que falha no meio de várias linhas mantém as linhas anteriores.
- `PERMISSIONS` de leitura não valem no acesso direto por id (`RETURN t:1.campo`), no `FETCH` e na leitura de um registro ligado.
- `FETCH` e o acesso por ponto não seguem uma ligação gravada num campo.
- No `JOIN`, um filtro sobre a tabela ligada pode zerar o resultado, o `ORDER BY` é ignorado, `LEFT JOIN` se comporta como junção interna e o apelido devolve nulo.
- Uma consulta com `AT` devolve o valor atual quando não há histórico para o instante pedido.
- `BEGIN SAGA` com um nome sem definição ainda é aceito, sem participantes. Um erro de digitação no nome faz o `CANCEL SAGA` informar sucesso sem desfazer nada nos bancos remotos.
- O GraphQL ignora os campos pedidos, os argumentos e as demais raízes da consulta, e devolve tudo.
- O método `signin` pelo WebSocket (`/rpc/ws`) devolve o token sem autenticar a conexão. Em seguida, chame `authenticate` com esse token.
- O artefato publicado para Spring Data não traz repositórios, template nem anotações.
- Um record id guardado num campo volta como texto. Uma Data Rule como `dono.equipe = 'a'` não segue esse link e nega a linha. A travessia de grafo (`->pertence->u.equipe`) é seguida.
- A auditoria de um usuário vem do token. Quem recebeu `AUDITED BY` depois de entrar só é alcançado no próximo login. Dois usuários auditados com papel de operador ainda podem aprovar os casos `WARNING` um do outro.
- Numa consulta `banco.tabela` feita por identidade de namespace ou root, as `PERMISSIONS` e as Data Rules aplicadas são as do banco da sessão, não as do banco citado.
- Um `LIVE SELECT` não aplica as `PERMISSIONS` de leitura aos avisos, e um `$parâmetro` do filtro é lido das variáveis de quem grava.
- Um `DATABASE LINK` vale para todo o namespace: um usuário de banco consegue consultá-lo, com a credencial gravada na ligação.
- Um erro de `LIVE SELECT` que não seja uma recusa direta de acesso (por exemplo, uma falha ao avaliar o filtro ou uma coluna) ainda pode derrubar a escrita de quem grava.
- `REEMBED` não exige administrador e regrava as linhas sem consultar as `PERMISSIONS`.
- Uma Data Rule cuja subconsulta leia linhas sujeitas à própria regra entra em recursão e a consulta falha.
- Pelo painel Enterprise, a pessoa auditada com papel de operador consegue decidir o próprio caso, porque o motor vê a conta do agente.
- A estimativa do `EXPLAIN` conta também as linhas que uma Data Rule oculta.
- Uma importação retida pela auditoria por IA e depois aprovada roda como um comando comum: não substitui registros existentes e, num nó único, não é tudo ou nada.
