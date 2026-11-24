# Grafo de relações

Exemplo de dados com arestas (`RELATE`) para ver a página **Relations graph** do AxonBase Enterprise e para
experimentar consultas de grafo no Workspace.

## A rede

`seed.sql` cria 12 registros e 25 arestas em `app/main` (rode o `seed.sql` de novo para recriar o que `queries.sql` remover):

| tabela | registros | ligações (tipo de aresta) |
|---|---|---|
| `pessoa` | ana, bia, caio, dani, edu | `segue` (entre pessoas, com um ciclo), `amigo_de`, `trabalha_em`, `comprou`, `mora_em` |
| `empresa` | acme, byte | `fornece` (para produtos) |
| `produto` | notebook, mouse, monitor | |
| `cidade` | sp, rj | |

Rode o arquivo inteiro com "Run all" no Workspace. Pode ser repetido (`CREATE` e `RELATE` regravam o mesmo registro e a
mesma aresta).

## Ver na página Relations graph

Abra **Relations graph**, escolha a tabela `pessoa`, digite o id `ana` e aumente a profundidade para 2 saltos.
Aparecem 12 registros e 21 ligações, com uma cor por tabela. Clique num nó para ver as propriedades dele.

A página descobre as arestas pelo catálogo do banco. A partir da versão 0.5.1 o `RELATE` registra o tipo da aresta
sozinho (como um `DEFINE TABLE tipo SCHEMALESS`), e na primeira subida o servidor registra os tipos de grafos criados
antes. Em versões anteriores é preciso declarar cada tipo (o `seed.sql` já faz isso, e declarar um tipo que existe
não muda nada); sem a declaração a página mostra só o registro escolhido, sem ligações.

## Consultas no Workspace

`queries.sql` tem consultas de grafo. A travessia `->tipo->tabela` segue as arestas que saem e `<-tipo<-tabela` as
que chegam; o apelido (`AS segue`) vira o nome da coluna.

```sql
SELECT id, ->segue->pessoa AS segue, ->trabalha_em->empresa AS empresa FROM pessoa:ana;
```

## Remover e navegar (a partir da versão 0.6.0)

- `UNRELATE pessoa:ana->amigo_de->pessoa:edu` remove uma aresta; `UNRELATE pessoa:dani->segue` remove todas as arestas de
  saída desse tipo. `DELETE` de um registro apaga também as arestas dele, de entrada e de saída.
- A travessia pode ser encadeada: `->segue->pessoa->segue->pessoa` segue duas vezes.
- `->comprou->produto.nome` devolve o campo de cada registro alcançado, e `FETCH` expande a coluna calculada pela
  travessia (`->comprou->produto AS produtos ... FETCH produtos`).

## Limites conhecidos

- Com um único resultado a coluna volta como texto (`"pessoa:caio"`), não como lista.
- Um campo comum com um id (`autor: pessoa:ana` em `CONTENT`) é guardado como texto, então `autor.nome` e
  `FETCH autor` não expandem; use arestas (`RELATE`) para ligações que você quer navegar.
- `UNRELATE` ainda não funciona em DATABASE LINK.

## Como duas pessoas se conhecem (a partir da versão 0.6.2)

`trail.sql` é um exemplo à parte (rode num banco só dele): Ana segue Caio, que segue Dani, e Ana e Dani não se seguem.
`graph::trail(pessoa:ana, pessoa:dani, 'segue', 5)` devolve uma linha por pessoa do caminho e, em cada linha, uma coluna
`segue` apontando para a próxima. Na aba **Graph** do resultado aparece só `Ana -> Caio -> Dani`, sem as outras pessoas
da rede. O quinto argumento escolhe o sentido (`'out'`, `'in'` ou `'both'`), e sem o tipo (`null`) o caminho pode usar
qualquer aresta, com a coluna de cada linha levando o nome da aresta usada.
