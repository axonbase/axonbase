-- Motoristas por perto. Rode seed.sql antes (num banco só para este exemplo, por exemplo app/corrida).
-- A posição "eu" nos exemplos é a Praça da Sé, em São Paulo: longitude -46.6333, latitude -23.5505 (nessa ordem).
-- Quando uma coluna do resultado é um ponto GeoJSON (a coluna pos), o Workspace mostra o mapa com um pino por linha.

-- 1. Todos os motoristas a até 2 km de mim, do mais perto ao mais longe. geo::distance devolve metros.
SELECT nome, placa, status, pos,
       geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) AS metros
FROM motorista
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 2000
ORDER BY metros;

-- 2. Só os que estão livres (o raio continua usando o índice GEO; o status filtra o que o índice trouxe).
SELECT nome, placa, pos,
       geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) AS metros
FROM motorista
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 2000
  AND status = 'livre'
ORDER BY metros;

-- 3. Minha posição e o raio em variáveis: troque só as duas primeiras linhas.
LET $eu = {type: 'Point', coordinates: [-46.6333, -23.5505]};
LET $raio = 2000;
SELECT nome, placa, status, pos, geo::distance(pos, $eu) AS metros
FROM motorista
WHERE geo::distance(pos, $eu) <= $raio
ORDER BY metros;

-- 4. Os 3 motoristas livres mais próximos, sem limite de raio.
SELECT nome, placa, pos,
       geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) AS metros
FROM motorista
WHERE status = 'livre'
ORDER BY metros
LIMIT 3;

-- 5. Quantos motoristas por status dentro do raio.
SELECT status, count() AS motoristas
FROM motorista
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 2000
GROUP BY status;

-- 6. Confirmar que o raio usa o índice GEO (a estratégia deve ser GEO, e não varredura da tabela).
EXPLAIN SELECT nome FROM motorista
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 2000;

-- 7. Atualizar a posição de um motorista (o índice GEO acompanha).
UPDATE motorista:1 SET pos = {type: 'Point', coordinates: [-46.6340, -23.5510]};
