# Cinco motores, um banco

Exemplo com os cinco tipos de busca do AxonBase no mesmo banco, para rodar no Workspace do AxonBase Enterprise:
texto (BM25, Best Matching 25, o ranking de relevância), vetor (HNSW, Hierarchical Navigable Small World, o índice de
vizinhos mais próximos), geo, grafo e columnar.

## Como usar

1. Crie um banco só para o exemplo (por exemplo `app/cinco`) e abra o Workspace nele.
2. Rode `seed.sql` com "Run all". Pode ser repetido: `CREATE` e `RELATE` regravam os mesmos registros.
3. Rode as consultas de `queries.sql` uma a uma. Cada resultado ganha a aba que combina com ele.

## O que o seed cria

| motor | tabela | índice |
|---|---|---|
| Texto (BM25) | `artigo` (8 artigos em português) | `SEARCH ANALYZER pt`, com minúsculas, radicais e palavras vazias |
| Vetor (HNSW) | `artigo.emb` (vetores de 3 dimensões, só para o exemplo) | `HNSW DIMENSION 3 DIST cos` (distância do cosseno) |
| Geo | `restaurante` (6 pontos em São Paulo) | `GEO`, o índice hexagonal H3 |
| Grafo | `pessoa`, `segue`, `curtiu` | arestas criadas com `RELATE` |
| Columnar | `venda` (240 vendas em 6 meses e 5 regiões) e as colunas `views` e `categoria` de `artigo` | `COLUMNAR` em cada coluna usada nas agregações |

## O que ver em cada resultado

- **Texto:** `search::score()` é a relevância e `search::highlight(corpo)` devolve o trecho com a palavra marcada. A aba
  Search lista por relevância, com o trecho destacado.
- **Vetor:** `WHERE emb @@ $q ORDER BY vector::distance::cosine(emb, $q) LIMIT 3` traz os 3 vizinhos mais próximos. A
  aba Vector mostra a similaridade de cada um.
- **Híbrida:** `corpo @@ 'relevância' AND emb @@ $q` usa o texto para filtrar e pontuar, e soma a similaridade do vetor
  ao score.
- **Geo:** a coluna `pos` é um ponto GeoJSON, então o resultado ganha a aba Map. A distância sai em metros.
- **Grafo:** `graph::trail(pessoa:ana, pessoa:dani, 'segue', 5)` devolve o caminho em linhas e a aba Graph desenha só ele.
- **Columnar:** quando todas as colunas da consulta têm índice `COLUMNAR`, o banco responde só com as entradas do
  índice, sem ler os documentos. `EXPLAIN` mostra a estratégia `COLUMNAR_AGG`. Com `GROUP BY` o resultado ganha a aba
  Chart, e sem agrupamento a aba mostra cartões com `count`, `sum`, `avg`, `min` e `max`.

## Limites que vale conhecer

- O stemmer (que reduz a palavra ao radical) é simples: tira o plural, mas não entende o português por inteiro. A busca por
  `motorista` não acha `motoristas`; a busca por `motoristas` acha.
- Um `SELECT` de agregação lê só pelo índice quando todas as colunas citadas têm índice `COLUMNAR`. Citar `id`, uma coluna
  sem índice, `JOIN` ou permissões na tabela faz a consulta ler os documentos, como antes.
- Os vetores do exemplo têm 3 dimensões para caberem na tela; embeddings reais têm centenas.
