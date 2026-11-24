#=== co_lista_basica
def main():
    xs = [3, 1, 2]
    xs.append(5)
    xs.extend([7, 8])
    xs.insert(0, 0)
    xs.insert(-1, 99)
    xs.insert(100, 100)
    return [xs, len(xs), xs.index(99), xs.count(1), 5 in xs, 4 in xs]

#=== co_lista_remocao
def main():
    xs = [1, 2, 3, 2, 1]
    xs.remove(2)
    a = xs.pop()
    b = xs.pop(0)
    del xs[0]
    ys = list(range(10))
    del ys[2:5]
    del ys[::2]
    return [xs, a, b, ys]

#=== co_lista_fatias_atribuicao
def main():
    xs = list(range(10))
    xs[2:4] = [20, 30, 40]
    xs[0] = -1
    xs[-1] = 99
    ys = list(range(6))
    ys[::2] = ["a", "b", "c"]
    zs = [1, 2, 3]
    zs[1:1] = [9, 9]
    zs[:] = [0]
    return [xs, ys, zs]

#=== co_lista_fatias_leitura
def main():
    xs = list(range(10))
    return [xs[2:5], xs[:3], xs[7:], xs[::3], xs[::-1], xs[-3:], xs[1:-1:2], xs[8:2:-2], xs[20:], xs[:-20], xs[5:2]]

#=== co_lista_ordenacao
def main():
    xs = [5, 2, 8, 1, 9, 3]
    ys = sorted(xs)
    zs = sorted(xs, reverse=True)
    xs.sort()
    ws = ["bb", "a", "ccc", "dd"]
    ws.sort(key=len)
    vs = ["bb", "a", "ccc", "dd"]
    vs.sort(key=len, reverse=True)
    return [xs, ys, zs, ws, vs, sorted([3.5, 1, 2.25]), sorted([(2, "b"), (1, "z"), (2, "a")])]

#=== co_sort_estavel
def main():
    pares = [("a", 2), ("b", 1), ("c", 2), ("d", 1), ("e", 2)]
    s1 = sorted(pares, key=lambda p: p[1])
    s2 = sorted(pares, key=lambda p: p[1], reverse=True)
    return [s1, s2]

#=== co_lista_reverse_copy
def main():
    xs = [1, 2, 3]
    ys = xs.copy()
    xs.reverse()
    zs = xs[:]
    zs.append(4)
    return [xs, ys, zs, list(reversed(ys)), xs is ys, xs == [3, 2, 1]]

#=== co_lista_multiplicacao_concat
def main():
    a = [0] * 3
    b = [[0] * 2] * 2
    b[0][0] = 1
    c = [1, 2] + [3]
    c += [4, 5]
    d = [1, 2]
    d *= 2
    return [a, b, c, d]

#=== co_lista_comparacao
def main():
    return [[1, 2] == [1, 2], [1, 2] < [1, 3], [1, 2] < [1, 2, 0], [] < [0], [2] > [1, 9], [1, [2, 3]] == [1, [2, 3]], [1, 2] != [2, 1], (1, 2) < (1, 3), (1, 2) == [1, 2]]

#=== co_lista_aninhada
def main():
    m = [[1, 2, 3], [4, 5, 6], [7, 8, 9]]
    t = [[m[j][i] for j in range(3)] for i in range(3)]
    diag = [m[i][i] for i in range(3)]
    flat = [x for linha in m for x in linha]
    return [t, diag, flat, sum(map(sum, m)), max(max(l) for l in m)]

#=== co_tupla_basica
def main():
    t = (1, 2, 3)
    u = 4,
    v = ()
    a, b, c = t
    return [t, u, v, a + b + c, t + u, t * 2, len(t), t[1], t[::-1], t.index(2), t.count(1), 2 in t, (1,) == (1,), tuple([1, 2]), tuple("ab")]

#=== co_desempacotamento
def main():
    a, *b = [1, 2, 3, 4]
    *c, d = [1, 2, 3, 4]
    e, *f, g = (1, 2, 3, 4, 5)
    (h, i), j = (1, 2), 3
    [k, l] = "xy"
    m, *n = "a"
    for p, (q, r) in [(1, (2, 3)), (4, (5, 6))]:
        pass
    return [a, b, c, d, e, f, g, h, i, j, k, l, m, n, p, q, r]

