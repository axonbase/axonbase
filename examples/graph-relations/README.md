# Grafo de relações

Exemplo de dados com arestas (`RELATE`) para ver a página **Relations graph** do AxonBase Enterprise e para
experimentar consultas de grafo no Workspace.

## A rede

`seed.sql` cria 12 registros e 24 arestas em `app/main`:

| tabela | registros | ligações (tipo de aresta) |
|---|---|---|
| `pessoa` | ana, bia, caio, dani, edu | `segue` (entre pessoas, com um ciclo), `trabalha_em`, `comprou`, `mora_em` |
| `empresa` | acme, byte | `fornece` (para produtos) |
| `produto` | notebook, mouse, monitor | |
| `cidade` | sp, rj | |

Rode o arquivo inteiro com "Run all" no Workspace. Ele apaga o que criou antes, então pode ser repetido.

## Ver na página Relations graph

Abra **Relations graph**, escolha a tabela `pessoa`, digite o id `ana` e aumente a profundidade para 2 saltos.
Aparecem 12 registros e 21 ligações, com uma cor por tabela. Clique num nó para ver as propriedades dele.

Os tipos de aresta precisam estar declarados no catálogo (`DEFINE TABLE segue SCHEMALESS` e os demais, que o
`seed.sql` já faz), porque a página descobre as arestas pelo catálogo do banco. Sem a declaração a página mostra
só o registro escolhido, sem ligações.

## Consultas no Workspace

`queries.sql` tem consultas de grafo. A travessia `->tipo->tabela` segue as arestas que saem e `<-tipo<-tabela` as
que chegam; o apelido (`AS segue`) vira o nome da coluna.

```sql
SELECT id, ->segue->pessoa AS segue, ->trabalha_em->empresa AS empresa FROM pessoa:ana;
```

## Limites conhecidos

- Com um único resultado a coluna volta como texto (`"pessoa:caio"`), não como lista.
- Travessias encadeadas (`->segue->pessoa->segue->pessoa`) ainda não são aceitas. Para mais de um salto use a
  página Relations graph, que tem o controle de profundidade.
- `->comprou->produto.nome` e `FETCH` sobre o resultado de uma travessia ainda não trazem os campos do registro
  ligado.
