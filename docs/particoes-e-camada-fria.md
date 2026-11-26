# Partições por tempo, TTL e camada fria

Desenho da parte de dados no tempo da fase E4 do [plano de escala](plano-escala-e0-e5.md). Cobre três recursos:

- **Partição por tempo:** a tabela é dividida em faixas de tempo. Apagar uma faixa inteira custa o mesmo com mil ou com um milhão de linhas.
- **TTL (Time To Live), o prazo de validade de cada linha:** linhas vencidas são removidas em segundo plano.
- **Camada fria:** partições antigas saem do disco local e passam a morar num armazenamento de objetos compatível com S3 (Simple Storage Service), continuando consultáveis.

O índice geográfico H3 não faz parte deste desenho.

## Sintaxe em AxonQL

```sql
-- partição por tempo (a tabela precisa estar vazia)
DEFINE PARTITION ON TABLE eventos FIELD quando INTERVAL 1d RETENTION 30d COLD AFTER 7d;

SHOW PARTITIONS ON TABLE eventos;

-- remoção de partições inteiras
DROP PARTITION ON TABLE eventos AT d'2026-11-01T00:00:00Z';
DROP PARTITION ON TABLE eventos BEFORE d'2026-10-01T00:00:00Z';

-- mudança de camada (assíncrona)
ALTER PARTITION ON TABLE eventos AT d'2026-11-01T00:00:00Z' TO COLD;
ALTER PARTITION ON TABLE eventos AT d'2026-11-01T00:00:00Z' TO HOT;

-- prazo de validade por tabela
DEFINE TTL ON TABLE sessoes FIELD expira_em;
DEFINE TTL ON TABLE sessoes FIELD criada_em AFTER 1h;
DROP TTL ON TABLE sessoes;
```

Regras:

- O campo da partição precisa ser `datetime`. Com `DEFINE FIELD quando ON TABLE eventos TYPE datetime`, textos no padrão ISO (International Organization for Standardization) 8601 são convertidos na escrita. Linha sem o campo é recusada.
- `INTERVAL` é uma duração fixa de pelo menos 1 segundo. As faixas são alinhadas ao instante zero em UTC (Coordinated Universal Time): com `1d`, cada partição começa à meia-noite UTC.
- A partição nasce na primeira escrita da faixa. Cada tabela aceita até 4096 partições.
- `RETENTION d` remove a partição cujo fim ficou mais de `d` no passado. `COLD AFTER d` move para a camada fria a partição cujo fim ficou mais de `d` no passado.
- Repetir `DEFINE PARTITION` com o mesmo campo e intervalo troca retenção e prazo frio. Trocar campo ou intervalo é recusado enquanto houver partições.
- No TTL, a linha vence em `valor do campo + AFTER` (sem `AFTER`, no próprio valor). Linha sem o campo nunca vence. Entre o vencimento e a remoção ela continua visível.

## Catálogo

A definição (campo, intervalo, retenção, prazo frio, TTL) fica no plano de controle, em dois tipos novos (`PARTITIONING` e `TTL`), com o texto AxonQL da própria instrução. É replicada e reexecutada como as demais definições.

A lista de partições fica em **chaves de dados**, não no plano de controle. Assim ela entra no mesmo lote atômico das linhas, e vai de graça para o journal, a replicação e os backups.

| Chave | Conteúdo |
|---|---|
| `!part\|ns\|db\|tabela\|<início>` | descritor: início, fim, estado e, quando fria, o objeto, a contagem e o tamanho |
| `!partgen\|ns\|db\|tabela` | marca que muda a cada mudança de descritor (invalida o cache em memória) |
| `!pbloom\|ns\|db\|tabela\|<início>` | filtro de Bloom das chaves de uma partição fria |

`<início>` é o começo da faixa em milissegundos, com 19 dígitos, de modo que a ordem das chaves é a ordem do tempo.

Estados: `HOT` (local, lê e grava), `FREEZING` (indo para a camada fria, só leitura), `COLD` (no armazenamento de objetos, só leitura) e `THAWING` (voltando, só leitura).

## Formato das chaves

Numa tabela particionada, tudo que pertence a uma partição fica sob o prefixo dela, `P = ns\0db\0tabela\0!p\0<início>\0`:

| O quê | Chave física |
|---|---|
| registro | `P` + chave do registro (`n...`, `s...` ou `x...`) |
| índice UNIQUE | `P` + `!uidx\0...` |
| índice COLUMNAR | `P` + `!ci\|<índice>\|<coluna>\|...` |
| entrada de TTL (fora das partições) | `ns\0db\0tabela\0!ttl\0<vencimento>\0<chave do registro>` |

