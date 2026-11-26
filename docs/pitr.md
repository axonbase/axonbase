# Restauração a um instante (PITR)

A restauração a um instante, ou PITR (Point-In-Time Recovery), devolve a base ao estado que ela tinha numa hora escolhida. O motor combina duas peças: um [backup físico](backup-fisico.md) tirado antes do instante e o **journal**, um arquivo que registra cada lote confirmado com um carimbo de tempo. Restaurar a um instante é restaurar o backup e reaplicar os lotes do journal até lá.

Vale para nó único com armazenamento RocksDB (a base de dados embutida do AxonBase). Em cluster esta API (Application Programming Interface) fica desligada: ela responde 409 a quem tenta restaurar, importar ou apagar segmentos. A restauração de um cluster é a do [backup de cluster com shards](shards.md#backup-do-cluster-e-restauração-a-um-instante).

## Ligar

O journal vem **desligado** por padrão. Ligá-lo custa vazão de escrita, de cerca de 4% a 17% conforme a quantidade de escritores e o caminho (veja "Medidas"). Todas as variáveis são opcionais.

| Variável | Padrão | O que faz |
|---|---|---|
| `AXON_PITR` | desligado | `on` ou `true` liga o journal. |
| `AXON_PITR_DIR` | `<dados>/.pitr-journal` | Diretório dos segmentos. Dentro do diretório de dados, o nome precisa começar com ponto (senão o restore o moveria, e o motor recusa subir). |
| `AXON_PITR_RETENTION_DAYS` | 7 | Por quantos dias os segmentos locais ficam, de 1 a 35. |
| `AXON_PITR_SEGMENT_SECONDS` | 25 | Idade máxima de um segmento aberto, de 1 a 30 segundos. |
| `AXON_HLC_MAX_DRIFT_MS` | 500 | Desvio máximo aceito no carimbo recebido de outro nó (veja abaixo). |

Tire um backup físico logo depois de ligar o journal: o PITR só alcança instantes a partir de um backup que já tenha carimbo.

## O carimbo de tempo

Todo lote confirmado recebe um carimbo de um relógio híbrido, o HLC (Hybrid Logical Clock): milissegundos físicos e um contador lógico, comparados nessa ordem. Junto vai um **índice** sem buracos, na mesma ordem dos carimbos.

- **Emissão:** o físico é o maior entre o anterior e o relógio local. Se ele não avançou, o contador sobe. O carimbo nunca recua, mesmo que o relógio recue.
- **Persistência por teto:** o motor não grava cada carimbo. Ele grava na chave `!hlc`, fora do alcance das consultas, um **teto**: o maior milissegundo e o maior índice que podem ser usados. O teto é gravado e sincronizado em disco (nos três modos de `AXON_STORAGE_SYNC`) **antes** de qualquer carimbo ou índice acima dele ser usado. Se a gravação falhar, o commit que precisava dele falha e nada é escrito.
- **Folga e renovação:** o teto vai à frente do relógio em metade do desvio máximo, entre 50 e 250 ms (250 ms no padrão), e reserva um milhão de índices. Passada a metade da folga, um commit o renova depois de soltar as travas dele, sem parar os outros. Sem escritas, nada é gravado. Sob carga são cerca de 8 gravações por segundo.
- **Reinício:** o relógio começa em `max(relógio, teto + 1)` e o índice continua acima do teto. Depois de uma queda, os carimbos novos podem ficar até a folga (250 ms) à frente do relógio. Um fechamento limpo aperta o teto ao último valor exato, sem salto.
- **Posição do conteúdo:** durante um backup físico, e só durante ele, cada commit grava a própria posição junto do lote (chaves `!hlc/...`). Assim a base restaurada sabe exatamente até que carimbo vai. Numa base que caiu sem cópia em curso, vale o teto, que nunca fica abaixo do conteúdo.
- **Troca de base:** antes de um restore em processo, a base preparada recebe o teto atual e perde a marca de desligamento limpo. Uma queda em qualquer ponto da troca reabre acima de tudo que já foi usado, em outra linha do tempo.
- **Compatibilidade:** no cluster o carimbo viaja no fim do payload de replicação (12 bytes). Um nó antigo ignora os bytes a mais, e um lote antigo é lido como "sem carimbo".
- **Linha do tempo:** cada história contínua da base tem um identificador. Ele só continua o mesmo quando a abertura prova que nada foi gravado depois de um desligamento limpo. Uma queda, uma troca de base ou escritas de uma versão sem carimbo criam outra linha do tempo, e o journal de uma nunca é aplicado sobre o backup de outra.
- **Cluster:** o líder carimba cada lote ao propô-lo no log do Raft, quem recebe adota o carimbo e, no arranque, o relógio parte da última entrada do log. O lote de um snapshot também é carimbado. A ordem dos índices do log é a ordem dos carimbos, inclusive depois de uma troca de líder.
- **Desvio entre nós (cluster):** um carimbo recebido que passe do relógio local mais o desvio máximo é rejeitado, com alerta no log e a métrica `axe_hlc_rejected_total`, e o líder reenvia o lote. O desvio máximo é de **500 ms**. Dois nós em lados opostos (um 300 ms adiantado e outro 300 ms atrasado, 600 ms entre si) passam dele: sob um líder adiantado, o atrasado acompanha com pelo menos 100 ms de atraso, e a escrita que depender dele para o quórum espera esse tempo.
- **Sincronização dos relógios:** sincronize todos os nós por NTP (Network Time Protocol) com o `chrony` e monitore o desvio (`axe_hlc_drift_max_seconds`). O limite de 500 ms pressupõe relógios sincronizados, e a hora pedida numa restauração só é tão exata quanto o relógio de quem estampou.

## O journal

Cada lote confirmado entra numa fila e uma thread própria o grava. **O commit nunca espera pelo journal.** Dentro do commit o journal serializa o próprio lote (quem chamou pode reaproveitar os mapas e os vetores) e o encadeia numa fila sem trava. A thread de escrita só junta os bytes prontos num buffer reaproveitado: assim ela trabalha o mínimo.

- Um segmento fecha ao atingir 25 segundos (configurável até 30) ou 64 MB. Parado, o motor grava um segmento vazio por minuto, que só avança a marca d'água (até onde se sabe que não falta nada).
- Segmentos fechados são imutáveis. O nome (`seg-<milissegundos>-<contador>.axj`) é único e crescente, então quem copia para um armazenamento de objetos pode pular o que já enviou.
- Cada registro tem CRC (Cyclic Redundancy Check, uma soma de verificação), e o rodapé guarda o primeiro e o último carimbo e índice, a marca d'água e os índices que faltam.
- Os bytes vão ao sistema operacional a cada rodada de escrita e são sincronizados em disco a cada segundo. Um `kill -9` perde só o que ainda estava na fila. Uma queda de energia pode perder até cerca de um segundo do segmento aberto.
- Se a escrita em disco falhar (disco cheio, por exemplo), os lotes atingidos contam como descartados e o journal volta a gravar quando o disco voltar.
- Na abertura, um segmento que ficou aberto numa queda é cortado no último lote inteiro e fechado como `recovered`.
- Com a fila cheia (50 mil lotes, ou um oitavo do heap até 128 MB), o lote é descartado e contado em `axe_pitr_dropped_batches_total`.

O formato é próprio e leva a versão no cabeçalho. As chaves usam tamanho de 32 bits, sem o limite de 64 KB do formato de replicação.

## Até onde dá para restaurar

A partir de um backup, o motor segue o índice sem falhas no journal, dentro da mesma linha do tempo. A janela de cada backup começa no instante dele e vai até o último milissegundo inteiro coberto. Sob escrita contínua, o backup contém lotes um pouco posteriores ao início anunciado: um instante muito próximo do começo pode responder `PITR_BASE_TOO_NEW`, e a saída é usar o backup anterior. O mesmo vale, por até 250 ms, num backup tirado logo depois de uma queda e antes de qualquer escrita.

A restauração **nunca** pula um lote nem mistura histórias. A janela para quando:

- a fila encheu, ou o disco falhou, e um lote foi descartado;
- o processo caiu (os instantes até a queda continuam disponíveis, e os posteriores pedem um backup novo);
- a base foi trocada por um restore (a história antiga continua disponível para instantes anteriores, e a nova começa num backup tirado depois);
- a base voltou no tempo por outro caminho, ou uma versão sem carimbo gravou nela (volta para a versão anterior);
- um segmento foi apagado ou está ilegível.

Em todos os casos, **um backup físico novo retoma a cobertura**. Depois de um restore, de uma queda ou de um aumento de descartes, tire um backup.

## Restaurar

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8000/admin/v1/backups/physical/3/restore?confirm=true&at=2026-11-26T14:32:10Z"
```

`at` é um instante no padrão ISO (International Organization for Standardization) 8601 de data e hora, com milissegundos e fuso opcionais. Valem todos os lotes com carimbo até aquele milissegundo, inclusive. O motor sela o segmento aberto, prepara a base em `<dados>/.restoring` (backup e depois os lotes), confere, e só então faz a mesma troca em processo do restore físico. Qualquer recusa deixa a base como estava.

```json
{
  "restored": true,
  "id": 3,
  "version": 5821,
  "previous": ".replaced-1795703530000",
  "at": "2026-11-26T14:32:10Z",
  "appliedBatches": 18422,
  "restoredUntil": "2026-11-26T14:32:09.998Z"
}
```

Com `source=<origem>`, o backup e os segmentos vêm da área de importação (veja "Recuperar em outro motor").

| Status | Código | Quando |
|---|---|---|
| 400 | `BAD_REQUEST` | `confirm` ausente ou `at` em formato inválido. |
| 404 | `NOT_FOUND` | Backup ou origem inexistente. |
| 409 | `PITR_DISABLED` | Journal desligado (e sem `source`). |
| 409 | `PITR_NO_BASE_STAMP` | Backup anterior à 0.11, sem carimbo. |
| 409 | `PITR_BASE_TOO_NEW` | O backup já contém escritas posteriores ao instante: escolha um mais antigo. |
| 409 | `PITR_UNREACHABLE` | O journal não cobre o instante a partir desse backup. O campo `latest` traz o último alcançável. |
| 409 | `PITR_BAD_JOURNAL` | Um segmento está corrompido. |
| 409 | `BUSY` | A troca não conseguiu esvaziar as operações em 60 s. |

## Rotas do journal

Todas ficam sob `/admin/v1/backups/journal`. Consultar, ler e selar pedem o papel de operador. Gravar e apagar pedem o de administrador.

| Rota | O que faz |
|---|---|
| `GET /journal[?source=]` | `{enabled, droppedBatches, writeErrors, pendingBatches, segments:[...]}`. |
| `GET /journal/windows[?source=]` | `{windows:[{backupId, from, to}]}`, calculado sobre os segmentos fechados. `to` nulo: nada coberto depois do backup. É o que alimenta um seletor de instante. |
| `POST /journal/seal` | Fecha o segmento aberto agora e o devolve. |
| `GET /journal/segments/{nome}[?source=]` | O arquivo, em fluxo. `X-Axon-Size` traz o tamanho original. |
| `PUT /journal/segments/{nome}?source=` | Grava na área de importação, depois de validar todos os checksums. Gravação atômica. |
| `DELETE /journal/segments/{nome}[?source=]` | Apaga um segmento. |
| `DELETE /journal/imports/{origem}` | Apaga a área de importação de segmentos da origem. |

Cada segmento aparece assim:

```json
{
  "name": "seg-001795703500000-0000000000.axj",
  "sizeBytes": 183220,
  "batches": 412,
  "firstAt": "2026-11-26T14:31:40.012Z",
  "lastAt": "2026-11-26T14:32:04.870Z",
  "firstIndex": 90113,
  "lastIndex": 90524,
  "completeAt": "2026-11-26T14:32:05.001Z",
  "completeIndex": 90524,
  "recovered": false
}
```

`firstAt` e `lastAt` são nulos num segmento vazio.

A transferência segue o mesmo estilo dos arquivos do backup físico: com `AXON_S3_ENCRYPT_KEY` definida, o corpo sai cifrado em blocos com AES-GCM (Advanced Encryption Standard em modo Galois/Counter), pela classe `StreamEncryption`, e o motor decifra na gravação.

A lista de backups (`GET /admin/v1/backups/physical`) traz `stampedAt` e `journalIndex` nos backups que servem de base.

## Requisito para o Enterprise: backup físico automático

Depois de uma **queda** do motor ou de uma **restauração** (física ou a um instante), o Enterprise **deve disparar sozinho um backup físico novo** (`POST /admin/v1/backups/physical`), sem esperar a próxima execução da política. Sem esse backup, nenhum instante posterior ao evento volta a ser alcançável.

- **Restauração:** quem a pediu é o próprio Enterprise. Assim que ela responder 200, ele dispara o backup.
- **Queda:** a API não anuncia a queda, mas ela aparece em `GET /journal/windows`: o `to` do backup mais novo para de andar. Com o journal ligado e o motor no ar, esse `to` fica a no máximo cerca de 1 minuto do presente, mesmo sem escritas. Se, passados 3 minutos do backup mais novo, o `to` dele for nulo ou estiver mais de 3 minutos atrás, ou se nenhum backup aparecer na lista, o Enterprise dispara um backup. A mesma regra cobre lotes descartados, falha de disco, segmento apagado e volta de versão.
- **Descartes:** um aumento de `droppedBatches` permite disparar o backup sem esperar os 3 minutos.
- Um backup que falhar é repetido, e a falha vira alerta, porque a janela do PITR fica parada enquanto isso.

## Fluxo para um agente externo

1. A cada 30 segundos, `GET /journal` e envio ao armazenamento de objetos, compatível com S3 (Simple Storage Service), dos segmentos que ainda não estão lá. O ponto mais recente guardado fora do servidor é o `completeAt` do último segmento enviado, e a distância dele até agora é o RPO (Recovery Point Objective, a perda tolerada) do PITR.
2. Retenção no armazenamento de objetos: guarde os segmentos desde o backup mais antigo que ainda se queira usar.
3. Cumpra o requisito de backup físico automático descrito acima.

### Recuperar em outro motor

1. Grave os arquivos do backup com `PUT /physical/files?path=...&source=<origem>`, deixando `meta/<id>` por último.
2. Grave os segmentos com `PUT /journal/segments/{nome}?source=<origem>`, todos os posteriores ao backup.
3. `GET /journal/windows?source=<origem>` mostra o intervalo alcançável.
4. `POST /physical/{id}/restore?confirm=true&source=<origem>&at=<instante>`.
5. Apague as áreas: `DELETE /physical/imports/<origem>` e `DELETE /journal/imports/<origem>`. Tire um backup físico no motor novo.

## Métricas

| Métrica | Significado |
|---|---|
| `axe_pitr_enabled` | 1 com o journal ligado. |
| `axe_pitr_dropped_batches_total` | Lotes descartados por fila cheia. Qualquer aumento pede um backup novo. |
| `axe_pitr_write_errors_total` | Falhas de escrita em disco. |
| `axe_pitr_pending_batches` | Lotes na fila. |
| `axe_pitr_last_seal_timestamp_seconds` | Quando o último segmento fechou. |
| `axe_hlc_rejected_total` | Carimbos rejeitados por desvio. |
| `axe_hlc_drift_max_seconds` | Maior adiantamento visto num carimbo recebido. |

## Limites

- O instante é o do relógio do servidor: a exatidão em relação à hora real é a do NTP dele. Pedir ao segundo é seguro.
- Um salto grande de relógio não quebra a ordem dos carimbos, mas faz o instante pedido deixar de corresponder à hora real.
- Num cluster, o PITR existe pelo backup de cluster com shards (inclusive com um shard só), descrito em [shards.md](shards.md#backup-do-cluster-e-restauração-a-um-instante). A API desta página continua só para o nó único.
- O `kill -9` não perde dado confirmado da base, mas encerra a linha do tempo: os instantes posteriores à queda pedem um backup novo. O desligamento normal do servidor não encerra.

## Medidas

Todas numa máquina dedicada do GCP (Google Cloud Platform), 8 vCPUs e disco NVMe (Non-Volatile Memory Express) local.

### Portões de correção

| Portão | Como medir | Resultado em 26/11/2026 | Meta |
|---|---|---|---|
| Relógio saltando para trás e para frente, 1 milhão de commits em 8 threads | `PitrGateTest` | ordem de índice igual à ordem de carimbo | estritamente crescentes |
| Restauração a instantes aleatórios contra um modelo de referência | `PitrRestoreTest` | diferença zero | diferença zero |
| `kill -9` sob carga, 8 rodadas | `PitrGateTest` | pior perda de 23 ms (661 commits) fora do journal; restauração consistente em todas | no máximo 60 s de lotes |
| Teto: reinício, relógio que salta para trás, queda entre gravar o teto e usá-lo, nos três modos de sincronização | `RocksDbBackendCeilingTest`, `CeilingRecordTest`, `CommitSequencerTest` | aprovado na suíte completa | carimbo nunca repete nem recua |
| Troca de líder no cluster com relógios 300 ms adiantado e atrasado | `ClusterClockTest` | aprovado na suíte completa | ordem de carimbo igual à ordem do log |

```bash
mvn -q -pl axonbase-core -am test -Daxon.gate.pitr=true -Dtest=PitrGateTest -Dsurefire.failIfNoSpecifiedTests=false
```

### Portões de custo

Commit `03786bc`, contra a 0.10.1, mediana de pares de rodadas (valor negativo quer dizer mais rápido que a referência). O custo do journal é medido sobre o motor já com o carimbo.

| Escritores | Caminho | Carimbo, meta abaixo de 2% | Custo do journal ligado |
|---|---|---|---|
| 64 | pelo motor (`CREATE`) | -0,7% | **4,3%** (é o portão: abaixo de 5%) |
| 64 | direto no armazenamento | -0,9% | 6,7% |
| 8 | pelo motor (`CREATE`) | -1,2% | 14,9% |
| 8 | direto no armazenamento | -1,5% | 16,7% |

- **Carimbo: aprovado** nas quatro medidas. A primeira versão, que gravava a chave `!hlc` em todo commit, custava de 9% a 24%.
- **Journal: aprovado.** Por decisão do dono em 26/11/2026, o portão do journal é medido com 64 escritores pelo motor, com custo abaixo de 5%. As outras três medidas não são portão: servem para saber o que esperar.
- **O journal segue desligado por padrão.** Com poucos escritores simultâneos (8 numa máquina de 8 vCPUs), ligá-lo custa cerca de 15% da vazão de escrita pelo motor e 17% gravando direto no armazenamento. Quem precisa do PITR liga com `AXON_PITR=on` sabendo desse custo.

O que se sabe sobre o custo do journal com 8 escritores, por variantes da medida:

- disco e sincronização não são a causa: o journal em memória custa quase o mesmo;
- só o trabalho feito dentro do commit custa 2,7% direto no armazenamento e 6,0% pelo motor, então com 8 escritores o custo pelo motor não ficaria abaixo de 5% nem sem escrever o segmento;
- serializar o lote dentro do commit, e não na thread de escrita, reduziu o custo pelo motor de 21,7% para 14,9% e não mudou o caminho direto;
- com o journal, as threads de commit ficam mais tempo paradas (a CPU ociosa vai de 7% para 17% no caminho direto), e ainda não se sabe onde;
- a thread de escrita dormindo 50 ms em vez de 10 ms baixa o custo para 13,7% e 11,4%. Esse ajuste não é o padrão.

Para o custo e as variantes (`WriteCostBench`, nos testes do `axonbase-core`), e para o perfil, sempre numa máquina dedicada:

```bash
scripts/bench-pitr.py --base "<classes da 0.10.1>:<rocksdbjni.jar>" --new "<classes atuais e de teste>:<rocksdbjni.jar>" --rounds 5 --seconds 10 --extra copy-only,journal-tmpfs,journal-pause50ms
```

```bash
scripts/profile-pitr.py --base "<classes da 0.10.1>:<rocksdbjni.jar>" --new "<classes atuais e de teste>:<rocksdbjni.jar>" --out perfil
```
