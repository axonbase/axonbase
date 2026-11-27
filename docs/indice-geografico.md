# Índice geográfico com H3

O índice `GEO` usa o H3, o índice hexagonal hierárquico da Uber: o globo é dividido em hexágonos, e cada hexágono se divide em cerca de sete menores na resolução seguinte. Ele substitui a grade plana de geohash das versões até a 0.10.

```sql
DEFINE INDEX motorista_pos ON TABLE motorista COLUMNS pos GEO;
```

O campo guarda uma geometria no formato GeoJSON (formato geográfico sobre JSON, JavaScript Object Notation), com as coordenadas na ordem longitude, latitude. O índice vale para a primeira coluna declarada. Em tabela que já tem dados, ele é construído em segundo plano, e `INFO FOR TABLE` mostra o progresso.

## Consultas atendidas

| Consulta | `strategy` no `EXPLAIN` |
|---|---|
| `WHERE geo::distance(pos, <ponto>) <= <metros>` (ou `<`) | `GEO` |
| `ORDER BY geo::distance(pos, <ponto>) LIMIT <n>` | `GEO_KNN` |
| `WHERE geo::contains(<polígono>, pos)` | `GEO` |

Valem também quando a condição é uma das partes de um `WHERE` ligado por `AND`. O ponto, o raio e o polígono não podem depender da linha (literal, variável, `$parent` ou subconsulta). A busca dos vizinhos mais próximos, ou KNN (K-Nearest Neighbors), exige ordem crescente, um único critério de ordenação, `LIMIT`, e uma tabela sem junção nem agrupamento.

## O que o resultado garante

O índice só escolhe candidatos. O `WHERE` inteiro é avaliado de novo sobre cada documento, com a mesma conta da varredura:

- `geo::distance` é a fórmula de haversine sobre uma esfera de raio 6.371.008,8 metros. Polos, meridiano de 180 graus e longitudes fora de -180..180 funcionam sem cuidado especial.
- `geo::contains` é plano, em graus, sobre o anel externo do polígono. Um polígono que cruza o meridiano de 180 graus deve ser dividido em dois, um de cada lado. O índice segue exatamente a mesma regra da varredura.

Diferenças entre a consulta com índice e sem índice, que já existiam antes do H3:

- Uma linha sem o campo, ou com um valor que não é geometria, não entra no índice. Sem índice, `geo::distance(pos, p) < r` devolve essa linha, porque a distância é nula e nulo é menor que qualquer número. Com índice, ela não aparece. O mesmo vale para a ordenação pelos mais próximos: sem índice, essas linhas vêm primeiro. Para o mesmo resultado nos dois casos, garanta que toda linha tenha o ponto.

Casos tratados de forma conservadora:

- Linhas e polígonos guardados no campo indexado são sempre candidatos de uma consulta de raio, como na varredura.
- Um ponto com latitude fora de -90..90 também é sempre candidato.
- Enquanto existir alguma dessas geometrias na tabela, a busca dos mais próximos não usa o índice.

## Como funciona

| Chave | Conteúdo |
|---|---|
| `SH\|ns\|db\|tabela\|índice\|caminho\|registro` | entrada com longitude e latitude |
| `SHR\|ns\|db\|tabela\|índice\|registro` | caminho gravado, para mover e apagar sem varrer o índice |
| `SHV\|ns\|db\|tabela\|índice` | versão do formato, gravada quando a construção termina |
| `IXB\|ns\|db\|tabela\|índice` | marcador de construção, mantido de propósito (ver downgrade) |

- **Caminho hierárquico.** O identificador de 64 bits do H3 não serve de prefixo. A chave usa a célula base com três dígitos seguida de um dígito por resolução, até a resolução 10 (arestas de cerca de 75 metros). Assim o caminho de uma célula grande é prefixo do caminho de todas as células dentro dela.
- **Cobertura.** Para um raio ou uma caixa, o motor desce a hierarquia a partir das 122 células base: descarta o que está fora, usa como prefixo único o que está inteiro dentro e divide o resto, até cerca de 200 prefixos. Como um filho no H3 não cabe exatamente dentro do pai, a decisão usa um círculo de folga em volta de cada célula (duas arestas médias da resolução).
- **Filtro exato.** A entrada traz as coordenadas, então quem está fora do raio é descartado sem carregar o documento.
- **Vizinhos mais próximos.** Busca do melhor primeiro na hierarquia: uma célula só é aberta quando ainda pode conter um ponto mais próximo que o melhor candidato em mãos. O custo acompanha o número de resultados pedidos, e não o tamanho da tabela. Um `WHERE` muito seletivo junto do `ORDER BY` faz a busca andar mais longe.

## Funções AxonQL

A célula é a string hexadecimal usada por todas as bibliotecas H3. Entrada inválida devolve `NULL`.

| Função | Resultado |
|---|---|
| `geo::h3(ponto, resolução)` | célula que contém o ponto, resolução de 0 a 15 |
| `geo::h3::center(célula)` | ponto central |
| `geo::h3::boundary(célula)` | polígono do contorno |
| `geo::h3::resolution(célula)` | resolução |
| `geo::h3::parent(célula, resolução)` | célula ancestral |
| `geo::h3::children(célula, resolução)` | descendentes, até cinco resoluções abaixo |
| `geo::h3::disk(célula, k)` | a célula e as vizinhas a até `k` passos, com `k` de 0 a 100 |

```sql
SELECT geo::h3(pos, 8) AS celula, count() AS pings
FROM transito
GROUP BY celula
ORDER BY pings DESC
LIMIT 20;
```

O contorno de uma célula que cruza o meridiano de 180 graus sai com longitudes dos dois lados dele, e por isso não serve de polígono para `geo::contains`.

