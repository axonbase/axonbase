# AxonPL: pedidos de delivery

Exemplo de funções, procedures e packages escritos em AxonPL (um subconjunto de Python que roda dentro do banco). O cenário é um delivery: produtos com estoque, clientes e pedidos que baixam o estoque.

Rode num banco só para o exemplo (por exemplo `app/axonpl_demo`), na ordem: `seed.sql`, `programs.sql` e `queries.sql`. No Workspace do Enterprise, cole cada arquivo no console e use "Run all". Os programas aparecem na árvore do banco, em Funções, Procedures e Packages, onde também podem ser abertos, executados e depurados.

## Arquivos

| arquivo | conteúdo |
|---|---|
| `seed.sql` | tabelas `produto` (com índice `UNIQUE` em `codigo`), `cliente` e `pedido`, com quatro produtos e dois clientes |
| `programs.sql` | três funções, um package e duas procedures |
| `queries.sql` | as chamadas, numeradas de 1 a 7 |

## Os programas

| objeto | tipo | o que faz |
|---|---|---|
| `desconto(valor, pct = 10)` | função | aplica um desconto percentual |
| `frete(distancia_km, valor)` | função | frete grátis a partir de 100, senão taxa fixa mais um valor por quilômetro |
| `estoque_de(codigo)` | função | consulta o estoque com `db.one` (só lê) |
| `loja` | package | constantes (`TAXA_SERVICO`, `DESCONTO_VIP`), `preco_final`, `classifica`, `resumo_cliente`, `repor_estoque` e `proximo_numero` |
| `registrar_pedido(cliente_id, itens, distancia_km = 3)` | procedure | confere o estoque, baixa cada item e grava o pedido |
| `cancelar_pedido(numero)` | procedure | devolve os itens ao estoque e marca o pedido como cancelado |

Uma função só lê, então vale em `SELECT`, `WHERE` e `DEFINE FIELD ... ASSERT`. Quem escreve é procedure, chamada com `CALL`. Funções de package seguem a mesma regra: em `SELECT` só leem, e para gravar a chamada é `CALL loja::repor_estoque(...)`.

## Chamadas

```sql
-- função direta e dentro de um SELECT
RETURN desconto(50, 20);
SELECT nome, preco, desconto(preco, 15) AS promocional FROM produto ORDER BY preco;

-- package: pacote::funcao
RETURN loja::preco_final(100, true);

-- procedure: grava de verdade
CALL registrar_pedido(cliente:1, [{codigo: 'PIZ-001', qtd: 2}, {codigo: 'BEB-001', qtd: 1}], 4);
```

## Atomicidade

`registrar_pedido` baixa o estoque item por item. Se um item falha, como pedir 5 brownies quando restam 2, a procedure inteira é desfeita: a pizza do primeiro item também volta ao estoque, e o erro traz o traceback com a linha do código.

```sql
CALL registrar_pedido(cliente:1, [{codigo: 'PIZ-001', qtd: 1}, {codigo: 'SOB-001', qtd: 5}]);
SELECT nome, estoque FROM produto ORDER BY codigo;   -- nada mudou
```

## Estado global do package

`loja::proximo_numero()` incrementa uma variável global do package. Esse estado vale entre chamadas diretas (`RETURN loja::proximo_numero()` devolve 1, depois 2). Quando outra procedure importa o package com `import loja`, ele é recarregado a cada chamada, e por isso `registrar_pedido` numera os pedidos contando as linhas de `pedido`.

## Depurar

No Workspace, abra `registrar_pedido`, clique na margem para colocar um breakpoint (por exemplo na linha da baixa de estoque) e use "Depurar". A execução para na linha, mostra a pilha e as variáveis, e permite avaliar uma expressão. Por padrão a depuração roda numa transação que é sempre revertida, então nenhum pedido é gravado.
