-- AxonPL: chamadas do exemplo de delivery. Rode depois de seed.sql e programs.sql.
-- Execute uma instrução por vez para ver cada resultado.

-- 1. FUNÇÕES: chamada direta e dentro de um SELECT
RETURN desconto(50, 20);
RETURN desconto(50);
RETURN frete(4, 60);
RETURN frete(4, 120);
SELECT nome, preco, desconto(preco, 15) AS promocional FROM produto ORDER BY preco;
SELECT nome FROM produto WHERE estoque_de(codigo) < 5;
RETURN estoque_de('PIZ-001');

-- 2. PACKAGE: funções públicas com pacote::funcao
RETURN loja::preco_final(100, false);
RETURN loja::preco_final(100, true);
RETURN loja::classifica(250);
SELECT nome, loja::preco_final(preco, true) AS preco_vip FROM produto;

-- 3. PROCEDURE: registra um pedido (grava de verdade, por isso usa CALL)
CALL registrar_pedido(cliente:1, [{codigo: 'PIZ-001', qtd: 2}, {codigo: 'BEB-001', qtd: 1}], 4);
CALL registrar_pedido(cliente:2, [{codigo: 'PIZ-002', qtd: 3}, {codigo: 'SOB-001', qtd: 1}], 8);
SELECT * FROM pedido;
SELECT nome, estoque FROM produto ORDER BY codigo;

-- 4. ATOMICIDADE: o segundo item não tem estoque (restam 2 brownies), então NADA é gravado,
--    nem a baixa da pizza do primeiro item.
CALL registrar_pedido(cliente:1, [{codigo: 'PIZ-001', qtd: 1}, {codigo: 'SOB-001', qtd: 5}]);
SELECT nome, estoque FROM produto ORDER BY codigo;

-- 5. PROCEDURE de package e função com estado global
CALL loja::repor_estoque('SOB-001', 10);
RETURN loja::resumo_cliente(cliente:1);
RETURN loja::proximo_numero();
RETURN loja::proximo_numero();

-- 6. CANCELAR devolve o estoque
CALL cancelar_pedido(1);
SELECT nome, estoque FROM produto ORDER BY codigo;

-- 7. INFORMAÇÕES e remoção
INFO FOR FUNCTION desconto;
INFO FOR PACKAGE loja;
INFO FOR DATABASE;
-- DROP FUNCTION desconto;