`geo::cell(ponto, metros)` não mudou: continua sendo uma grade quadrada em metros, independente do índice.

## Migração dos índices antigos

Não há comando a executar. Um índice no formato geohash é reconstruído em segundo plano quando:

1. a base é aberta pela versão nova;
2. um backup feito pela versão antiga é restaurado;
3. um nó em cluster recebe do líder uma escrita no formato antigo.

Enquanto reconstrói, o índice fica no estado "em construção": a consulta varre a tabela (`FULL_SCAN` no `EXPLAIN`), com o resultado correto e mais lento, e as escritas continuam. Planeje a atualização considerando esse período em tabelas grandes. O índice antigo é apagado no começo da reconstrução.

### Voltar para a versão anterior (downgrade)

O binário 0.10 não lê as chaves novas. O marcador `IXB` mantido pela versão nova faz o binário antigo entender que a construção foi interrompida e refazer o índice geohash a partir dos dados. Ao atualizar de novo, a versão nova percebe isso e refaz o índice H3. Versões anteriores à 0.10 não conhecem o marcador: nelas, apague e recrie os índices `GEO`.

### Cluster com versões misturadas

Todos os nós do cluster precisam rodar a mesma versão: cluster com versões misturadas não é suportado. Da 0.10.x ou anterior, atualize com os três nós parados, porque essas versões podem confirmar escritas sem gravar (veja [operacao-e-resiliencia.md](operacao-e-resiliencia.md#falha-de-versões-mistas)). Se as versões se misturarem mesmo assim, este é o comportamento do índice:

- Líder antigo e seguidor novo: o seguidor percebe as escritas no formato antigo e reconstrói o índice. Durante a janela, as consultas geográficas nele varrem a tabela.
- Líder novo e seguidor antigo: o seguidor antigo não atualiza o índice dele com as escritas novas e pode responder com resultado incompleto. Não leia de um nó antigo depois que a liderança passar para um nó novo.

## Biblioteca e plataformas

- `com.uber:h3:4.5.1`, a ligação oficial para Java, sob a licença Apache 2.0. O jar tem cerca de 1,3 MB.
- Traz bibliotecas nativas para Linux (x64 e ARM64, entre outras), macOS e Windows. No Linux exige a glibc (GNU C Library) 2.26 ou mais nova, o que a imagem oficial atende. Imagens baseadas em musl, como a Alpine, não são suportadas.
- A biblioteca nativa é extraída no diretório temporário. Se ele não permitir execução (`noexec`), informe outro com `-Dh3.native.dir=<diretório>`. A falha ao carregar aparece como erro "biblioteca nativa H3 indisponível".

## Portão de latência

Proposta para o portão da fase E4, ainda sem medida:

| Consulta, com 10 milhões de pontos | Meta p99 (percentil 99) |
|---|---|
| 20 vizinhos mais próximos | 50 ms |
| Raio de 500 m com `LIMIT 100` | 50 ms |

Condições: pontos uniformes em um quadrado de 1 grau de lado (cerca de 900 por km²), RocksDB local, 8 threads, depois do aquecimento. O raio sem `LIMIT` é medido e impresso sem reprovar, porque o custo cresce com o número de pontos devolvidos.

Em máquina dedicada Linux:

```bash
mvn -B -pl axonbase-core -am test -Dtest=GeoLatencyGateTest -Dsurefire.failIfNoSpecifiedTests=false -Daxon.gate.geo=true -Daxon.gate.geo.dir=/data/geo
```

| Propriedade (prefixo `axon.gate.geo.`) | Padrão |
|---|---|
| `points` | 10000000 |
| `side` (graus) | 1.0 |
| `queries` e `warmup` | 5000 e 1000 |
| `threads` | 8 |
| `radius` (metros) e `limit` | 500 e 100 |
| `neighbours` | 20 |
| `p99.knn` e `p99.radius` (ms) | 50 e 50 |
| `moves` e `step` (metros) | um décimo dos pontos (0 desliga) e 30 |
| `settle` (segundos) | 0; informado, pausa e mede mais uma vez antes dos movimentos |
| `dir` | diretório temporário; informado, reaproveita a carga |

As metas de p99 valem só para a primeira medida, feita logo depois da carga. Em seguida o teste repete as mesmas consultas (efeito do cache) e, com `settle`, repete de novo depois da pausa (efeito da compactação). Só então move registros em passos curtos, mede as atualizações e consulta mais uma vez. Em cada momento ele imprime o tamanho da base e a quantidade de tabelas SST (Sorted String Table), os arquivos de dados do RocksDB.

Para comparar resoluções de armazenamento, o roteiro abaixo copia o repositório para cada resolução, troca a constante só na cópia e junta os números em `resumo.txt`. O diretório de saída precisa ser novo e ficar fora do repositório.

```bash
RESOLUCOES="9 10 12" scripts/geo-comparar-resolucoes.sh /data/geo-resolucoes
```

## Limites conhecidos

- O índice não pagina por conta própria: um raio grande sem `LIMIT` carrega todos os documentos de dentro do raio.
- Um polígono que cobre quase toda a tabela lê o índice inteiro, o que pode custar um pouco mais que a varredura.
- Em cluster, a construção de um índice grande é local a cada nó, como a dos demais índices criados em segundo plano. Uma recarga do catálogo no meio dela (por exemplo, um comando de definição replicado) faz a construção recomeçar, o que pode atrasá-la muito se essas definições forem frequentes.
- Dentro de uma transação aberta, a busca dos mais próximos varre a tabela. As consultas de raio e de polígono usam o índice.
- Nos armazenamentos em memória e em arquivo WAL (Write-Ahead Log), cada passo da busca dos mais próximos copia as chaves da área examinada, e o custo cresce com a tabela. No RocksDB a leitura é em fluxo.