#=== co_desempacotamento_chamada_literal
def soma(a, b, c):
    return a + b + c

def main():
    xs = [1, 2]
    d = {"c": 3}
    return [soma(*xs, **d), [*xs, 3, *"ab"], (*xs, *xs), {*xs, 5} == {1, 2, 5}, {**{"a": 1}, "b": 2, **{"a": 9}}, soma(1, *[2], c=5)]

#=== co_dict_basico
def main():
    d = {"a": 1, "b": 2}
    d["c"] = 3
    d["a"] += 10
    del d["b"]
    return [d, len(d), "a" in d, "b" in d, d.get("a"), d.get("z"), d.get("z", 0), list(d), list(d.keys()), list(d.values()), list(d.items())]

#=== co_dict_metodos
def main():
    d = {"x": 1}
    d.update({"y": 2, "x": 5})
    d.update(z=3)
    d.update([("w", 4)])
    a = d.pop("y")
    b = d.pop("nada", -1)
    c = d.setdefault("k", [])
    c.append(1)
    e = d.setdefault("x", 100)
    f = d.popitem()
    g = d.copy()
    g["novo"] = 1
    return [d, a, b, c, e, f, g, sorted(g)]

#=== co_dict_iteracao
def main():
    d = {"um": 1, "dois": 2, "tres": 3}
    ks = []
    vs = []
    its = []
    for k in d:
        ks.append(k)
    for v in d.values():
        vs.append(v * 2)
    for k, v in d.items():
        its.append(f"{k}={v}")
    return [ks, vs, its, {k: v for k, v in d.items() if v > 1}, {v: k for k, v in d.items()}, sum(d.values()), max(d, key=d.get)]

#=== co_dict_ordem_insercao
def main():
    d = {}
    for ch in "zebra":
        d[ch] = ord(ch)
    d["a"] = 0
    del d["z"]
    d["z"] = 1
    return [list(d), list(d.values())]

#=== co_dict_chaves_variadas
def main():
    d = {1: "int", "1": "str", (1, 2): "tupla", 2.5: "float", None: "none", frozenset([1]): "fs"}
    return [d[1], d["1"], d[(1, 2)], d[2.5], d[None], len(d), d[frozenset([1])]]

#=== co_dict_aninhado
def main():
    cfg = {"db": {"host": "h", "portas": [1, 2]}, "n": 3}
    cfg["db"]["portas"].append(3)
    cfg["db"]["user"] = "u"
    cfg.setdefault("log", {})["nivel"] = "x"
    return [cfg, cfg["db"]["portas"][-1], len(cfg["db"]), sorted(cfg)]

#=== co_dict_comparacao
def main():
    a = {"x": 1, "y": [1, 2]}
    b = {"y": [1, 2], "x": 1}
    return [a == b, a != b, {} == {}, {"a": 1} == {"a": 1.0}, {"a": 1} == {"a": 2}, dict(a) == a, dict([("p", 1), ("q", 2)]), dict(a=1, b=2), dict({"k": 1}, z=2)]

#=== co_dict_agrupamento
def main():
    palavras = ["casa", "carro", "bola", "barco", "cama", "lua"]
    g = {}
    for p in palavras:
        g.setdefault(p[0], []).append(p)
    return [g, {k: len(v) for k, v in g.items()}, sorted(g.items())]

#=== co_set_basico
def main():
    s = {3, 1, 2, 3}
    s.add(5)
    s.add(1)
    s.discard(2)
    s.discard(99)
    s.remove(3)
    return [sorted(s), len(s), 1 in s, 3 in s, sorted(set([1, 1, 2])), set() == set(), sorted(set("hello"))]

