# Backup físico

O backup físico copia os arquivos do RocksDB (a base de dados embutida do AxonBase) com o `BackupEngine`. Diferente do backup lógico em texto (o `.axbak`, descrito em [backup-e-restauracao.md](backup-e-restauracao.md)), ele não relê registro por registro: copia arquivos SST (Sorted String Table, os arquivos de dados do RocksDB) e o log de escrita de um instante consistente. O backup seguinte reaproveita os arquivos que já foram copiados e só paga pelo que mudou, e qualquer backup restaura sozinho, sem montar cadeia.

Vale só para nó único com armazenamento RocksDB. Em cluster ou com armazenamento em memória a API responde 409.

## Onde ficam os backups

O diretório é `AXON_BACKUP_DIR` (ou a propriedade `axon.backup.dir`). Sem isso, fica em `<dados>/.physical-backups`, dentro do mesmo volume de dados e ignorado pelo RocksDB. Esse diretório é do `BackupEngine`: não escreva nele por fora.

```
<AXON_BACKUP_DIR>/
  meta/<id>                 manifesto do backup <id> (grava por último: é o marcador de "completo")
  private/<id>/...          arquivos que só o backup <id> usa (MANIFEST, OPTIONS, CURRENT, log)
  shared_checksum/<nome>    arquivos SST compartilhados entre backups; o nome inclui o checksum
  imports/<origem>/...      área de importação (veja "Restaurar um backup vindo de fora")
```

Um arquivo de `shared_checksum/` tem nome com o checksum e o tamanho, então um nome igual sempre tem o mesmo conteúdo: quem envia ao armazenamento de objetos pode pular o que já enviou.

## Rotas de gestão

Todas ficam sob `/admin/v1/backups/physical`. Criar, listar e verificar pedem o papel de operador. Restaurar, reter e apagar importações pedem o de administrador.

| Rota | O que faz |
|---|---|
| `POST /physical` | Cria um backup agora, sem parar o servidor. Responde `{id, createdAt, sizeBytes, files, version}`. |
| `GET /physical` | Lista os backups, do mais antigo ao mais novo: `{backups:[{id, createdAt, sizeBytes, files, version}]}`. |
| `POST /physical/{id}/verify` | Verificação completa (restaura num diretório temporário e abre). `{id, ok}`. |
| `DELETE /physical?keep=N` | Mantém só os `N` mais novos (`N >= 1`) e apaga os arquivos que mais nenhum backup usa. |
| `POST /physical/{id}/restore?confirm=true` | Restaura o backup `id` no lugar da base, dentro do processo, sem reiniciar. |

`version` é a marca d'água de versão do instante do backup. A idade do último backup é o RPO (Recovery Point Objective, a perda máxima de dados aceita) do backup físico.

### Restaurar

O restore prepara a base em `<dados>/.restoring`, prova que ela abre, segura as operações em curso (espera até 60 s por varreduras longas, senão responde `409 BUSY` sem tocar na base), troca o conteúdo do diretório de dados e reabre. A base anterior fica em `<dados>/.replaced-<instante>` (só a mais recente é mantida). Se o processo cair no meio da troca, a próxima abertura devolve a base anterior sozinha. O diretório de dados nunca é renomeado, porque em Docker ele costuma ser a raiz de um volume montado.

## Transferência de arquivos (armazenamento de objetos)

Para guardar os backups fora do servidor (o MinIO do Enterprise), o motor expõe os arquivos do diretório de backup. O agente do Enterprise copia só o que falta.

### Listar os arquivos de um backup

```
GET /admin/v1/backups/physical/{id}/files[?source=<origem>]
```

```json
{
  "id": 5,
  "version": 5821,
  "files": [
    {"path": "shared_checksum/000012_3456789_13_1700000000.sst", "sizeBytes": 123456, "shared": true},
    {"path": "private/5/MANIFEST-000005", "sizeBytes": 812, "shared": false},
    {"path": "meta/5", "sizeBytes": 220, "shared": false}
  ]
}
```

A lista traz os arquivos de dados primeiro e `meta/<id>` por último. Quem copia deve gravar `meta/<id>` por último também: sem ele o backup não existe.

### Ler um arquivo

```
GET /admin/v1/backups/physical/files?path=<caminho relativo>[&source=<origem>]
```

Devolve o arquivo em `application/octet-stream`, em fluxo. O cabeçalho `X-Axon-Size` traz o tamanho do arquivo original. Se `AXON_S3_ENCRYPT_KEY` estiver definida, o corpo sai cifrado em blocos (AES-GCM, o mesmo esquema do backup lógico, classe `StreamEncryption`), e o tamanho original continua em `X-Axon-Size`. O caminho precisa ficar sob `meta/`, `private/` ou `shared_checksum/`; qualquer outro, ou com `..`, responde 400.

### Gravar um arquivo

```
PUT /admin/v1/backups/physical/files?path=<caminho relativo>&source=<origem>
```

O cabeçalho opcional `X-Axon-Size` declara o tamanho do arquivo original (sem cifra); se vier e o corpo, depois de decifrado, tiver outro tamanho, a gravação é recusada e não deixa nada. Grava o corpo no diretório de importação `<AXON_BACKUP_DIR>/imports/<origem>/` (a origem é obrigatória na gravação, para um backup vindo de fora nunca se misturar com os do próprio motor). Com a chave de cifra definida, o motor decifra e valida o corpo antes de gravar; a gravação é atômica (arquivo temporário e renomeação). Um arquivo de `shared_checksum/` que já existe com o mesmo tamanho é aceito sem regravar. Responde `{path, sizeBytes}`.

```
DELETE /admin/v1/backups/physical/imports/{origem}
```

Apaga uma área de importação inteira (administrador).

### Restaurar um backup vindo de fora

1. O agente baixa do armazenamento de objetos os arquivos do backup escolhido e os grava com `PUT ...files?path=...&source=<origem>`, deixando `meta/<id>` por último.
2. `GET /physical/{id}/files?source=<origem>` confere que o backup chegou inteiro, e `POST /physical/{id}/restore?confirm=true&source=<origem>` restaura a partir de `imports/<origem>/` em vez do diretório do próprio motor.
3. Se der certo, `DELETE /physical/imports/<origem>` libera o espaço.

Os `id` de um backup importado são os do motor de origem, e a área `imports/<origem>/` é separada do diretório do motor, então nunca colidem com os backups locais.

## Códigos de erro

| Status | Código | Quando |
|---|---|---|
| 400 | `BAD_REQUEST` | `confirm` ausente, caminho fora de `meta/`, `private/` e `shared_checksum/`, nome de origem inválido, gravação sem `source`, corpo cifrado inválido, tamanho divergente, `keep < 1`. |
| 403 | | papel insuficiente. |
| 404 | `NOT_FOUND` | backup, arquivo ou origem de importação inexistente. |
| 409 | `PHYSICAL_UNSUPPORTED` | armazenamento em memória. |
| 409 | `CONFLICT` | cluster (backup físico só vale em nó único). |
| 409 | `BUSY` | o restore não conseguiu esvaziar as operações em 60 s; a base ficou como estava. |
| 500 | `BACKUP_FAILED` | falha do `BackupEngine` ou do sistema de arquivos. |

## O que ainda não existe

- Backup físico de cluster com várias réplicas (E2 e E3 do plano de escala).
- Restauração a um instante (PITR, Point In Time Recovery), que combina este backup com o arquivo de lotes da E2.
