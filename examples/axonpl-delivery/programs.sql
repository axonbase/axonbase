-- AxonPL: funções, procedures e um package para o exemplo de delivery.
-- Cada objeto é uma instrução só, com o corpo entre $$ ... $$ (Python simplificado).

-- FUNÇÃO: só lê, serve em SELECT, WHERE e DEFINE FIELD. Parâmetro com tipo e valor padrão.
CREATE OR REPLACE FUNCTION desconto(valor, pct int = 10) RETURNS number AS $$
"""Aplica um desconto percentual ao valor."""
return round(valor * (100 - pct) / 100, 2)
$$;

-- FUNÇÃO: frete grátis a partir de 100, senão taxa fixa mais um valor por quilômetro.
CREATE OR REPLACE FUNCTION frete(distancia_km, valor) RETURNS number AS $$
if valor >= 100:
    return 0
return round(5 + 1.5 * distancia_km, 2)
$$;

-- FUNÇÃO que consulta o banco com db.one e db.query (somente leitura).
CREATE OR REPLACE FUNCTION estoque_de(codigo) RETURNS int AS $$
p = db.one("SELECT estoque FROM produto WHERE codigo = $c", {"c": codigo})
if p is None:
    return -1
return p["estoque"]
$$;

-- PACKAGE: constantes, funções públicas e uma função com estado global.
-- O estado global só persiste entre chamadas diretas (loja::proximo_numero()). Quando outro programa
-- importa o package, ele é recarregado a cada chamada.
-- Nomes que começam com _ ficam privados ao package.
CREATE OR REPLACE PACKAGE loja AS $$
TAXA_SERVICO = 5
DESCONTO_VIP = 15
vendas = 0

def _com_taxa(valor):
    return valor + valor * TAXA_SERVICO / 100

def preco_final(valor, vip):
    """Preço com taxa de serviço, e desconto extra para cliente VIP."""
    final = _com_taxa(valor)
    if vip:
        final = final * (100 - DESCONTO_VIP) / 100
    return round(final, 2)

def classifica(total):
    if total >= 200:
        return "ouro"
    if total >= 80:
        return "prata"
    return "bronze"

def resumo_cliente(cliente_id):
    """Quantos pedidos e quanto gastou um cliente."""
    linhas = db.query("SELECT total FROM pedido WHERE cliente = $c AND status != 'cancelado'", {"c": cliente_id})
    gasto = 0
    for l in linhas:
        gasto = gasto + l["total"]
    return {"pedidos": len(linhas), "gasto": round(gasto, 2), "nivel": classifica(gasto)}

def repor_estoque(codigo, qtd):
    """Escreve: chame com CALL loja::repor_estoque(...)."""
    if qtd <= 0:
        raise ValueError("a quantidade deve ser positiva")
    db.exec("UPDATE produto SET estoque = estoque + $q WHERE codigo = $c", {"q": qtd, "c": codigo})
    log.info("estoque de {} reposto em {}", codigo, qtd)
    return estoque_de_codigo(codigo)

def estoque_de_codigo(codigo):
    p = db.one("SELECT estoque FROM produto WHERE codigo = $c", {"c": codigo})
    return p["estoque"]

def proximo_numero():
    """Contador global do package: compartilhado entre as chamadas."""
    global vendas
    vendas = vendas + 1
    return vendas
$$;

-- PROCEDURE: escreve no banco. É atômica: se qualquer item falhar, nada fica gravado.
-- itens é uma lista de objetos {codigo, qtd}.
CREATE OR REPLACE PROCEDURE registrar_pedido(cliente_id, itens, distancia_km = 3) AS $$
import loja
cliente = db.one("SELECT * FROM cliente WHERE id = $c", {"c": cliente_id})
if cliente is None:
    raise ValueError("cliente não existe: " + str(cliente_id))
linhas = []
subtotal = 0
for it in itens:
    p = db.one("SELECT * FROM produto WHERE codigo = $c", {"c": it["codigo"]})
    if p is None:
        raise ValueError("produto não existe: " + it["codigo"])
    if p["estoque"] < it["qtd"]:
        raise ValueError("estoque insuficiente de " + p["nome"] + ": restam " + str(p["estoque"]))
    db.exec("UPDATE produto SET estoque = estoque - $q WHERE codigo = $c", {"q": it["qtd"], "c": it["codigo"]})
    linhas.append({"codigo": it["codigo"], "qtd": it["qtd"], "preco": p["preco"]})
    subtotal = subtotal + p["preco"] * it["qtd"]
taxa_entrega = db.one("RETURN frete($d, $v)", {"d": distancia_km, "v": subtotal})
total = loja.preco_final(subtotal, cliente["vip"]) + taxa_entrega
pedido = db.one(
    "CREATE pedido CONTENT {cliente: $c, itens: $i, total: $t, status: 'novo', numero: $n} RETURN AFTER",
    {"c": cliente_id, "i": linhas, "t": round(total, 2), "n": db.count("SELECT * FROM pedido") + 1})
log.info("pedido {} criado, total {}", pedido["numero"], pedido["total"])
return pedido
$$;

-- PROCEDURE: cancela um pedido novo (pelo número) e devolve os itens ao estoque.
CREATE OR REPLACE PROCEDURE cancelar_pedido(numero) AS $$
p = db.one("SELECT * FROM pedido WHERE numero = $n", {"n": numero})
if p is None:
    raise ValueError("pedido não existe: " + str(numero))
if p["status"] != "novo":
    raise ValueError("só dá para cancelar pedido novo; este está " + p["status"])
for it in p["itens"]:
    db.exec("UPDATE produto SET estoque = estoque + $q WHERE codigo = $c", {"q": it["qtd"], "c": it["codigo"]})
db.exec("UPDATE pedido SET status = 'cancelado' WHERE numero = $n", {"n": numero})
return "pedido " + str(numero) + " cancelado"
$$;
