-- Embeddings. Rode seed.sql antes (num banco só para este exemplo, por exemplo app/embeddings).
-- O Workspace mostra a aba Vector nos resultados com vizinhos e similaridade.

-- 1. Busca por significado: o texto vira vetor com o embedding do próprio campo emb e os mais próximos vêm primeiro.
--    Sem ORDER BY e sem LIMIT, o banco ordena pela distância e devolve os 10 mais próximos.
SELECT titulo FROM artigo WHERE emb @@ 'quem está perto de mim de carro' LIMIT 3;

-- 2. A mesma busca com o vetor à mostra: vector::embed devolve o vetor (apelido: vector::fromstr) e dá para
--    calcular a similaridade e a distância. Use "Run all" para o LET valer na consulta seguinte.
LET $q = vector::embed('rede de amigos');
SELECT titulo,
       vector::similarity::cosine(emb, $q) AS similaridade,
       vector::distance::cosine(emb, $q) AS distancia
FROM artigo
WHERE emb @@ $q
ORDER BY vector::distance::cosine(emb, $q) ASC
LIMIT 4;

-- 3. Palavra exata e significado juntos (híbrida): o texto filtra e pontua, o vetor entra no score.
CREATE ANALYZER pt LOWERCASE;
CREATE INDEX artigo_texto ON TABLE artigo COLUMNS corpo SEARCH ANALYZER pt;
SELECT titulo, search::score() AS score
FROM artigo
WHERE corpo @@ 'documentos' AND emb @@ 'ordenar resultados por relevância'
ORDER BY search::score() DESC;

-- 4. O vetor acompanha o texto: ao mudar o corpo, o banco recalcula o vetor sozinho (e só nesse caso).
UPDATE artigo:5 SET corpo = 'Como mostrar no mapa quem está a dois quilômetros de mim.';
SELECT titulo FROM artigo WHERE emb @@ 'motorista por perto' LIMIT 2;

-- 5. Custo antes de regerar: REEMBED DRY RUN só conta linhas, caracteres e estima os tokens (sem chamar o provedor).
REEMBED TABLE artigo DRY RUN;

-- 6. Regerar os vetores de uma tabela (por exemplo depois de trocar de modelo, com a nova dimensão no campo e no índice).
-- REEMBED TABLE artigo FIELD emb;

-- 7. Conferir os embeddings definidos e testar um deles (a chave só aparece como nome da variável de ambiente).
INFO FOR DATABASE;
RETURN vector::embed('teste');
