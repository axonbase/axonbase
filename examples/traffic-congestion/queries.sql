-- Trechos mais congestionados entre 18:00 e 19:30 (horário de Brasília), todos os dias.
-- Os horários ficam em UTC; o fuso é informado em cada consulta.

-- 1. Sem cadastro de trechos: grade de 500 m. O resultado já é GeoJSON, então o mapa do Workspace desenha as células.
SELECT geo::cell(pos, 500) AS trecho, count() AS pings, count(DISTINCT placa) AS carros
FROM transito
WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'
GROUP BY trecho
ORDER BY pings DESC
LIMIT 10;

-- 2. Por dia: quais dias tiveram mais movimento na janela.
SELECT time::day(quando, 'America/Sao_Paulo') AS dia, count() AS pings
FROM transito
WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'
GROUP BY dia
ORDER BY pings DESC;

-- 3. Perfil da janela em faixas de 10 minutos.
SELECT time::floor(quando, '10m') AS faixa, count() AS pings
FROM transito
WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'
GROUP BY faixa
ORDER BY faixa;

-- 4. Trechos nomeados (tabela trechos). Com um índice GEO a leitura de cada trecho usa só as células do polígono.
DEFINE INDEX transito_geo ON TABLE transito COLUMNS pos GEO;
SELECT id, nome, geo,
  (SELECT VALUE count() FROM transito
    WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'
      AND geo::contains($parent.geo, pos)) AS pings
FROM trechos
ORDER BY pings DESC;

-- 5. O plano mostra se o índice foi usado.
EXPLAIN SELECT * FROM transito WHERE geo::contains((SELECT VALUE geo FROM trechos:pinheiros_2), pos);