#=== co_set_operacoes
def main():
    a = {1, 2, 3, 4}
    b = {3, 4, 5}
    return [sorted(a | b), sorted(a & b), sorted(a - b), sorted(a ^ b), sorted(a.union(b, {9})), sorted(a.intersection(b)), sorted(a.difference(b)), sorted(a.symmetric_difference(b)), {1} <= a, {1, 9} <= a, a >= {1, 2}, a < a, a <= a, a.issubset({1, 2, 3, 4, 5}), a.issuperset({1}), a.isdisjoint({7})]

#=== co_set_update
def main():
    a = {1}
    a.update([2, 3], {4})
    a |= {5}
    a -= {1}
    a &= {2, 3, 5, 6}
    b = {1, 2, 3}
    b.intersection_update({2, 3, 4})
    c = {1, 2, 3}
    c.difference_update({1})
    return [sorted(a), sorted(b), sorted(c)]

#=== co_set_deduplicar_ordem
def main():
    xs = [3, 1, 3, 2, 1]
    visto = set()
    out = []
    for x in xs:
        if x not in visto:
            visto.add(x)
            out.append(x)
    return [out, sorted(set(xs)), len(set(xs))]

#=== co_frozenset
def main():
    f = frozenset([1, 2, 3])
    g = frozenset([3, 2, 1])
    d = {f: "x"}
    return [f == g, d[g], sorted(f | {9}), len(f), 2 in f, f.issubset({1, 2, 3, 4}), sorted(f & g)]

#=== co_compreensoes_lista
def main():
    return [[x * x for x in range(6)], [x for x in range(20) if x % 3 == 0 if x % 2 == 0], [(x, y) for x in range(3) for y in range(x)], [c.upper() for c in "abc"], [[i * j for j in range(3)] for i in range(3)], [x if x % 2 else -x for x in range(5)], [y for y in [1, 2, 3] for _ in range(y)]]

#=== co_compreensoes_dict_set
def main():
    return [{x: x ** 2 for x in range(4)}, {x % 3 for x in range(10)} == {0, 1, 2}, {c: ord(c) for c in "ab"}, {k: v for k, v in zip("abc", range(3)) if v}, sorted({len(w) for w in ["a", "bb", "cc", "d"]})]

#=== co_geradores_ansiosos
def main():
    return [sum(x for x in range(10)), max(len(w) for w in ["a", "bbb", "cc"]), min((x * x for x in [3, -4, 2])), any(x > 8 for x in range(10)), all(x < 10 for x in range(10)), sorted(x % 4 for x in range(10)), list(x + 1 for x in [1, 2]), tuple(i for i in "ab"), ",".join(str(x) for x in range(3)), set(x for x in [1, 1, 2]) == {1, 2}]

#=== co_escopo_compreensao
def main():
    x = "externo"
    ys = [x for x in range(3)]
    fs = [lambda x=x: x for x in range(3)]
    gs = [(lambda: n) for n in range(3)]
    return [x, ys, [f() for f in fs], [g() for g in gs]]

#=== co_range
def main():
    r = range(1, 10, 3)
    return [list(range(5)), list(range(2, 8, 2)), list(range(5, 0, -2)), list(range(0)), list(range(3, 3)), len(r), r[0], r[-1], 4 in r, 5 in r, list(r), sum(range(101)), list(range(-3, 3)), list(reversed(range(4))), range(0, 5) == range(5), len(range(10, 0, -3))]

#=== co_enumerate_zip
def main():
    xs = ["a", "b", "c"]
    ys = [10, 20, 30, 40]
    return [list(enumerate(xs)), list(enumerate(xs, start=5)), list(zip(xs, ys)), list(zip(xs, ys, [True, False, None])), list(zip()), dict(zip(xs, ys)), [(i, c) for i, c in enumerate("xy")], list(zip(*[(1, 2), (3, 4), (5, 6)])), [a * b for a, b in zip([1, 2, 3], [4, 5, 6])]]

#=== co_map_filter
def main():
    return [list(map(lambda x: x * 2, [1, 2, 3])), list(map(str, [1, 2])), list(map(lambda a, b: a + b, [1, 2], [10, 20])), list(filter(lambda x: x > 1, [0, 1, 2, 3])), list(filter(None, [0, 1, "", "a", None, [], [0]])), list(map(len, ["a", "bb"])), sum(map(int, "12345"))]

