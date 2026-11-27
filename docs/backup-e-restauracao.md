# Backup e restauração

O AxonBase exporta e restaura o estado do motor em um arquivo texto, o `.axbak`. Este documento descreve o formato v2 (o atual), as regras de cadeia que o restore aplica, o que ele garante, o formato v1 legado e os códigos de erro.

## Visão geral

- A API usa HTTP (HyperText Transfer Protocol), com `GET /admin/v1/backups/export?type=full|incremental&since=N` gera o backup (papel mínimo: operador).
- `POST /admin/v1/backups/restore?confirm=true[&force=true]` restaura (papel mínimo: administrador).
- O export lê o motor a partir de um único ponto no tempo (um snapshot do RocksDB). Um statement que grava registro e índices numa operação atômica aparece inteiro ou não aparece no backup.
- Se `AXON_S3_ENCRYPT_KEY` estiver definida, o corpo sai cifrado em blocos (AES-GCM, Advanced Encryption Standard com Galois/Counter Mode). A cifra fica por fora do formato: o restore decifra primeiro e valida o texto claro depois.

## Formato v2

Um arquivo é um ou mais segmentos concatenados. Cada segmento tem esta forma:

```
--axonbase-backup-v2--
!meta<TAB>base64(JSON)
ctrl<TAB>base64(...)
<chave><TAB>base64(valor)
!del<TAB>base64(chave)
!end<TAB>base64(JSON)
```

- `!meta` traz um JSON (JavaScript Object Notation) como `{"format":2,"type":"full"|"incremental","since":N,"version":N,"createdAt":"...","nodeId":"...","engineVersion":"..."}`. No completo, `since` é 0. `version` é a marca d'água: o próximo incremental deve partir dela.
- `ctrl` é o snapshot do plano de controle (definições e identidades).
- As linhas `<chave><TAB>base64(valor)` são as gravações. As linhas `!del` (só no incremental) são as chaves apagadas depois de `since`.
- `!end` traz `{"puts":N,"deletes":M,"sha256":"hex"}`. O SHA-256 (Secure Hash Algorithm de 256 bits) cobre todos os bytes do segmento, do início da linha de cabeçalho até a quebra de linha da última linha de dados, imediatamente antes da linha `!end`.

As chaves das gravações são escritas com escape: `\\` vira `\\\\`, a quebra de linha vira `\\n`, o retorno de carro vira `\\r` e a tabulação vira `\\t`. Isso é necessário porque as chaves do índice columnar carregam bytes de números codificados, e alguns desses bytes são quebra de linha ou tabulação, o que quebraria o formato de uma linha por chave. O restore desfaz o escape. Em um banco real com índice columnar (77 mil chaves), 36 chaves tinham esses caracteres.

A marca d'água é lida antes de o snapshot ser fixado. Por isso o snapshot pode conter algumas gravações com versão acima dela; o incremental seguinte as inclui de novo, o que é inofensivo porque aplicar a mesma gravação duas vezes dá o mesmo resultado. O que não acontece é uma gravação ficar de fora de todos os segmentos.

## Regras do restore

O restore trabalha em duas passadas sobre o arquivo temporário com o corpo já decifrado.

**Passo 1, sem tocar na base.** Valida cada segmento (cabeçalho, `!meta`, contagens, SHA-256, truncamento, linha acima do limite de 8 MiB) e a continuidade:

1. o primeiro segmento tem que ser `full`;
2. cada segmento seguinte tem que ser `incremental`, com `since` igual ao `version` do segmento anterior.

Qualquer violação devolve erro e a base fica intacta.

**Passo 2.** Só então limpa a base e aplica os segmentos em ordem (gravações e `!del`). O `ctrl` do último segmento prevalece. A resposta é:

```json
{"restored":true,"restoredKeys":1234,"segments":3,"version":5821,"type":"chain"}
```

`type` é `full` (um segmento completo), `chain` (completo mais incrementais) ou `legacy` (arquivo v1). `version` é a marca d'água final.

Restaurar só um incremental é recusado: um incremental sozinho apagaria a base e deixaria apenas o delta.

## Formato v1 (legado)

