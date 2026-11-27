# Plano de escala e backup: E0 a E5 (versão 3, para aprovação)

Este plano atualiza o plano E0 a E5 com o estado real depois das versões 0.9.0 e 0.9.1 e com as metas de backup e restauração aprovadas. Cada fase termina num teste de portão: sem o teste verde, a fase não está pronta.

Mudanças da versão 3: o carimbo de tempo dos lotes está decidido (relógio híbrido, HLC) e tem uma seção própria, com regras, garantias, limites e testes. As decisões de 1 a 6 da versão 2 estão aprovadas, e o que resta aprovar está na última seção.

## Metas aprovadas

| Meta | Valor |
|---|---|
| Perda de dados por falha de nó (RPO, Recovery Point Objective) | 0, pelo quórum do cluster Raft |
| RPO de backup (perda tolerada se o cluster inteiro for perdido) | 5 minutos |
| Restauração a um instante (PITR, Point-In-Time Recovery) | granularidade abaixo de 1 minuto, arquivando os lotes do log Raft |
| Retenção | 7 dias por padrão, configurável até 35 |
| Tempo de recuperação (RTO, Recovery Time Objective) | restaurar 10 GB em menos de 10 minutos (conta estimada, a ser medida no teste de portão da E1) |
| Destino dos backups | MinIO do Enterprise, compatível com S3 (Simple Storage Service) |

## Ponto de partida