O motor continua pensando em chaves lógicas (`ns\0db\0tabela\0chave`). Uma camada nova (`PartitionedKv`, no pacote `core.partition`) traduz entre chave lógica e física e é o único ponto de toque no caminho de leitura e escrita do executor. Banco sem tabela particionada nem TTL não passa por ela.

- **Escrita:** a partição sai do valor do campo. As entradas de índice acompanham a linha. Um `UPDATE` que muda o campo move a linha de partição.
- **Leitura por id:** o id não diz a partição, então a camada pergunta às partições, da mais nova para a mais antiga. Numa partição fria, o filtro de Bloom evita baixar o objeto quando a chave não está lá. Não há diretório de id para partição, que custaria uma escrita a mais por linha e deixaria entradas órfãs a cada remoção.
- **Id gerado pelo motor:** é novo por construção, e a escrita não procura nas outras partições.
- **Poda:** num `SELECT`, `UPDATE` ou `DELETE` sem junção, condições ligadas por `AND` que comparam o campo da partição com um valor constante (`=`, `>`, `>=`, `<`, `<=`, entre dois valores) descartam as partições fora da faixa antes de ler. A condição é reavaliada linha a linha, então a poda só pode descartar o que não casaria.
- **Ordem sem `ORDER BY`:** as linhas saem por partição (tempo) e, dentro dela, por chave. Numa tabela comum saem só por chave.

## Remoção por faixa

Apagar uma partição grava, num único lote:

1. uma **marca de remoção por faixa**: um `put` na chave reservada `!rd/<início em hexadecimal>`, com o fim da faixa como valor;
2. a remoção do descritor (e do filtro de Bloom) e a nova marca de geração.

O armazenamento interpreta a marca: no RocksDB (a base de dados embutida do AxonBase) vira um `DeleteRange` nos dados e nas versões, aplicado antes dos demais `put` e `delete` do mesmo lote. A marca continua gravada como uma chave comum.

Codificar a remoção como uma chave do lote, em vez de um campo novo, faz ela viajar sem mudança de formato por todos os caminhos que já levam lotes: replicação Raft, cópia completa para um nó novo, journal do PITR (Point-In-Time Recovery, a restauração a um instante) e backup lógico. Ela é **carimbada pelo HLC** (Hybrid Logical Clock) como qualquer lote, e seguidores e restaurações aplicam a mesma remoção na mesma ordem.

Um lote com marca pega a trava exclusiva do armazenamento. Assim nenhum outro commit corre junto, e a ordem dos carimbos é a ordem real entre a remoção e as escritas.

O custo é constante no número de linhas: o lote tem poucas chaves. O espaço em disco volta nas compactações do RocksDB.

## Interação com índices

- **UNIQUE** e **COLUMNAR** são locais à partição e somem junto com ela. A unicidade continua valendo para a tabela inteira, porque a checagem consulta todas as partições. O custo dessa checagem cresce com o número de partições.
- **Texto completo, vetorial, geográfico e contagem** são recusados em tabela particionada nesta versão: guardam estatísticas ou um grafo globais, que não dá para apagar em tempo constante. `DEFINE PARTITION` numa tabela que os tem também é recusado.
- Em partição fria, as entradas de índice **ficam locais**. Só os registros vão para o objeto.
- Arestas de grafo (`RELATE`) que apontam para linhas de uma partição removida não são apagadas. A travessia ignora o destino que não existe.
- Remover uma partição **não dispara** `DEFINE EVENT` nem notificações de `LIVE SELECT`, e não gera linha de auditoria por registro. O TTL, ao contrário, remove por `DELETE` e dispara tudo.
- Viagem no tempo (`AT`, `BEFORE`) não vale para tabela particionada.

## Transações e concorrência

- A mudança de estado ou a remoção de uma partição pega o lado de escrita da trava de índices do banco durante o commit. Os statements e os commits de escrita seguram o lado de leitura, então nunca há uma escrita pela metade.
- Uma transação anota as partições em que escreveu. No `COMMIT`, se alguma sumiu ou deixou de ser `HOT`, ela falha com conflito e nada é gravado.
- Criar uma partição dentro de uma transação não gera conflito entre transações que criam a mesma.
- `DROP PARTITION` dentro de uma transação explícita vale no `COMMIT`. Depois dele, a própria transação já não vê as linhas.

## Segundo plano

Uma thread de manutenção (`PartitionMaintenance`) roda a cada 10 segundos (`AXON_PARTITION_MAINTENANCE_SECONDS`, 0 desliga). Em cluster, só o líder age.

1. **Retenção:** remove as partições vencidas.
2. **Camada fria:** congela, envia e troca as partições que passaram do prazo, e traz de volta as pedidas.
3. **TTL:** percorre as entradas `!ttl` em ordem de vencimento, só até a hora atual, e remove em lotes pequenos (`AXON_TTL_BATCH`, 200 linhas) com pausa entre eles e um teto por rodada (`AXON_TTL_MAX_PER_RUN`, 20 000). O custo é proporcional ao que venceu, não ao tamanho da tabela. Cada entrada é conferida contra a linha atual: entrada antiga (o campo mudou ou a linha sumiu) é só descartada.
4. **Coleta de lixo dos objetos frios** (ver adiante).

