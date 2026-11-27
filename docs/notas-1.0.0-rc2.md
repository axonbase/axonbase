# Notas da versão 1.0.0-rc2

A 1.0.0-rc2 é a segunda RC (release candidate), ou versão candidata, da 1.0.0. Ela corrige falhas que perdiam ou corrompiam dados e muda o comportamento de escritas. Leia a primeira seção antes de atualizar. O restante da versão está nas [notas da 1.0.0-rc1](notas-1.0.0-rc1.md).

Tire um backup antes de atualizar.

## Mudanças de comportamento

### `CREATE` e `INSERT` recusam um registro que já existe

- `CREATE` e `INSERT` sobre um id existente devolvem erro. Antes sobrescreviam o registro.
- `INSERT IGNORE` pula o id existente e devolve só o que gravou. Ele não ignora violação de `UNIQUE`, de `PRIMARY KEY` ou de outra restrição.
- `UPSERT` continua sobrescrevendo. Um `UPSERT` cujo `WHERE` não casa com o registro existente ainda o regrava, agora sem deixar entradas antigas nos índices.
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
- `MERGE` junta objetos internos em qualquer profundidade. Antes substituía o objeto inteiro. Listas continuam sendo substituídas, e `MERGE {campo: {}}` já não esvazia o objeto.
- Os métodos `update` e `upsert` dos SDKs usam `MERGE` e passam a juntar objetos internos. Para substituir um objeto, use `SET` ou `CONTENT`.

### Importação tudo ou nada

- A importação roda numa transação. Se uma sentença falha, dados, definições, arestas e chave-valor voltam a ficar como antes.
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

### Conversões

As conversões passam a recusar entrada inválida em vez de gravar o instante atual, zero ou um texto com marcador. Um campo `int` recusa número com fração.

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

## Limites conhecidos

- Um índice `UNIQUE` ainda aceita duplicatas em escritas simultâneas feitas fora de transação num nó único.
- Num armazenamento em memória, um comando que falha no meio de várias linhas mantém as linhas anteriores.
