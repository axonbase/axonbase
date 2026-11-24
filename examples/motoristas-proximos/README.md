# Motoristas por perto

Exemplo de busca por raio: mostrar no mapa os motoristas que estão a até 2 km de uma posição.

## Tabela

`seed.sql` cria a tabela `motorista` e um índice geoespacial (`GEO`) em `pos`, com 40 motoristas em volta da Praça da Sé
(São Paulo). Quinze estão a até 2 km, dois ficam perto da fronteira (cerca de 1,96 km e 2,04 km) e o resto está entre
2,2 km e 7 km.

| campo | conteúdo |
|---|---|
| `nome`, `placa` | identificação do motorista |
| `status` | `livre` ou `ocupado` |
| `pos` | ponto GeoJSON `{type: 'Point', coordinates: [longitude, latitude]}` (a longitude vem primeiro) |

```sql
DEFINE TABLE motorista SCHEMALESS;
DEFINE INDEX motorista_geo ON TABLE motorista COLUMNS pos GEO;
CREATE motorista:1 CONTENT {nome: 'Ana', placa: 'MOT001', status: 'livre',
                            pos: {type: 'Point', coordinates: [-46.637755, -23.556555]}};
```

Rode o arquivo inteiro com "Run all" no Workspace, num banco só para o exemplo (por exemplo `app/corrida`).

## A consulta

```sql
SELECT nome, placa, status, pos,
       geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) AS metros
FROM motorista
WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.6333, -23.5505]}) <= 2000
ORDER BY metros;
```

- `geo::distance(pos, ponto)` devolve a distância em metros; o filtro `<= 2000` é o raio de 2 km.
- Com o índice `GEO`, o banco lê só as células da grade em volta do ponto, em vez da tabela inteira (`EXPLAIN` mostra a
  estratégia `GEO`). O índice também vale quando o raio vem combinado com outro filtro, como `AND status = 'livre'`.
- A coluna `pos` é um ponto GeoJSON, então o Workspace mostra o mapa do resultado com um pino por motorista.
- `ORDER BY metros` ordena pelo apelido da coluna calculada (a partir da versão 0.6.3).

`queries.sql` traz as variações: só os livres, a posição e o raio em variáveis (`LET $eu`, `LET $raio`), os mais
próximos sem limite de raio, contagem por status, `EXPLAIN` e a atualização da posição de um motorista.