Na escrita, o TTL custa um `put` pequeno a mais no mesmo lote, sem leitura extra.

## Camada fria

### Armazenamento de objetos

Interface `ColdStore` com duas implementações: sistema de arquivos (`AXON_COLD_DIR`) e S3 compatível (`AXON_COLD_S3`, no mesmo formato de endereço do backup, `s3://bucket/prefixo?endpoint=...`), que reaproveita o cliente S3 do backup. Todos os nós de um cluster precisam enxergar o mesmo armazenamento.

### Formato do objeto

Um objeto por partição, **imutável**, de nome único: `parts/<ns>/<db>/<tabela>/<início>-<identificador>.axc`. Traz um cabeçalho com versão, os registros em ordem de chave e um rodapé com a contagem e o SHA-256 (Secure Hash Algorithm 256) do conteúdo.

### Ida e volta

1. **Congelar:** commit que passa o estado para `FREEZING`. A partir daí nenhuma escrita entra.
2. **Enviar:** lê os registros, grava o objeto, baixa de volta e confere o SHA-256. Sem trava: o resto do banco continua gravando.
3. **Trocar:** um único lote com a remoção por faixa dos registros, o descritor `COLD` e o filtro de Bloom.

Uma queda entre os passos deixa a partição em `FREEZING`, com os dados locais intactos; a rodada seguinte recomeça. A volta (`THAWING`) grava os registros e o estado `HOT` num lote só.

### Cache

O objeto lido é guardado em disco (`AXON_COLD_CACHE_DIR`), com limite de tamanho (`AXON_COLD_CACHE_MB`, 1024) e descarte do menos usado, o LRU (Least Recently Used). A leitura é transparente. Se o armazenamento de objetos estiver fora, a consulta que precisa dele falha com erro claro: nunca devolve resultado incompleto.

## Backups

**Nenhum backup copia objetos frios.** Os descritores são chaves de dados, então backup físico, backup lógico e journal já levam a referência.

O que precisa ser garantido é que o objeto referenciado continue existindo. São duas proteções:

- **Bloqueio (hold):** um documento no próprio armazenamento de objetos (`holds/<nome>.json`) lista objetos que não podem ser apagados.
  - backup físico: um bloqueio por backup, solto quando a retenção apaga o backup;
  - backup lógico: um bloqueio com validade de 35 dias (`AXON_COLD_LOGICAL_HOLD_DAYS`), porque o motor não sabe quando o arquivo é apagado;
  - motor em uso: cada motor publica as referências da sua base viva, o que protege uma cópia restaurada que compartilhe o armazenamento.
- **Carência:** um objeto sem nenhuma referência só é apagado depois de `AXON_COLD_GC_GRACE_DAYS` (35, o máximo da retenção do PITR). A coleta de lixo (GC, Garbage Collection) marca o objeto como órfão na primeira vez em que o vê assim e apaga depois da carência, se ele continuar sem referência. Isso cobre o PITR para instantes anteriores à remoção da partição.

Se não for possível gravar o bloqueio, o backup **falha**. Um restore confere se os objetos referenciados existem e recusa a troca de base se faltar algum.

## Journal do PITR

Nada muda no formato. Restaurar a um instante anterior à remoção de uma partição devolve as linhas, porque a marca de remoção está num lote posterior. Restaurar a um instante posterior reaplica a remoção. Instante anterior à ida para a camada fria devolve a partição local; posterior, a referência ao objeto.

## Réplicas e cluster

- Seguidores e réplicas de leitura aplicam a marca de remoção no mesmo ponto do log.
- Um nó novo recebe descritores e marcas pela cópia completa. Na aplicação de um lote, as remoções por faixa vêm antes das escritas.
- A thread de manutenção só age no líder. O envio ao armazenamento de objetos é feito por um nó só.

## Compatibilidade

- Base sem partição nem TTL: nenhuma chave nova, nenhum custo.
- Um nó de versão anterior grava a marca como uma chave qualquer e **não apaga a faixa**, divergindo dos demais. Só defina partições depois que todos os nós estiverem atualizados. O mesmo vale para voltar de versão.
- O formato do lote, do log Raft e do journal não muda.
- A definição nova no plano de controle é ignorada por um nó antigo.

## Fora desta versão

- Particionar uma tabela que já tem dados.
- Índices globais (texto completo, vetorial, geográfico) em tabela particionada.
- Bloqueio nativo de objetos do S3 (Object Lock).
- Rotas de administração para listar e soltar bloqueios.
