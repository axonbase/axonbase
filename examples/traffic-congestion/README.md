# Trânsito: trechos mais congestionados

Exemplo de consultas de agregação do AxonBase sobre milhares de eventos de trânsito: quais trechos de via
ficam mais congestionados entre 18:00 e 19:30 (horário de Brasília), todos os dias.

## Os dados

`seed.py` cria duas tabelas com dados simulados (a semente é fixa, então a carga é sempre a mesma):

- `transito` guarda `quando` (data e hora do evento, em UTC, o horário universal coordenado), `placa` e `pos`
  (ponto GeoJSON, o formato aberto para geometrias). São 3 semanas, com um ping por minuto por carro em cinco vias
  de São Paulo. No pico da tarde algumas vias andam devagar, então o mesmo trecho recebe muito mais pings.
- `trechos` guarda 15 corredores poligonais (3 por via), usados nas consultas por trecho nomeado.

Brasília é UTC-3 e não tem horário de verão, então 18:00 a 19:30 locais são 21:00 a 22:30 UTC. As funções de
horário aceitam o fuso como último argumento, e as consultas deste exemplo sempre o informam.

```bash
AXON_TOKEN=<jwt> python3 seed.py --url http://127.0.0.1:8000/sql --ns app --db main
```

`AXON_TOKEN` é um token JWT (JSON Web Token) de um usuário com permissão de escrita. Para pegar um, faça
`POST /signin` com usuário e senha. O script apaga e recria as duas tabelas.

## As consultas

`queries.sql` tem as consultas, todas testadas sobre esses dados. A principal não precisa de cadastro de trechos:

```sql
SELECT geo::cell(pos, 500) AS trecho, count() AS pings, count(DISTINCT placa) AS carros
FROM transito
WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'
GROUP BY trecho
ORDER BY pings DESC
LIMIT 10;
```

- `geo::cell(pos, 500)` devolve a célula da grade de 500 m que contém o ponto, como polígono GeoJSON. Serve de chave
  em `GROUP BY` e o mapa do Workspace do AxonBase Enterprise desenha as células.
- `time::clock(quando, fuso)` devolve `'HH:mm'` no fuso informado, então a janela do dia é um `BETWEEN` sobre texto.
- `count(DISTINCT placa)` conta carros diferentes. `ORDER BY` e `LIMIT` valem para os grupos.

Com a tabela `trechos`, a consulta 4 conta os pings dentro de cada corredor. Com um índice GEO (geoespacial) em
`transito.pos`, cada trecho lê só as células que cobrem o polígono, e `EXPLAIN` mostra a estratégia `GEO`.

## Limites

O `SELECT` ainda lê a tabela inteira quando não há índice que atenda o filtro, e o filtro por faixa de horário não
usa índice. Com 60 mil linhas, a consulta da grade leva cerca de 3 segundos; com o índice GEO, a consulta por
trechos nomeados leva cerca de 2 segundos.