Arquivos com o cabeçalho `--axonbase-backup-v1--` continuam aceitos e são tratados como completos. Atenção: o formato v1 não escapava as chaves, e o restore v1 pulava em silêncio qualquer linha sem tabulação, então as entradas de índice columnar cujas chaves continham quebra de linha ou tabulação eram perdidas sem aviso. Prefira sempre backups v2. O risco: não têm manifesto, contagem nem checksum. Um arquivo v1 truncado ou adulterado só é detectado se quebrar a leitura, e um incremental v1 restaurado sozinho apaga a base e deixa o delta (perda silenciosa de dados). Full e incrementais v1 só restauram certo se concatenados na ordem. Prefira o v2.

## Códigos de erro

| Código | Status HTTP | Quando |
|---|---|---|
| `BACKUP_CHAIN_INVALID` | 409 | primeiro segmento não é completo, ou `since` não bate com a versão do segmento anterior |
| `BACKUP_CHECKSUM` | 400 | SHA-256 ou contagens do segmento não conferem (arquivo alterado ou corrompido) |
| `BACKUP_TRUNCATED` | 400 | o arquivo termina antes da linha `!end` |
| `BACKUP_INVALID` | 400 | cabeçalho, manifesto ou linha inválidos |
| `BACKUP_SINCE_AHEAD` | 409 | no export, `since` é maior que a marca d'água atual do motor (linhagem diferente, por exemplo um motor restaurado em base nova): faça um backup completo |
| `BAD_REQUEST` | 400 | `confirm` ausente, corpo que não é backup, senha de cifra errada, linha gigante |

## Exemplos

```bash
# completo
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/admin/v1/backups/export?type=full" -D full.hdr -o full.axbak
grep -i x-axon-backup full.hdr    # Type, Version e Format: 2

# incremental a partir da versão do completo
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/admin/v1/backups/export?type=incremental&since=5821" -o inc1.axbak

# restaurar a cadeia: basta concatenar os segmentos na ordem
cat full.axbak inc1.axbak > cadeia.axbak
curl -s -X POST -H "Authorization: Bearer $TOKEN" --data-binary @cadeia.axbak \
  "http://localhost:8080/admin/v1/backups/restore?confirm=true&force=true"
```

Com o corpo cifrado, os segmentos são cifrados um a um por fora; para montar uma cadeia, decifre, concatene e cifre de novo, ou restaure cada arquivo na ordem pelo orquestrador (o Enterprise monta a cadeia a partir do manifesto).

## Restauração do S3 na subida

Com `AXON_S3_BUCKET` e `AXON_S3_ENDPOINT` configurados, o servidor também pede a restauração: se a base local estiver vazia, ele a refaz a partir dos backups do balde no S3 (Simple Storage Service) antes de atender.

| Situação com o S3 configurado | O que o servidor faz |
|---|---|
| base com dados | sobe com ela, sem consultar o balde para restaurar |
| base vazia e balde sem nenhum backup | sobe com os bancos iniciais (primeira subida) |
| base vazia e restauração concluída | sobe com os dados restaurados |
| base vazia e falha ao listar, baixar, decifrar ou aplicar, inclusive por objeto frio sem a camada fria | **não sobe**: mostra a causa e sai com o código 2 |
| base deixada pela metade por uma restauração que parou no meio | **não sobe**: apague o diretório de dados e suba de novo |

Sem o S3 configurado nada disso se aplica. Num cluster, um nó com a base vazia e o S3 configurado precisa alcançar o balde para subir.

**Diferença para a 0.10.x:** nela, qualquer falha era tratada como balde vazio e o servidor subia com a base vazia (ou, depois de uma falha no meio, com a base pela metade no reinício seguinte), e o backup periódico passava a enviar esse estado. Quem subia com o S3 inacessível, com o balde ainda não criado ou com a senha de cifra errada agora precisa corrigir a causa, ou remover as duas variáveis para subir sem o S3.

A restauração pedida pela API administrativa, com o servidor no ar, continua devolvendo erro sem derrubar o processo. Se ela parar no meio, o próximo início com o S3 configurado é recusado. A marca vale para os dois formatos de arquivo, o antigo e o `.axbak` v2. Enquanto a base estiver pela metade, o backup para o S3 (periódico, manual ou do desligamento) falha com essa causa e nada é enviado ao balde. Restaurar de novo um backup bom, com sucesso, libera os backups.

## O que não está neste formato

- Backup físico (arquivos do RocksDB) e restore com troca atômica de diretório ficam para a etapa seguinte do plano.
- Os métodos em `String` usados no cluster (`backupCluster`, `backupIncremental`) continuam no formato v1 e não ganharam manifesto nem checksum.
