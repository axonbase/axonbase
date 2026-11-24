-- Cinco motores, um banco. Rode seed.sql antes (num banco só para este exemplo, por exemplo app/cinco).
-- No Workspace o resultado ganha a aba certa: Search (relevância), Vector (vizinhos), Map (pontos), Graph (ligações)
-- e Chart (agregações).

-- ===================== 1. Texto (BM25) =====================
-- @@ ativa o índice de busca; search::score() é a relevância e search::highlight() devolve o trecho com a palavra marcada.
SELECT titulo, search::score() AS relevancia, search::highlight(corpo) AS trecho
FROM artigo
WHERE corpo @@ 'motoristas'
ORDER BY relevancia DESC;

-- O analisador (CREATE ANALYZER) põe tudo em minúsculas, tira as palavras vazias e reduz o plural ao radical: a busca
-- abaixo, em maiúsculas e com dois termos, acha o documento que tem os dois (com mais de um termo, todos precisam aparecer).
SELECT titulo, search::score() AS relevancia FROM artigo WHERE corpo @@ 'MOTORISTAS mapa' ORDER BY relevancia DESC;

-- ===================== 2. Vetor (HNSW) =====================
-- Os 3 artigos mais próximos de um vetor de consulta. emb @@ $q usa o índice HNSW; ORDER BY distância e LIMIT escolhem os vizinhos.
LET $q = [0.88, 0.12, 0.08];
SELECT titulo, vector::similarity::cosine(emb, $q) AS similaridade, vector::distance::cosine(emb, $q) AS distancia
FROM artigo
WHERE emb @@ $q
ORDER BY vector::distance::cosine(emb, $q) ASC
LIMIT 3;

-- ===================== 2b. Texto e vetor juntos (busca híbrida) =====================
-- O texto filtra e pontua (BM25); o vetor entra no score combinado. Sem o LIMIT, vêm todos os artigos que casam.
LET $q = [0.20, 0.25, 0.90];
SELECT titulo, search::score() AS score_combinado
FROM artigo
WHERE corpo @@ 'relevância' AND emb @@ $q
ORDER BY search::score() DESC;

-- ===================== 3. Geo =====================
-- Restaurantes a até 3 km da Praça da Sé, do mais perto ao mais longe. O resultado tem um ponto GeoJSON (pos): aba Map.
SELECT nome, tipo, nota, pos,
       geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) AS metros
FROM restaurante
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 3000
ORDER BY metros;

-- ===================== 4. Grafo =====================
-- Como a Ana chega na Dani: o caminho vira linhas e a aba Graph desenha só Ana -> Caio -> Dani.
RETURN graph::trail(pessoa:ana, pessoa:dani, 'segue', 5);

-- Quem curtiu cada artigo (<- percorre as arestas que chegam).
SELECT titulo, <-curtiu<-pessoa AS curtido_por FROM artigo WHERE views > 0;

-- ===================== 5. Columnar =====================
-- Todas as colunas da consulta têm índice COLUMNAR, então o banco responde só com o índice, sem ler os documentos.
-- Com GROUP BY, o resultado ganha a aba Chart.
SELECT regiao, count() AS vendas, sum(valor) AS faturamento, avg(valor) AS ticket_medio
FROM venda
GROUP BY regiao
ORDER BY faturamento DESC;

SELECT mes, sum(valor) AS faturamento FROM venda GROUP BY mes ORDER BY mes;

-- Totais da tabela inteira: uma linha, que a aba Chart mostra como cartões.
SELECT count() AS vendas, sum(valor) AS faturamento, avg(valor) AS ticket_medio, min(valor) AS menor, max(valor) AS maior
FROM venda GROUP ALL;

-- EXPLAIN confirma a estratégia: COLUMNAR_AGG (agregação só pelo índice) em vez de FULL_SCAN.
EXPLAIN SELECT regiao, sum(valor) AS faturamento FROM venda GROUP BY regiao;
