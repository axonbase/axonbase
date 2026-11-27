# Notas da versão 1.0.0-rc2

A 1.0.0-rc2 é a segunda RC (release candidate), ou versão candidata, da 1.0.0. Ela corrige falhas de segurança e falhas que perdiam ou corrompiam dados, e muda o comportamento de escritas e de acesso. Leia a primeira seção antes de atualizar. O restante da versão está nas [notas da 1.0.0-rc1](notas-1.0.0-rc1.md).

Tire um backup antes de atualizar.

## Mudanças de comportamento

### `CREATE` e `INSERT` recusam um registro que já existe

- `CREATE` e `INSERT` sobre um id existente devolvem erro. Antes sobrescreviam o registro.
- `INSERT IGNORE` pula o id existente e devolve só o que gravou. Ele não ignora violação de `UNIQUE`, de `PRIMARY KEY` ou de outra restrição.
- `UPSERT` continua sobrescrevendo. Um `UPSERT` cujo `WHERE` não casa com o registro existente ainda o regrava, agora sem deixar entradas antigas nos índices.
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
- `$auth` e `$token` passam a vir do token da sessão. Numa sessão autenticada, o cliente não os define por `LET`, por variável da requisição nem pelo método `let`. Quem passava `auth` como variável para simular um usuário precisa mudar.
- A auditoria por IA passa a interceptar pelo servidor os usuários definidos com `AUDITED BY`: eles passam a acionar o provedor de IA e podem ser bloqueados.
- Um comando aprovado roda com a identidade do autor, nunca com o poder de quem aprovou. Autor removido não executa nada, e um caso aberto sem o autor registrado continua pendente.
- Uma Data Rule que não existe no banco atual, ou que precisa de um valor ausente na sessão, nega o acesso. Antes liberava.
- As máscaras passam a valer no retorno das escritas, em `SELECT VALUE`, em apelidos, no acesso por id, no `FETCH` e no `JOIN`.
- Um usuário com Data Rule não usa `LIVE SELECT` nem `/export`.
- Data Rules não são uma fronteira completa de isolamento. A lista do que elas não cobrem está no [README](../README.md#data-rules-coverage-and-limits).

### Conversões

As conversões passam a recusar entrada inválida em vez de gravar o instante atual, zero ou um texto com marcador. Um campo `int` recusa número com fração.

- Converter um valor nulo ou ausente devolve o próprio valor.
- Um texto só vira booleano quando é `true` ou `false`.
- Um texto só com a data vira meia-noite em UTC (Coordinated Universal Time). Data com hora e sem fuso é erro.
- Converter uma data para texto sai sem o marcador `d'...'`.

## Correções de perda ou corrupção de dados

### Catálogo e reinício

- **`DROP INDEX` impedia o servidor de subir.** Uma base afetada volta a abrir. Um índice apagado e recriado na rc1 é mantido quando suas entradas ainda existem. Sem entradas, ele fica apagado. Um índice comum não grava entradas, então precisa ser criado de novo. Um índice `UNIQUE` sem nenhuma linha a indexar é mantido. Confira com `INFO FOR TABLE`.
- **`CANCEL` depois de um `DROP INDEX` derrubava a autenticação.** Uma falha ao reconstruir o catálogo também já não deixa o nó sem usuários.
- **O reinício perdia partes do esquema.** `ALTER TABLE`, `DEFINE TABLE` ou um segundo `CREATE TABLE` substituíam a definição gravada, e `ALTER TABLE ... DROP COLUMN` podia impedir o servidor de subir. A base volta a abrir, mas o que a rc1 já tinha substituído não pode ser recuperado: confira o esquema.
- **Eventos e analisadores apagados voltavam.** Os que foram apagados na rc1 continuam gravados e precisam ser apagados de novo.
- Definições de SAGA, remoção de ligações entre bancos, de usuários e de Data Rules, e a auditoria por IA, deixaram de se perder ou de impedir o reinício.

### Dados

- `RELATE ... SET` grava as propriedades da aresta.
- A exportação passa a levar arestas e suas propriedades, entradas de chave-valor com o prazo restante, embeddings, eventos, restrições de tabela e todas as opções de campo. Campos e eventos vão depois dos dados, para a importação não aplicar `DEFAULT`, `ASSERT` ou `REFERENCES` sobre o que já existia nem disparar eventos de novo. A exportação falha, com erro, diante de uma aresta cuja chave não possa ser escrita (por exemplo, com `|`).
- Usuários, acessos, Data Rules, permissões de tabela, auditorias e ligações entre bancos continuam fora da exportação.

### SAGA e arestas

- A compensação preserva a chave, com o tipo dela, e o `id` do registro, e segue a ordem dos passos também depois de um reinício ou de uma troca de líder.
- `CANCEL SAGA` retoma uma compensação que parou no meio.
- Confirmar ou cancelar uma SAGA sem transação ativa, sem definição ou com o nome de outra devolve erro. Antes informava sucesso.
- O cancelamento pela API de gestão alcança os participantes pelas ligações do namespace de quem abriu a SAGA.
- A leitura `->aresta.campo` alcança a propriedade da aresta quando o registro de destino não tem o campo.

## Desempenho

- **`EXPLAIN`.** Sem `ANALYZE`, as linhas de uma tabela são contadas em fluxo, sem carregar os registros. Dentro de uma transação, essa contagem não conta como leitura. Subconsulta e `DATABASE LINK` ainda materializam o resultado para contar.
- **Índice geográfico.** As metas de latência com 10 milhões de pontos foram atendidas em uma rodada, em nó único, sem rede e sem sincronizar o disco a cada escrita. O p99 (percentil 99) foi de 33,88 ms nos 20 vizinhos mais próximos e de 12,45 ms no raio de 500 m com `LIMIT 100`, para uma meta de 50 ms. As condições estão em [índice geográfico](indice-geografico.md#portão-de-latência).

## Limites conhecidos

- Um índice `UNIQUE` ainda aceita duplicatas em escritas simultâneas feitas fora de transação num nó único.
- Num armazenamento em memória, um comando que falha no meio de várias linhas mantém as linhas anteriores.
- `DEFINE EVENT` não exige administrador, e o corpo do evento roda na sessão de quem dispara a escrita. Um editor pode, assim, fazer o root executar comandos que ele mesmo não poderia.
- A estimativa do `EXPLAIN` conta também as linhas que uma Data Rule oculta.
- Uma importação retida pela auditoria por IA e depois aprovada roda como um comando comum: não substitui registros existentes e, num nó único, não é tudo ou nada.
