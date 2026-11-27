# Notas da versão 1.0.0-rc1

Esta é a versão candidata a lançamento (RC, release candidate) 1.0.0-rc1 do AxonBase. Ela **não é a `latest`**, e a tag `latest` não aponta para ela. Reúne as fases E2 a E5 do [plano de escala](plano-escala-e0-e5.md): Raft com fsync em grupo e log em segmentos, carimbo de tempo e restauração a um instante, vários shards, partições por tempo com camada fria, índice geográfico H3 (índice hexagonal hierárquico, da Uber) e operação em cluster.

**Os testes de carga das fases E3 a E5 ainda não foram feitos.** Trate esta candidata como não validada para carga.

> **AVISO: escritas confirmadas que nenhum nó guarda (modo cluster)**
>
> - As versões 0.4.0 a 0.10.1, no modo cluster, podem confirmar escritas que nenhum nó guarda depois de uma falta de quórum. O caso foi provado na 0.10.1. Nas anteriores, a evidência é só a leitura do código.
> - Atualize a partir da 0.10.x somente com os três nós parados. Atualizar um nó de cada vez a partir da 0.10.x não é suportado.
> - Parar o cluster não recupera o que já se perdeu.
> - O `upgrade-check` (`GET /admin/v1/cluster/upgrade-check`) recusa a troca nó a nó com membro 0.10.x, mas não impede que alguém a faça.
> - Mitigação: reinicie o nó que respondeu falta de quórum. Isso não devolve escritas já perdidas.
>
> Roteiro de atualização: [operacao-e-resiliencia.md](operacao-e-resiliencia.md#atualização-um-nó-de-cada-vez).

## Frentes

Cada item diz se está ligado por padrão ou só quando configurado.

### E2: Raft, carimbo de tempo e restauração a um instante

- **Raft com fsync em grupo.** Várias propostas dividem um fsync, conforme `AXON_STORAGE_SYNC`. Cada membro mantém conexão persistente e envia várias entradas por requisição. Ligado por padrão. O log do Raft usa `AXON_RAFT_LOG_SYNC`, com padrão `always`, que mantém a garantia da 0.10.1 (nenhuma entrada contada sem fsync). `batch` é opcional.
- **Log do Raft em segmentos.** Cada entrada tem CRC (Cyclic Redundancy Check, verificação cíclica de redundância). O log é truncado e compactado sem reescrita, e corrupção em dado confirmado é recusada. O `raft.log` antigo é migrado. Retenção: `AXON_RAFT_RETAIN_ENTRIES` (padrão 100000) e `AXON_RAFT_RETAIN_MINUTES` (padrão 30). Ligado por padrão.
- **Leitura em seguidor.** O cliente escolhe a consistência por requisição: cabeçalho `Axon-Read-Consistency` no `/sql` e no `/table`, ou campo `consistency` no RPC (Remote Procedure Call, chamada remota de procedimento). Valores: `stale`, `max-lag=<ms>` e `linearizable` (ReadIndex do Raft). Sem pedido, vale `AXON_RAFT_MAX_READ_LAG_MS`, cujo padrão `0` não impõe limite.
- **Learner.** Nó que acompanha o log sem votar, criado a partir de backup físico com `AXON_RAFT_LEARNER=true` e `AXON_RAFT_BOOTSTRAP_BACKUP`. Os votantes listam o endereço em `AXON_RAFT_LEARNERS`. Só existe quando configurado.
- **Desfecho desconhecido.** Sem quórum, a escrita que pode ter sido gravada responde com erro próprio, distinto de falha: RPC `-32013` (`OUTCOME_UNKNOWN`) ou HTTP (HyperText Transfer Protocol) 504 com `"status":"UNKNOWN"`. O cliente repete só quando a escrita certamente não aconteceu. Vale sempre.
- **Carimbo HLC (Hybrid Logical Clock, relógio híbrido).** Todo lote recebe um carimbo, com índice sem buracos. Em disco fica um teto, gravado e sincronizado antes de ser usado. `AXON_HLC_MAX_DRIFT_MS` (padrão 500) limita o desvio aceito de outro nó. Vale sempre.
- **Journal de PITR (Point-In-Time Recovery, restauração a um instante).** `AXON_PITR=on` liga o journal de segmentos, a restauração a um instante e a linha do tempo. Desligado por padrão. Ligá-lo custa vazão de escrita, que depende do número de escritores (ver [pitr.md](pitr.md)). Em cluster, o journal passa a ser arquivado, mas a API (Application Programming Interface) de PITR continua sem restaurar, importar nem apagar segmentos (409). A restauração a um instante em cluster sai pelo backup de cluster de shards (E3) e pela recuperação de desastre (E5).

### E3: vários shards

- **`SHARD BY` e gateway.** Tabela dividida por um campo, roteada por 4096 slots. Cada shard é um grupo Raft inteiro, com líder próprio. O gateway (`axonbase gateway`) não guarda dado e pode rodar em várias instâncias. Consultas espalhadas com `LIMIT` e agregações são empurradas para os shards. `UPDATE` e `DELETE` que atingem vários shards só rodam dentro de saga, e `INSERT` em vários shards é permitido.
- **Plano de controle e movimentação.** O mapa dos shards fica no grupo do shard base. Há mover, retomar, desistir e acrescentar shard. O backup de cluster usa barreira e manifesto, e a restauração a um instante vale para todos os shards ou para nenhum.
- **Desligado por padrão.** Sem gateway e sem `SHARD BY`, o banco segue como antes. A rota interna `/shard/v1/` só responde em nó com `AXON_SHARD_SECRET`.
- **Segredo dos shards.** `AXON_SHARD_SECRET` é obrigatório nos nós de shard e no gateway, que não sobe sem ele. O gateway o envia no cabeçalho `Axon-Shard-Secret`. Sem TLS (Transport Layer Security, cifra do transporte) entre eles, o segredo trafega em claro.
- **Variáveis do gateway.** `AXON_GATEWAY_SHARDS` (mapa criado na primeira subida), `AXON_GATEWAY_SHARD_USER`, `AXON_GATEWAY_SHARD_PASS`, `AXON_GATEWAY_ADMIN_TOKEN`, `AXON_GATEWAY_BIND` (padrão `127.0.0.1`), `AXON_GATEWAY_PORT` (padrão 8080), `AXON_GATEWAY_MAP_TTL_MS`, `AXON_GATEWAY_DEADLINE_MS` e `AXON_GATEWAY_EXPLICIT_IDS` (padrão `refuse`).
- **Recusas por decisão de projeto.** Em tabela dividida, o gateway recusa `JOIN`, `FETCH`, grafo, subconsulta, `RELATE`, busca textual, `LIVE`, `PERMISSIONS`, partição e TTL (Time To Live, prazo de validade das linhas). Também recusa Data Rule que use o campo de shard. Identificador de registro escolhido pelo cliente só com `AXON_GATEWAY_EXPLICIT_IDS=check`.
- **Administração.** `SHARD BY`, movimentação e backup de shard exigem administrador root.

### E4: partições por tempo, TTL, camada fria e índice geográfico

- **Partição por tempo.** `DEFINE PARTITION`, `SHOW PARTITIONS` e `DROP PARTITION AT|BEFORE`. Nesta versão, só se define partição em tabela vazia. Índices `UNIQUE` e `COLUMNAR` são locais a cada partição. Texto completo, vetorial, geográfico e de contagem são recusados em tabela particionada. Ligado só quando definido.
- **TTL.** `DEFINE TTL ON TABLE ... FIELD ...`, com `AFTER` opcional. A remoção vai em lotes (`AXON_TTL_BATCH`, padrão 200 linhas, e `AXON_TTL_MAX_PER_RUN`, padrão 20000 por rodada). Em cluster, só o líder remove. Ligado só quando definido.
- **Manutenção.** A thread `PartitionMaintenance` roda a cada 10 segundos (`AXON_PARTITION_MAINTENANCE_SECONDS`; `0` desliga). Em cluster, só o líder age.
- **Camada fria.** `ALTER PARTITION ... TO COLD|HOT` e `COLD AFTER`. Os objetos são imutáveis e têm resumo. O destino é um diretório (`AXON_COLD_DIR`) ou um bucket S3 (Simple Storage Service, armazenamento de objetos) em `AXON_COLD_S3`, com cache em disco (`AXON_COLD_CACHE_MB`) e filtro de Bloom, que evita leituras inúteis. Partição fria é somente leitura. A coleta de lixo tem carência de 35 dias (`AXON_COLD_GC_GRACE_DAYS`). Backups referenciam os objetos sem copiá-los e impedem a coleta deles. Ligado só com `AXON_COLD_DIR` ou `AXON_COLD_S3`.
- **Índice geográfico H3.** Os índices `GEO` criados nesta versão usam células H3, com as funções `geo::h3*`. `geo::cell` não mudou. A biblioteca é `com.uber:h3` 4.5.1 (licença Apache 2.0). Vale sempre para índices novos. As chaves do índice mudaram (prefixo `SH|`).

### E5: operação em cluster

- **QoS (Quality of Service, qualidade de serviço).** Classifica as cargas em transacional e analítica, com vagas, fila, orçamento de CPU e rebaixamento de leitura longa. `AXON_QOS` aceita `off` (padrão), `explicit` ou `auto`. O cliente só se declara transacional com `AXON_QOS_TRUST_CLIENT=true`. Os limites estão nas variáveis `AXON_QOS_ANALYTICAL_*`. Desligado por padrão.
- **Drenagem.** `POST /admin/v1/cluster/drain` leva o nó ao estado `DRAINING`. O `/ready` responde 503, o líder transfere o cargo e o nó espera `AXON_DRAIN_GRACE_MS`. A drenagem no desligamento está desligada (`AXON_DRAIN_ON_SHUTDOWN=false`).
- **Transferência de liderança e verificação de versões.** A transferência de liderança faz parte do roteiro de atualização. `GET /admin/v1/cluster/upgrade-check?target=<versão>&members=<versões>` aplica as regras de atualização. Membros 0.10.x fazem a verificação recusar a troca nó a nó.
- **Recuperação de desastre (DR, Disaster Recovery).** O `DrTool` (`com.axonbase.core.dr.DrTool`) copia para S3 ou para um diretório os segmentos fechados do journal e os backups físicos de um nó. Ele roda ao lado de cada nó. `restore` aplica o backup mais o journal até um instante. Em cluster, restaurar a um instante posterior à última escrita é recusado (use o instante `latest`).
- **Portões de operação.** `scripts/resilience-gates/run.sh` imprime linhas `E5`, com veredito `E5 <portão> RESULT PASS|FAIL`, e sai com código 1 quando reprova. Os portões são opt-in.

### Painel Enterprise

O backup automático, a janela de PITR e a tela de restauração a um instante estão no Enterprise, que é um repositório separado, publicado junto desta candidata como 0.10.0-rc1. Veja o que não foi verificado abaixo.

## Mudanças de comportamento em relação à 0.10.1

Com a configuração padrão, estas mudanças valem:

- **Erro de desfecho desconhecido.** Sem quórum, a escrita responde RPC `-32013` (`OUTCOME_UNKNOWN`) ou HTTP 504 com `"status":"UNKNOWN"`, em vez de erro de falha comum.
- **Redirecionamento 307 em `/sql`.** Perda de liderança passa a responder 307. Um comando que chamou serviço externo (link de banco, embedding ou provedor de IA) e perdeu a liderança devolve erro, sem ser refeito, em vez de redirecionar.
- **Rota `/shard/v1/`.** Sem `AXON_SHARD_SECRET`, responde 404, como na 0.10.x. Com o segredo, modo de sistema, definições e backup nessa rota continuam exigindo administrador root, e escrita que estoura o prazo responde desfecho desconhecido (504).
- **Manutenção de partições sempre ativa.** A thread roda a cada 10 segundos. Em cluster, só o líder age.
- **Índices GEO antigos.** Índices geohash são reconstruídos ao abrir a base. Ao voltar para a 0.10.x, o marcador `IXB|` faz o binário antigo refazer o índice geohash sozinho. Ao atualizar de novo, a versão nova refaz o índice H3.
- **Carimbo HLC em todo lote.** O teto de HLC é gravado em disco antes de ser usado. Se essa gravação falhar, o commit que precisava dele também falha.
- **Journal de PITR em cluster.** Com `AXON_PITR=on`, o journal passa a ser arquivado nos nós do cluster.
- **Backup físico em learner ou seguidor.** A criação é liberada nesses nós. Restauração e importação continuam bloqueadas em cluster.
- **Voto restrito ao aplicado.** Todo nó recusa voto a candidato com log menor que o que ele já aplicou.
- **Defesas contra a 0.10.x.** O motor tem verificações para reduzir a exposição de quem trocar nó a nó assim mesmo. Elas não dão garantia.
- **Subida com S3 configurado.** Com a base vazia e falha ao listar, baixar, decifrar ou aplicar a restauração pedida, o servidor não sobe e sai com código 2. Uma restauração interrompida recusa o próximo início, em vez de subir com base pela metade. Com o S3 inacessível, o bucket ausente ou a senha de cifra errada, é preciso corrigir a causa ou remover as variáveis do S3.
- **Valor inválido derruba a partida.** Um valor mal escrito em `AXON_QOS*`, `AXON_CLUSTER_HTTP_PEERS` ou `AXON_DRAIN_*` encerra o processo antes de abrir a porta.
- **Índice geográfico.** As chaves mudaram (prefixo `SH|`). Consultas de terceiros que lerem essas chaves precisam ser revistas.

## Correções em relação à 0.10.1

Estes defeitos da 0.10.1 foram corrigidos no código desta candidata:

- Transações concorrentes no cluster podiam perder escritas em silêncio e deixar o líder diferente dos seguidores.
- O líder apagava entradas do próprio log quando faltava quórum.
- Um pedido de voto com termo maior não tirava o líder do cargo, e os batimentos se acumulavam sem limite.
- Termo e voto não eram sincronizados em disco.
- Ao religar um membro, o catálogo em memória podia perder definições.
- Eleições causadas por envio travado num fsync de segmento cheio, ou por seguidor que atendia lotes grandes sem contar o contato como batimento, têm correção no código: batimento em conexão própria e contagem por requisição. Eleições causadas por pausa da máquina virtual não são cobertas (ver limitações).
- Identificador textual gerava uma estatística por identificador no painel de atividade, e o mapa de estatísticas não tinha limite. Isso degradava leituras por identificador textual e consumia memória.
- O índice vetorial HNSW (Hierarchical Navigable Small World) tinha recall baixo na construção em segundo plano, e apagar ou atualizar linha não religava os vizinhos. Índices vetoriais criados antes continuam com o grafo antigo. Para ganhar a correção, recrie o índice.

## Limitações conhecidas

### Cluster e atualização

- Atualização a partir da 0.10.x só com parada total (ver aviso).
- O reinício nó a nó foi verificado só com a mesma versão nova dos dois lados. A troca entre duas versões novas diferentes não foi verificada.
- Partição nova só deve ser definida com o cluster todo atualizado. Um nó antigo não apaga a faixa.
- O prazo de eleição é de 350 a 850 ms. Pausas da máquina virtual (coleta de lixo) ou falta de CPU não são cobertas. Pré-voto não foi implementado.
- A porta do Raft (9000 por padrão) não tem autenticação. Exponha-a só em rede interna.
- Com o padrão `AXON_RAFT_MAX_READ_LAG_MS=0`, um seguidor isolado continua respondendo com dados antigos.
- O redirecionamento 307 de escrita aponta para o endereço do Raft, a não ser que `AXON_CLUSTER_HTTP_PEERS` seja informado.
- Com `--path /data`, o diretório do Raft fica fora do volume Docker.
- Com QoS ligado, as leituras de manutenção do movimentador de slots podem ser rebaixadas e estourar `AXON_QOS_ANALYTICAL_TIMEOUT_MS`.
- Um 429 do limite de taxa em `/shard/v1/` não é repetido pelo gateway.
- O log do Raft guarda 30 minutos de segmentos por padrão. O disco precisa acompanhar.

### Shards

- Mover slots do shard base exige que nenhuma saga esteja aberta.
- Depois de mover slots, só um backup novo permite restaurar instantes posteriores.
- Uma requisição atrasada além do prazo de proteção pode gravar no dono antigo do slot. A proteção é por tempo.
- Um campo lido por record link para tabela dividida vem vazio, sem erro.
- O parser não aceita `LEAVE SAGA` nem `USE NS x DB y`. Os caminhos correspondentes do gateway não são alcançáveis.
- Só por leitura do código, não testado: o `CANCEL` da saga nativa pode falhar em chaves UUID (Universally Unique Identifier) geradas automaticamente.
- Só por leitura do código: `DROP EVENT` não entra no plano de controle, e `ALTER TABLE` substitui o registro de `DEFINE TABLE`.
- Só por leitura do código: a restauração de shards publica um diretório de cada vez. Um erro de disco na última etapa pode deixar parte publicada, então confira o código de saída antes de subir qualquer nó.
- O bloqueio frio de um backup de shard não vence nem é liberado. Isso é conservador, mas acumula objetos.

### Partições, TTL e camada fria

- Índices de texto completo, vetoriais, geográficos e de contagem não existem em tabela particionada.
- Ler pelo identificador no instante em que a linha muda de partição pode não encontrá-la.
- Dois comandos avulsos simultâneos com o mesmo valor `UNIQUE` e identificadores diferentes podem passar os dois. Isso já acontece em tabela comum.
- Remover partição não dispara eventos nem `LIVE SELECT`, e não apaga arestas.
- Partição fria tem limite de 2 gigabytes. A volta é uma única entrada do log do Raft, com a partição inteira em memória.
- Ler pelo identificador e checar `UNIQUE` ficam mais caros com muitas partições (teto de 4096).
- Não há Object Lock nativo do S3. Objetos frios órfãos têm carência de 35 dias e bloqueio por backup.
- O bloqueio de backup lógico vale 35 dias. Quem precisa de retenção maior no Enterprise precisa de uma rota para renovar o bloqueio, que ainda não existe.
- Apagar uma partição não limpa as entradas do índice GEO. Elas não geram resultado errado, mas ocupam espaço.

### Geográfico e vetorial

- A biblioteca H3 exige glibc (GNU C Library, biblioteca C do projeto GNU). Imagens Alpine não são suportadas. Se `/tmp` estiver montado com `noexec`, use `-Dh3.native.dir=<diretório executável>`.
- A presença das bibliotecas nativas no arquivo jar (Java ARchive) não foi confirmada.
- Atualizar ou apagar uma linha com índice vetorial lê as arestas dos vizinhos. Isso aumenta a chance de conflito entre transações concorrentes na mesma tabela, e o custo não foi medido.
- Identificador textual com o caractere `|` quebra a leitura das arestas do grafo HNSW. Defeito conhecido, não corrigido.

### Exportação e importação

- A exportação em AxonQL perde as opções de índice `SEARCH`, `HNSW`, `COLUMNAR` e `COUNT`.
- Em tabela comum, a exportação grava datas e decimais no envelope `{"$...": ...}`, que a importação não reconverte. Isso é defeito anterior à candidata.

### Backup e recuperação

- A restauração em cluster a um instante aceita o instante `latest`, mas recusa instantes posteriores à última escrita.
- A recuperação de desastre sem instante falha quando o fim do histórico se perdeu. Aceitar a perda exige informar um instante anterior, e pela linha de comando o último milissegundo íntegro também fica de fora.
- A restauração a partir do S3 pedida pela API administrativa pula backup vazio ou incompleto sem avisar e informa sucesso.
- A mensagem de recusa por restauração incompleta cita o S3 mesmo quando a restauração parada foi de arquivo. Sem S3 configurado, a subida não confere essa marca.

### Partições em cluster, procedimentos e concorrência

- Em cluster, partição acima de cerca de 256 MiB (mebibyte) não vai nem volta da camada fria, e uma partição já fria acima disso não tem como voltar.
- Em nó único, um procedimento chamado por evento não pode criar partição: o comando é recusado a cada faixa de tempo nova, salvo se uma escrita comum a criar antes.
- Logo depois de uma troca de líder, um `DEFINE PARTITION` ou `DEFINE INDEX` ainda pendente pode não valer para uma escrita simultânea.
- Uma releitura do catálogo (por exemplo, ao cancelar uma sessão depois de uma definição concorrente) pode deixar uma escrita simultânea ver o catálogo pela metade. Defeito anterior à candidata.
- Nos primeiros instantes da subida, o servidor HTTP atende antes de conhecer o estado do cluster, e um seguidor pode ignorar a consistência de leitura pedida.

### Auditoria e máscara

- Em tabela dividida, os casos e bloqueios da auditoria por IA valem por shard.
- A máscara de coluna no `/sql` pode ser contornada por apelido, `VALUE`, `ONLY` ou campo aninhado, e a ordem ou o filtro permitem deduzir valores. Defeito anterior à candidata.

### SDKs

- O SDK (Software Development Kit) .NET desta versão candidata não é publicado no NuGet.
- Os testes de integração do SDK Java exigem autenticação e falham sem ela. Isso também acontece na 0.10.1.

## O que não foi verificado

- **Portões de carga.** Os roteiros da E2 foram ensaiados só numa máquina, nunca em máquinas separadas. O portão de carga da E3 nunca foi executado. Os portões da E4 nunca rodaram. Os portões da E5 foram ensaiados numa só máquina.
- **Carga de 10 gigabytes em recuperação de desastre.** A meta de menos de 10 minutos para 10 gigabytes não foi medida.
- **Metas do índice geográfico.** As metas de latência com 10 milhões de pontos não foram medidas.
- **Armazenamento real.** A recuperação de desastre não foi executada contra MinIO nem contra S3 real. Os testes do cliente usam um servidor HTTP simulado.
- **Cobertura de testes.** Não há medição consolidada de cobertura para a candidata inteira.
- **Últimas correções.** A segunda revisão independente cobriu as correções até o meio da última rodada. As quatro correções finais (trava de índices na releitura do catálogo, ordem de instalação dos ganchos do cluster, prazo na rota de shard e marca de restauração de arquivo) não passaram por nova revisão.
- **Desempenho em cluster.** A comparação com a 0.10.1 foi feita em nó único. A escrita em cluster não foi medida no código final.
- **Enterprise.** A tela não foi vista num navegador. Excluir um cluster não apaga o journal no MinIO.
- **Atualização de cluster.** A troca nó a nó entre duas versões novas diferentes não foi verificada.
- **SDK .NET.** Não publicado no NuGet nesta candidata.
- **SDK Java.** Os testes de integração precisam de autenticação para rodar.

## Validação

Tudo rodou em máquinas virtuais Linux de 8 processadores virtuais no GCP (Google Cloud Platform).

| Verificação | Resultado |
|---|---|
| Suíte completa do motor, três rodadas seguidas no código publicado | Sem falhas. Motor principal com 1284 testes (17 pulados) e servidor com 257 (2 pulados), além dos demais módulos |
| Reinício nó a nó de um cluster só com esta versão | 60 de 60 rodadas sem falha e sem perda |
| Atualização desde a 0.10.1 com parada total | 60 de 60 trocas sem perda causada pela troca e sem divergência, metade com parada normal e metade com processos derrubados. Em uma troca, a 0.10.1 já havia perdido escritas antes da parada |
| Prova do defeito de escrita confirmada | A 0.10.1 falha e esta versão passa |
| Ingestão em nó único contra a 0.10.1, na mesma máquina | +0,2% com 1 thread e +1,9% com 8 threads |
| SDKs contra o motor desta versão | Java, Node.js, Python, .NET, Ruby, Rust, Go e PHP (PHP: Hypertext Preprocessor). Passam, exceto 2 testes de integração do Java que falham por falta de autenticação, também na 0.10.1 |
| Enterprise: agente, backend, tela e regras de alerta | Sem falhas (314 testes no backend e 77 arquivos de teste na tela) |
| Enterprise de ponta a ponta contra este motor, execução oficial | Backup em cluster, restauração a um instante e restauração física de 10 GiB (gibibyte) aprovados |

Uma das rodadas anteriores da suíte falhou num teste intermitente da 0.10.1 (listagem de tokens de serviço), que comparava um trecho errado do segredo. O teste foi corrigido, sem mudança no produto.

### Achados da revisão independente

Uma revisão só por leitura do código integrado apontou 4 achados críticos, 2 altos e 3 médios, e bloqueou a publicação. Todos receberam correção com um teste que falhava antes:

- **Críticos:** rota interna dos shards sem auditoria e com vazamento por ordenação em coluna mascarada; escrita perdida em partição removida ao mesmo tempo; índice retomado vazio na releitura do catálogo; falha de restauração na subida que levava a apagar backups bons.
- **Altos:** trava permanente em evento que criava partição; partição grande da camada fria que travava as escritas do cluster. A correção desta é uma recusa, e não a volta em lotes.
- **Médios:** cálculo sem autenticação no gateway; consulta entre shards sem prazo nem cancelamento; recuperação de desastre que terminava com sucesso sem o fim do histórico.

Uma segunda revisão não encontrou achado crítico nem alto, e os seus achados médio e baixos também foram corrigidos. As limitações que sobraram estão listadas acima.
