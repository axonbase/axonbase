# Depuração do AxonPL

Este documento descreve o contrato da API (Application Programming Interface) HTTP (HyperText Transfer Protocol) de depuração de funções, procedures e packages AxonPL. A API vive em `/admin/v1` e é consumida pelo painel do AxonBase Enterprise.

## Modelo

Uma sessão de depuração executa um programa numa thread própria do servidor. A execução para em breakpoints (pontos de parada) e em passos, e o cliente a conduz por comandos. Todas as linhas são **linhas do corpo do usuário**: a primeira linha do corpo de uma função ou procedure é a linha 1 (o cabeçalho que o motor acrescenta não conta). Em packages, a linha é a do texto do package.

Regras de segurança e efeito:

- Por padrão a execução roda numa transação que é **sempre revertida** ao final, inclusive em procedures com escrita, em erro, em parada e em ociosidade. Dentro da sessão as escritas ficam visíveis ao próprio programa e a `evaluate`, mas não a outras conexões.
- `commit=true` confirma a transação ao final e exige papel `admin` (e líder, em cluster). Se a execução falhar ou for parada, nada é confirmado.
- Sem `commit`, sentenças de esquema (DDL, Data Definition Language) dentro do programa são recusadas.
- Funções continuam somente leitura. Procedures e packages escrevem na transação da sessão.
- Quem depura usa a própria identidade (mesmas Data Rules e permissões). Exige o mesmo nível de `CREATE FUNCTION`: papel diferente de `viewer` e escopo que cubra o banco.
- Os limites do AxonPL (passos, profundidade, memória e prazo de `AXON_QUERY_TIMEOUT`) valem. O tempo parado num breakpoint **não conta** contra o prazo.
- Uma sessão pausada sem comando por mais que a ociosidade é cancelada e revertida (estado `timeout`). O padrão é 300 segundos e a variável opcional `AXON_PL_DEBUG_IDLE` (segundos, 0 desliga) o altera.
- No máximo 4 sessões ativas ao mesmo tempo por servidor. Passar disso devolve 429.

## Endpoints

| Método | Rota | Função |
|---|---|---|
| POST | `/admin/v1/databases/{ns}/{db}/programs/{nome}/debug` | cria e inicia a sessão (201) |
| GET | `/admin/v1/debug/sessions` | lista sessões visíveis ao chamador |
| GET | `/admin/v1/debug/sessions/{id}` | estado completo |
| GET | `/admin/v1/debug/sessions/{id}/events?after=N&wait=MS` | eventos (long poll) |
| POST | `/admin/v1/debug/sessions/{id}/command` | `{"command": "..."}` |
| PUT | `/admin/v1/debug/sessions/{id}/breakpoints` | `{"lines": [3, 7]}` substitui os breakpoints |
| POST | `/admin/v1/debug/sessions/{id}/evaluate` | `{"expression": "a + 1"}` |
| DELETE | `/admin/v1/debug/sessions/{id}` | para (se ativa), reverte e esquece (204) |

Para um package, `{nome}` é `pacote::funcao`.

### Criar

Corpo (todos os campos são opcionais):

```json
{
  "args": [10],
  "breakpoints": [3],
  "stopOnEntry": true,
  "commit": false,
  "profile": false
}
```

- `stopOnEntry`: pausa no primeiro comando do corpo. Padrão verdadeiro quando não há breakpoints nem `profile`.
- `profile`: executa sem parar (a menos que haja breakpoints) e devolve o rastro no fim. O rastro também vem em toda sessão de depuração.

### Comandos

`continue`, `stepOver` (passo sobre), `stepInto` (passo para dentro), `stepOut` (passo para fora), `pause` (para no próximo comando) e `stop` (cancela e reverte). Os passos só valem em pausa; fora dela a resposta é 409. `stepInto` entra em funções do mesmo programa e em packages importados; chamadas feitas por SQL (Structured Query Language) (por exemplo `fn::outra(...)` dentro de `db.query`) não são seguidas.

### Eventos

`GET .../events?after=N&wait=MS` devolve os eventos com `seq` maior que `N`. Se não houver nenhum e a sessão estiver ativa, espera até `wait` milissegundos (máximo 30000). Resposta:

```json
{ "events": [ { "seq": 1, "type": "paused", "at": 1790000000000, "pause": { } } ], "next": 1, "state": "paused" }
```

O cliente repete a chamada com `after=next`. Tipos de evento: `paused`, `resumed`, `finished`, `error`, `stopped` e `timeout`. Os quatro últimos são finais e trazem `state`, `committed` e, no erro, `error`.

### Estado

```json
{
  "id": "dbg-...", "state": "paused", "program": "grava", "kind": "procedure",
  "function": "grava", "commit": false, "source": "texto do corpo",
  "startedAt": 1790000000000, "breakpoints": [3], "lastEventSeq": 2,
  "pause": {
    "number": 1, "reason": "breakpoint",
    "stack": [ { "function": "grava", "program": "procedure grava", "line": 3,
                 "locals": [ { "name": "v", "type": "int", "value": "10" } ] } ],
    "globals": [ { "name": "BASE", "type": "int", "value": "10" } ]
  }
}
```

`state` vale `running`, `paused`, `finished`, `error`, `stopped` ou `timeout`. `reason` vale `breakpoint`, `step` ou `pause`. Os valores são texto curto (até 200 caracteres, três níveis de aninhamento e 20 itens por coleção). Depois do fim, o estado traz também `result`, `error`, `committed`, `log` (linhas de `log.*` e `print`) e `profile`.

### Avaliar

`evaluate` roda uma expressão de uma linha no quadro de topo da pausa e devolve `{"type": "int", "value": "40"}`. A avaliação usa **cópias** das variáveis (não altera o programa), enxerga locais e globais, e permite `db.query`, `db.one` e `db.count`, mas nunca escrita. Funções do programa e módulos importados não ficam visíveis. Erro de sintaxe ou de execução da expressão devolve 400; fora de pausa, 409.

### Rastro (`profile`)

```json
{
  "lines": [ { "program": "procedure grava", "line": 1, "count": 1, "timeUs": 120 } ],
  "queries": [ { "sql": "CREATE it CONTENT {v: $v}", "durationUs": 340, "rows": 1, "line": 1 } ]
}
```

`timeUs` é o tempo exclusivo da linha (sem o tempo das funções que ela chama), em microssegundos, sem o tempo parado. `queries` lista as chamadas `db.*` na ordem, com a linha do programa que as disparou, para a futura linha do tempo de consultas (query timeline).

## Erros

Formato padrão de `/admin/v1`. Códigos: 400 (`BAD_REQUEST`), 401, 403 (`FORBIDDEN`, por exemplo `commit=true` sem `admin`), 404 (`NOT_FOUND`), 409 (`CONFLICT`, comando fora de hora) e 429 (`TOO_MANY_DEBUG_SESSIONS`). Um erro do programa depurado não é erro HTTP: a sessão passa ao estado `error` com a mensagem e o traceback.

## Métricas

- `axe_pl_debug_sessions` (gauge): sessões ativas agora.
- `axe_pl_debug_timeouts_total` (counter): sessões canceladas por ociosidade.

## Limites conhecidos

- Não há pausa em exceção nem breakpoints condicionais.
- A sessão guarda os últimos 2000 eventos; sessões terminadas ficam 10 minutos na memória (ou até o `DELETE`).
- Em cluster, a depuração sem `commit` roda no nó que recebeu o pedido e não replica nada.