#=== co_reversed_sorted_iter
def main():
    it = iter([1, 2, 3])
    a = next(it)
    b = next(it)
    c = next(it)
    d = next(it, "fim")
    return [a, b, c, d, list(reversed([1, 2, 3])), list(reversed("abc")), list(reversed((1, 2))), sorted(iter([3, 1, 2])), list(iter("ab"))]

#=== co_any_all
def main():
    return [any([]), all([]), any([0, 0, 1]), all([1, 1, 0]), any(x == "a" for x in "bca"), all(isinstance(x, int) for x in [1, 2, 3]), any(map(lambda x: x > 5, range(10))), all(x for x in [1, "a", [1]])]

#=== co_len_sum_min_max_colecoes
def main():
    d = {"a": 3, "b": 1}
    return [len(d), len("abc"), len([1, 2]), len((1,)), len({1}), len(range(7)), min(d), max(d.values()), sum(d.values()), sum([[1], [2]], []), max([[1, 2], [1, 3]]), min([(2, "a"), (1, "z")])]

#=== co_pilha_fila
def main():
    pilha = []
    for i in range(5):
        pilha.append(i)
    topo = pilha.pop()
    fila = list(range(5))
    primeiro = fila.pop(0)
    return [pilha, topo, fila, primeiro]

#=== co_busca_binaria
def busca(xs, alvo):
    lo, hi = 0, len(xs) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if xs[mid] == alvo:
            return mid
        if xs[mid] < alvo:
            lo = mid + 1
        else:
            hi = mid - 1
    return -1

def main():
    xs = [1, 3, 5, 7, 9, 11, 13]
    return [busca(xs, 7), busca(xs, 1), busca(xs, 13), busca(xs, 4)]

#=== co_bubble_sort
def bolha(xs):
    xs = list(xs)
    n = len(xs)
    for i in range(n):
        for j in range(n - 1 - i):
            if xs[j] > xs[j + 1]:
                xs[j], xs[j + 1] = xs[j + 1], xs[j]
    return xs

def main():
    return [bolha([5, 2, 9, 1, 5, 6]), bolha([]), bolha([1]), bolha([3, 2, 1])]

#=== co_quicksort_recursivo
def qs(xs):
    if len(xs) <= 1:
        return xs
    p = xs[0]
    return qs([x for x in xs[1:] if x < p]) + [p] + qs([x for x in xs[1:] if x >= p])

def main():
    return qs([3, 6, 1, 8, 2, 9, 2, 5, 3])

#=== co_matriz_multiplicacao
def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(len(b))) for j in range(len(b[0]))] for i in range(len(a))]

def main():
    a = [[1, 2], [3, 4]]
    b = [[5, 6], [7, 8]]
    return [mul(a, b), mul(a, [[1, 0], [0, 1]])]

#=== co_contagem_frequencia
def main():
    texto = "abracadabra"
    freq = {}
    for c in texto:
        freq[c] = freq.get(c, 0) + 1
    topo = sorted(freq.items(), key=lambda kv: (-kv[1], kv[0]))
    return [topo, max(freq.values()), [c for c, n in topo if n == 1]]

#=== co_copia_rasa_profunda
def main():
    a = [[1, 2], [3]]
    b = a[:]
    c = [x[:] for x in a]
    a[0].append(99)
    return [a, b, c, a == b, a == c, a[0] is b[0], a[0] is c[0]]

#=== co_identidade_e_none
def main():
    a = [1]
    b = a
    c = [1]
    return [a is b, a is c, a == c, None is None, a is not None, "a" == "a", None == None, None == 0]

#=== co_tabela_agregada
def main():
    vendas = [("ana", 10), ("bia", 5), ("ana", 7), ("caio", 3), ("bia", 8)]
    tot = {}
    for nome, v in vendas:
        tot[nome] = tot.get(nome, 0) + v
    rank = sorted(tot.items(), key=lambda kv: kv[1], reverse=True)
    return [tot, rank, [n for n, _ in rank], sum(tot.values())]