- **Feito:** a E0, exceto o benchmark no CI (Continuous Integration) com metas. Saiu na 0.9.0, com correções na 0.9.1.
- **Existe e funciona:** exportação em fluxo (completa e incremental por marca d'água de versão), criptografia em blocos, envio ao S3, política por cron no Enterprise, retenção que preserva a cadeia e verificação do SHA-256 (Secure Hash Algorithm 256) no restore.
- **Defeitos verificados em teste:**
  - Restaurar só um incremental apaga tudo e deixa apenas o delta (21 linhas em vez de 119).
  - O backup lógico não é uma foto de um instante: varre as chaves e lê cada valor no estado atual.
  - O arquivo não traz manifesto nem checksum próprio.
  - O restore apaga e regrava na mesma base, e em cluster é recusado.
  - Ninguém testa se o backup restaura.

## Fases

### E0: fechar a fase (2 a 3 dias úteis)

**Entregas**
- Job de CI que roda o `ScaleBaselineBenchmarkTest` em Linux, com 200 mil linhas, e reprova regressões.
- `ORDER BY ... LIMIT` sem índice deixa de varrer a tabela inteira de forma serial: varredura paralela com limite do heap (hoje 2,4 s em 200 mil linhas).

**Teste de portão:** o job de CI verde com metas por razão entre testes, que resistem ao ruído do executor: ingestão com 8 threads pelo menos 2 vezes a de 1 thread, `SELECT ... LIMIT 10` abaixo de 50 ms, busca dos 20 mais próximos com p99 abaixo de 100 ms, e queda de mais de 25% frente à linha de base registrada reprova.

### E1: backup, restauração e índice online (cerca de 3 semanas)

**E1.0, correções do backup atual (3 a 4 dias úteis)**
- Restore em cadeia: o Enterprise monta o completo e os incrementais na ordem, confere a continuidade pela marca d'água e aplica sem apagar entre eles. O motor passa a recusar um incremental isolado com erro claro.
- Exportação a partir de um snapshot do RocksDB, lendo os valores do próprio iterador.
- Manifesto no arquivo (formato, tipo, base, versão inicial e final, horário, nó, contagem) e checksum ao final.

*Teste de portão:* backup sob escrita concorrente restaura com registros e índices coerentes; cadeia de um completo com vários incrementais, com deleções, restaura exatamente; incremental isolado é recusado; arquivo adulterado é detectado.

**E1.1, backup físico (7 a 10 dias úteis)**
- Backup físico com o `BackupEngine` do RocksDB: completo e incremental por arquivo, verificação de integridade e retenção segura. O restore de qualquer backup não exige montar a cadeia na mão.
- API de backup físico no motor e envio ao MinIO em fluxo, criptografado.
- Restore em diretório novo seguido de troca atômica (renomear), de modo que uma falha no meio não destrói a base original.
- Incrementais a cada 5 minutos pela política do Enterprise, retenção de 7 dias (até 35) preservando a cadeia.
- Verificação automática diária: restaurar o último backup num contêiner descartável e conferir contagens e uma amostra, com alerta se falhar.
- Métrica `axe_backup_rpo_seconds` (idade do último ponto restaurável) e alerta no Enterprise quando passar de 5 minutos. Tela de backups com a cadeia, o indicador de RPO e o estado da verificação.

*Teste de portão:*
1. Restaurar 10 GB em menos de 10 minutos, medido em Linux com MinIO local.
2. Incrementais a cada 5 minutos sob 5 mil escritas por segundo sem queda de vazão acima de 10%.
3. Restore do terceiro incremental confere por checksum.
4. Matar o processo no meio de um backup e de um restore não corrompe a base original.

**E1.2, índice criado em segundo plano (cerca de 5 dias úteis)**
- `DEFINE INDEX` não bloqueia: constrói em segundo plano com progresso visível e aplica as escritas concorrentes.
- `UNIQUE` criado depois da carga valida duplicatas e aborta sem efeito colateral.

*Teste de portão:* índice em tabela de 5 milhões de linhas com escrita concorrente, resultado idêntico ao de um índice criado antes da carga, e duplicata detectada em `UNIQUE`.

**Versão:** 0.10.0.

### E2: Raft, réplicas de leitura e PITR (cerca de 4 a 5 semanas)

**Entregas**
- Group commit no Raft (agrupar várias escritas por fsync) e replicação em pipeline.
- Leitura em seguidores com limite de defasagem, e leitura linearizável por ReadIndex quando pedida.
- Réplica de leitura (learner) que entra no cluster a partir de um backup físico mais o log, usada para análises e para tirar o backup do líder.
- **PITR:** um arquivo de lotes (journal) que registra cada lote confirmado, com um carimbo de tempo, em segmentos no MinIO. No cluster, os lotes vêm do log Raft. No nó único, de um gancho no commit do armazenamento. O formato é o mesmo nos dois modos. Os segmentos fecham em no máximo 30 segundos. Restaurar a um instante é: restore físico mais reaplicação dos lotes até o instante.
- Carimbo de tempo em todo lote por relógio híbrido (HLC, Hybrid Logical Clock), com as regras da seção "Carimbo de tempo dos lotes", preparando a ordem entre shards da E3.
- Seletor de instante na tela de backups do Enterprise.

**Teste de portão**
1. Group commit: com `AXON_STORAGE_SYNC=always` e 8 threads, pelo menos 60% da vazão do modo `batch`, em Linux.
2. Leituras em 3 nós com pelo menos 2,5 vezes a vazão de um nó.
3. Um nó novo entra num cluster de 10 GB em menos de 15 minutos sem pausar as escritas por mais de 1 segundo.
4. PITR: sob carga contínua, restaurar a um instante aleatório e comparar com o estado de referência registrado, com diferença zero; matar o nó com `kill -9` e perder no máximo 60 segundos de lotes arquivados; custo do journal abaixo de 5% da vazão.
5. Carimbo de tempo: os seis testes da seção "Carimbo de tempo dos lotes" verdes, e o custo do carimbo mais a chave de metadados abaixo de 2% da vazão de escrita.

**Versão:** 0.11.0.

### E3: vários shards (cerca de 5 a 7 semanas)

**Entregas**
- Vários grupos Raft com `SHARD BY`, um gateway sem estado e um plano de controle.
- Consulta em vários shards com `LIMIT` e agregação empurrados para os shards. Entre shards, a saga que já é nativa.
- **Backup de cluster com shards:** um manifesto de cluster com o ponto de corte de cada shard, tirado numa barreira, mais o backup físico e o journal de cada shard. O PITR entre shards usa o HLC da E2.

**Teste de portão:** 3 shards de 3 nós ingerem pelo menos 2,4 vezes um shard só; consultas entre shards batem com um nó único em testes diferenciais; restore de cluster de shards a um instante dá estado consistente entre shards.

**Versão:** 0.12.0.

### E4: dados no tempo e camada fria (cerca de 3 a 4 semanas)

**Entregas**
- Partição por tempo com retenção por partição (apagar uma partição custa O(1)), TTL (Time To Live) e camada fria no S3.
- Índice geográfico hierárquico com H3 (índice hexagonal hierárquico da Uber), no lugar da grade de geohash plana.
- Backups passam a referenciar os objetos da camada fria em vez de copiá-los, com bloqueio de retenção enquanto algum backup os referenciar.

**Teste de portão:** apagar uma partição de 1 milhão de linhas em tempo constante; TTL remove sem degradar a latência de escrita; busca geográfica com 10 milhões de pontos dentro da meta de latência definida na fase; restore com camada fria restaura sem copiar os objetos frios.

**Versão:** 0.13.0.

### E5: operação e resiliência (cerca de 2 a 3 semanas)

**Entregas**
- Prioridade por tipo de carga (QoS, Quality of Service): análises não degradam as transações.
- Atualização com um nó de cada vez entre versões novas. Da 0.10.x, só com os três nós parados.
- Testes de caos: matar o líder, partir a rede, matar durante backup e durante restore.
- Exercício de recuperação de desastre (DR, Disaster Recovery) automatizado: perda total do cluster, restaurar do MinIO e a um instante.

**Teste de portão:** matar o líder sob carga não perde nenhum dado confirmado (RPO 0) e elege outro em menos de 10 segundos; atualização sem erro visível ao cliente; carga analítica sem piorar o p99 transacional em mais de 20%; o exercício de DR repete a meta de 10 GB em menos de 10 minutos e o PITR.

**Versão:** 1.0.0, se todos os portões estiverem verdes.

## Carimbo de tempo dos lotes (HLC)

Hoje nenhum lote tem carimbo: o `CommittedBatch` guarda só o identificador da transação, as escritas, as remoções e o indicador de snapshot completo. O carimbo entra na E2.

### O que ele garante

1. **Monotonia:** carimbos crescem na ordem de commit, inclusive depois de troca de líder e de reinício.
2. **Hora legível:** fica perto do relógio de parede, para o usuário pedir "restaurar às 14:32:10".
3. **Causalidade entre shards (E3):** se B começa depois de observar o commit de A, o carimbo de B é maior.
4. **Independência de relógio perfeito:** os relógios das máquinas divergem e saltam, e o carimbo continua correto.

### Alternativas descartadas

| Opção | Por que não |
|---|---|
| Só o índice do Raft | Dá ordem exata dentro de um grupo, mas não responde "14:32" sem uma tabela de índice para hora, e não ordena entre shards |
| Hora de parede do líder | Um salto do relógio para trás ou uma troca de líder com relógios desalinhados deixa os carimbos fora de ordem, e "o último lote antes de T" fica ambíguo |
| Gravar hora e índice agora e o HLC só na E3 | Menos código agora, mas muda o formato duas vezes e deixa o PITR sujeito ao relógio de cada líder |

### Regras

- O carimbo é um par **(milissegundos físicos, contador lógico)**, comparado nessa ordem.
- **Ao emitir:** `físico' = max(físico anterior, relógio local)`. Se o físico não avançou, o contador incrementa. Se avançou, o contador volta a 0. Assim o carimbo nunca recua, mesmo que o relógio local recue.
- **Ao receber um lote** (seguidor aplicando, ou mensagem entre nós e shards): o HLC local vira o `max` entre o seu valor e o do lote. Quando um seguidor vira líder, já viu todos os carimbos anteriores e nunca emite um menor.
- **Quem estampa:** no cluster, o líder, ao propor o lote. No nó único, o próprio commit.
- **Persistência:** o maior carimbo emitido é gravado numa chave de metadados (`!hlc`) no mesmo lote atômico de escrita do RocksDB. No reinício, `HLC = max(relógio, persistido + 1)`. Custo: uma chave pequena por commit e 12 bytes por lote.
- **Ordem no nó único:** o carimbo é emitido depois de pegar as travas por faixa de chave e antes de gravar. Dois commits que mexem na mesma chave se excluem pelas travas, então o carimbo do segundo é maior. Commits sem chaves em comum podem sair em qualquer ordem entre si, porque comutam.
- **Por que o replay converge:** os lotes são absolutos (`put` e `delete` com o valor final), logo idempotentes. Se o backup físico já contém um lote com carimbo maior que o seu ponto de partida, reaplicar esse lote não estraga nada. Reaplicados em ordem de carimbo, o último lote de cada chave prevalece, que é o estado verdadeiro.

### Como o PITR usa o carimbo

1. O usuário escolhe T (hora de parede).
2. Cada segmento do journal registra o primeiro e o último carimbo e o primeiro e o último índice.
3. Uma busca binária nesse índice acha o último lote com carimbo menor ou igual a (T, infinito).
4. O restore físico mais o replay até esse lote dá o estado.

A precisão é de milissegundos. Empates no mesmo milissegundo se desfazem pelo contador e pelo índice.

### Entre shards (E3)

O HLC viaja em toda mensagem entre gateway e shards, com a regra de recebimento acima. Restaurar a T faz cada shard reaplicar até T, e o resultado é consistente para tudo que é causalmente relacionado. Uma saga não é atômica entre shards, então um corte em T pode cair no meio dela. Isso é inerente ao modelo e será dito na documentação.

### Limites

- **Não é um relógio global verdadeiro.** O instante T é o do relógio do líder, então a exatidão em relação à hora real é a do NTP (Network Time Protocol) do líder: de milissegundos a dezenas de milissegundos com `chrony`, mais se ele estiver ruim. Pedir "ao segundo" é seguro. "Ao milissegundo" depende da qualidade do relógio.
- **Salto grande de relógio:** o HLC preserva a monotonia, mas o T pedido pode deixar de corresponder à hora real.
- **Limite de desvio (`maxDrift`, padrão 500 ms, sem configuração obrigatória):** um nó rejeita um lote cujo carimbo físico passe do seu relógio local mais 500 ms e emite alerta. Isso impede que um relógio adiantado puxe o cluster para o futuro. O desvio entre nós entra nas métricas.

### Compatibilidade de formato

O `RaftPayload` escreve o indicador de snapshot por último, e o decodificador só o lê se ainda houver bytes. Basta acrescentar `(físico: long, lógico: int)` depois dele.

- **Nó antigo lendo entrada nova:** ignora os bytes finais, então o formato é lido nos dois sentidos. Isso não torna suportada a atualização um nó de cada vez a partir da 0.10.x, que é só com os três nós parados (veja [operacao-e-resiliencia.md](operacao-e-resiliencia.md#atualização-um-nó-de-cada-vez)).
- **Nó novo lendo entrada antiga (0.9.x):** não encontra carimbo e trata como "sem carimbo". O PITR só alcança instantes a partir do primeiro lote estampado, e isso será documentado.
- **O log Raft em arquivo** não muda, porque o carimbo vive dentro do payload. O journal de PITR é um formato novo, com versão no cabeçalho.

### Testes de portão do carimbo

1. Relógio falso que salta para trás e para frente, com 1 milhão de commits: carimbos estritamente crescentes.
2. Troca de líder com relógios com desvio de 300 ms para mais e para menos: a ordem de índice coincide com a ordem de carimbo.
3. Reinício do nó: o primeiro carimbo novo é maior que o último persistido.
4. Desvio acima do limite: lote rejeitado e alerta emitido.
5. PITR diferencial: um modelo de referência registra o estado a cada instante, e a restauração a um instante aleatório dá diferença zero.
6. Compatibilidade: lotes sem carimbo (0.9.x) são lidos sem erro, e um nó antigo lê entradas novas.

## Ordem e dependências

1. E0 e E1.0 podem começar já, em paralelo (uma é de teste e CI, a outra de backup).
2. E1.1 depende da E1.0. E1.2 é independente e pode andar em paralelo.
3. E2 depende da E1.1: o learner e o PITR usam o backup físico como base.
4. E3 depende da E2 (journal, HLC e learner).
5. A parte de TTL e partição por tempo da E4 pode começar depois da E1. O restante da E4 e a E5 vêm depois da E3.

Estimativa total: 14 a 19 semanas de trabalho contínuo, com incerteza maior nas fases E3 e E4.

## Riscos

- **RTO de 10 GB:** depende da vazão do MinIO e do disco. Se o portão da E1.1 falhar, as alternativas são download paralelo por partes e restaurar de um checkpoint local mantido ao lado do backup.
- **Custo do journal:** o registro dos lotes não pode bloquear o commit. Fica em fila assíncrona com limite, e o portão da E2 mede o custo.
- **Carimbo de tempo no lote muda o formato do log:** os bytes finais são ignorados por nós antigos, e lotes antigos são lidos como "sem carimbo". O PITR só alcança instantes a partir do primeiro lote estampado depois da atualização, então vale tirar um backup físico logo após subir a E2.
- **Relógio ruim em um nó:** o limite de desvio de 500 ms rejeita o lote e alerta, mas um líder com relógio errado ainda produz instantes de PITR errados em relação à hora real. Mitigação: métrica do desvio entre nós e recomendação de `chrony` na documentação de operação.
- **Custo do carimbo no caminho de commit:** a chave `!hlc` no mesmo lote atômico acrescenta uma escrita pequena. O portão da E2 reprova acima de 2% da vazão de escrita.
- **Ruído do CI:** executores compartilhados variam. Por isso as metas do portão da E0 são razões entre testes e comparação com a linha de base, e não valores absolutos.

## Decisões

**Já aprovadas**

1. Usar o `BackupEngine` do RocksDB para o backup físico, em vez de um formato incremental próprio.
2. O PITR vale também para o nó único, com o journal alimentado pelo commit do armazenamento.
3. Segmentos do journal fechando em no máximo 30 segundos (a meta é abaixo de 1 minuto).
5. Mapeamento de versões: E1 em 0.10.0, E2 em 0.11.0, E3 em 0.12.0, E4 em 0.13.0 e E5 em 1.0.0.
6. Começar já pela E0 e pela E1.0, que corrigem a perda de dados do restore de incremental.

**Escolhida, a confirmar na aprovação do plano**

4. Carimbo de tempo por relógio híbrido (HLC), com os parâmetros abaixo.

| Parâmetro | Valor proposto |
|---|---|
| Representação | milissegundos físicos (`long`) mais contador lógico (`int`), 12 bytes por lote, no fim do payload |
| Persistência | chave `!hlc` no mesmo lote atômico de escrita |
| Desvio máximo aceito (`maxDrift`) | 500 ms, sem configuração obrigatória |
| Lotes sem carimbo (0.9.x) | lidos sem erro, tratados como "sem carimbo" |
| Instantes alcançáveis pelo PITR | a partir do primeiro lote estampado |
| Corte entre shards | por carimbo, consistente para o que é causalmente relacionado, e uma saga pode ser cortada no meio |

**Aprovação do plano:** ao aprovar esta versão, a E0 e a E1.0 começam em paralelo. O HLC só entra no código na E2.
