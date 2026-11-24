#!/usr/bin/env python3
"""Gera o corpus de varreduras (sweeps.pyt) usado pelos testes diferenciais do AxonPL.

Cada programa gerado e pequeno e valido em Python 3 e AxonPL. Os resultados esperados
sao produzidos depois por scripts/gen_expected.py com o python3 de verdade.

Uso: python3 axonbase-pl/scripts/gen_sweeps.py
"""
import os

SAIDA = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources", "diff", "sweeps.pyt")

programas = []


def add(nome, corpo):
    programas.append((nome, corpo))


# ---------------------------------------------------------------- formatos numericos e de texto
INTS = [0, 7, -7, 42, -1234567, 255, 100000]
FLOATS = [0.0, 1.5, -2.25, 3.14159, 1234567.891, 1e-05, 25000000000.0, 0.1, 2.5, 0.125, -0.0001234]
STRS = ["", "a", "abc", "olá", "x" * 12]

SPECS_INT = ["", "d", "5d", "<5d", "^7d", "05d", "+d", " d", ",d", "_d", "x", "X", "#x", "o", "b", "08b", "10", ">10,d", "*^9d", "+08d", "#o", "#b"]
SPECS_FLOAT = ["", ".2f", "10.3f", "<10.1f", "^10.2f", "010.2f", "+.1f", "e", ".3e", "E", "g", ".3g", ".0f", "%", ".1%", ",.2f", "_.1f", "12", ">12", ".4", "G", "+e", ".0e", "#.0f"]
SPECS_STR = ["", "s", ">8", "<8", "^8", "*>8", "->8", ".1", ".2s", "10.2", "_^9"]

for i, spec in enumerate(SPECS_INT):
    add(f"sw_fmt_int_{i}", f"def main():\n    return [format(v, {spec!r}) for v in {INTS!r}]\n")
for i, spec in enumerate(SPECS_FLOAT):
    add(f"sw_fmt_float_{i}", f"def main():\n    return [format(v, {spec!r}) for v in {FLOATS!r}]\n")
for i, spec in enumerate(SPECS_STR):
    add(f"sw_fmt_str_{i}", f"def main():\n    return [format(v, {spec!r}) for v in {STRS!r}]\n")
add("sw_fmt_int_como_float", "def main():\n    return [format(v, '.2f') for v in [0, 7, -7, 1000000]] + [format(v, 'e') for v in [1, 10, 12345]]\n")
add("sw_fmt_bool", "def main():\n    return [format(True, ''), format(False, 'd'), format(True, '>6'), f'{True}', f'{None}']\n")
add("sw_fmt_erros", "def main():\n    r = []\n    for v, s in [(1, 's_x'), ('a', 'd'), (1.5, 'x'), (1, '.2d'), ('a', '05')]:\n        try:\n            r.append(format(v, s))\n        except ValueError:\n            r.append('erro')\n    return r\n")

# ---------------------------------------------------------------- aritmetica
OPS = [("+", "soma"), ("-", "sub"), ("*", "mul"), ("/", "div"), ("//", "piso"), ("%", "mod"), ("**", "pot")]
PARES = {
    "int": [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 5), (5, 0), (1234, 3), (1, 1), (10, 3), (2, 10), (3, -2), (-3, 3)],
    "float": [(7.5, 2.0), (-7.5, 2.5), (0.1, 0.2), (1e10, 3.0), (5.0, 0.0), (2.5, -0.5), (1e-5, 1e5), (9.99, 0.01), (-0.0, 1.0), (1.0, 3.0), (100.0, 7.0), (0.5, 3.0)],
    "misto": [(7, 2.0), (2.5, 3), (-4, 0.5), (10, 4.0), (1, 3.0), (0, 2.5), (6.0, 4), (-9, 2.0), (3, 0.0), (2, 0.5), (1e3, 7), (5, -2.0)],
}
for op, nome in OPS:
    for tipo, pares0 in PARES.items():
        # Python devolve complex para base negativa com expoente fracionario; o AxonPL nao tem complex
        pares = [(a, b) for a, b in pares0 if not (op == "**" and a < 0 and b != int(b))]
        corpo = (
            "def calc(a, b):\n"
            "    try:\n"
            f"        return a {op} b\n"
            "    except ZeroDivisionError:\n"
            "        return 'zero'\n"
            "    except ValueError:\n"
            "        return 'valor'\n"
            "    except OverflowError:\n"
            "        return 'overflow'\n\n"
            "def main():\n"
            f"    return [calc(a, b) for a, b in {pares!r}]\n"
        )
        add(f"sw_ar_{nome}_{tipo}", corpo)

COMPS = ["<", "<=", ">", ">=", "==", "!="]
for op in COMPS:
    pares = [(1, 2), (2, 2), (3, 2), (1, 1.5), (2, 2.0), (-1, -1.0), (0.1 + 0.2, 0.3), (True, 1), (10 ** 15, 1e15), (2 ** 53, 2.0 ** 53)]
    add(f"sw_cmp_{COMPS.index(op)}", f"def main():\n    return [a {op} b for a, b in {pares!r}]\n")

# ---------------------------------------------------------------- fatias
for passo in ["None", "1", "2", "3", "-1", "-2", "-3"]:
    limites = [(None, None), (2, None), (None, 5), (1, 7), (-3, None), (None, -2), (-7, -2), (8, 2), (20, None), (None, -20), (5, 5), (-1, None)]
    exprs = []
    for a, b in limites:
        sa = "" if a is None else str(a)
        sb = "" if b is None else str(b)
        sp = "" if passo == "None" else ":" + passo
        exprs.append(f"{sa}:{sb}{sp}")
    lista = ", ".join(f"xs[{e}]" for e in exprs)
    txt = ", ".join(f"s[{e}]" for e in exprs)
    nome = passo.replace("-", "m")
    add(f"sw_fatia_lista_{nome}", f"def main():\n    xs = list(range(10))\n    return [{lista}]\n")
    add(f"sw_fatia_str_{nome}", f"def main():\n    s = 'abcdefghij'\n    return [{txt}]\n")
    add(f"sw_fatia_tupla_{nome}", f"def main():\n    t = tuple(range(10))\n    s = t\n    return [{txt}]\n")

# ---------------------------------------------------------------- metodos de str
ENTRADAS = ["", "  a b  ", "Hello World", "ola, mundo", "ÁÉÍ óú", "123", "a1b2", "UPPER lower", "x", "   ", "tab\there", "ab" * 4, "João da Silva"]
METODOS = [
    "upper()", "lower()", "title()", "capitalize()", "swapcase()", "strip()", "lstrip()", "rstrip()", "split()",
    "split('o')", "split(' ', 1)", "rsplit(' ', 1)", "isdigit()", "isalpha()", "isalnum()", "isspace()", "isupper()", "islower()",
    "zfill(8)", "center(15, '*')", "ljust(14, '.')", "rjust(14)", "find('o')", "rfind('o')", "count('a')", "count('')",
    "startswith('H')", "endswith('d')", "replace('o', '0')", "replace('', '-')", "partition(' ')", "rpartition(' ')",
    "splitlines()", "title().swapcase()", "strip('a').upper()", "removeprefix('a')", "removesuffix('d')", "index('a') if 'a' in s else -1",
]
for i, m in enumerate(METODOS):
    add(f"sw_str_{i}", f"def main():\n    return [s.{m} for s in {ENTRADAS!r}]\n")

# ---------------------------------------------------------------- floats, round, int, repr
FLOATS_REPR = [0.1, 0.2, 0.3, 1 / 3, 2 / 3, 10 / 3, 1e15, 1e16, 1e17, 123456789012345.6, 1234567890123456.7, 1e-4, 1e-5, 0.00012, 1.5e-7, 1e100, 1.2345678901234567e-200, 5e-324, 1.7976931348623157e308, 4.35, 4.349999999999999, 100.0, 1e2, 12.0, 0.5, 2.0 ** 0.5, 3.14159265358979, 9007199254740993.0, 123.456e5, 0.1 + 0.7]
add("sw_float_repr_1", f"def main():\n    return {FLOATS_REPR[:15]!r}\n")
add("sw_float_repr_2", f"def main():\n    return {FLOATS_REPR[15:]!r}\n")
add("sw_float_str_1", f"def main():\n    return [str(x) for x in {FLOATS_REPR[:15]!r}]\n")
add("sw_float_str_2", f"def main():\n    return [str(x) + '|' + repr(x) for x in {FLOATS_REPR[15:]!r}]\n")
VALS_ROUND = [0.5, 1.5, 2.5, 3.5, -0.5, -1.5, -2.5, 0.49999, 2.675, 1.005, 1234.5678, 0.125, 0.375, 1e15 + 0.5, 99.995, 7.0]
for n in [None, 0, 1, 2, 3, -1]:
    chamada = "round(x)" if n is None else f"round(x, {n})"
    add(f"sw_round_{'n' if n is None else str(n).replace('-', 'm')}", f"def main():\n    return [{chamada} for x in {VALS_ROUND!r}]\n")
add("sw_int_de_float", "def main():\n    return [int(x) for x in [0.0, 0.9, -0.9, 1e10, -1e10, 123.999, 2.5e15]]\n")
add("sw_int_de_texto", "def main():\n    r = []\n    for t in ['1', ' 2 ', '+3', '-4', '1_0', '0', '007', 'x', '1.5', '', '1 2']:\n        try:\n            r.append(int(t))\n        except ValueError:\n            r.append('erro')\n    return r\n")
add("sw_float_de_texto", "def main():\n    r = []\n    for t in ['1', '2.5', ' 3.5 ', '-1e3', '.5', '5.', 'inf', '-inf', 'x', '', '1e', '1_0.5', '1__0']:\n        try:\n            r.append(float(t))\n        except ValueError:\n            r.append('erro')\n    return r\n")

# ---------------------------------------------------------------- listas e dicts com valores variados
add("sw_sort_tipos", "def main():\n    return [sorted([3, 1.5, 2, True, 0]), sorted(['b', 'B', 'a', 'A', '1', '_']), sorted([(1, 'b'), (1, 'a'), (0, 'z')]), sorted([[2, 1], [1, 5], [1, 2]]), sorted([-1, -10, 5, 0], key=abs), sorted(['aa', 'b', 'ccc'], key=len, reverse=True)]\n")
add("sw_minmax_variados", "def main():\n    return [min([3, 1.5, 2]), max([3, 1.5, 2]), min('hello'), max(['a', 'ab', 'b']), min([[2], [1, 9]]), max((1, 2), (1, 3)), min(1, 2.0), max(1, 1.0), max(1.0, 1)]\n")
add("sw_sum_variados", "def main():\n    return [sum([]), sum([1, 2, 3]), sum([0.1] * 3), sum([1, 2.5]), sum([True, True]), sum(range(1000)), sum([0.1, 0.2, 0.3, 0.4]), sum([1e100, 1.0, -1e100]), sum([[1], [2, 3]], []), sum((x for x in range(5)), 100)]\n")
add("sw_divmod_pares", "def main():\n    return [divmod(a, b) for a, b in [(7, 2), (-7, 2), (7, -2), (-7, -2), (0, 3), (10, 5), (7.5, 2), (-7.5, 2), (1e10, 3)]]\n")
add("sw_pow_variados", "def main():\n    return [pow(2, 8), pow(2, 0), pow(0, 0), pow(-2, 3), pow(2, -3), pow(2.5, 2), pow(9, 0.5), pow(10, 18), pow(7, 2, 5), pow(2, 100, 1000007)]\n")
add("sw_bool_truthiness", "def main():\n    vals = [0, 1, -1, 0.0, 0.1, '', ' ', [], [0], (), (0,), {}, {0: 0}, set(), {0}, None, True, False]\n    return [bool(v) for v in vals]\n")
add("sw_dict_get_default", "def main():\n    d = {'a': 1, 'b': None, 'c': 0, 'd': ''}\n    return [d.get(k, 'padrao') for k in 'abcdx'] + [d[k] or 'vazio' for k in 'abcd']\n")

if __name__ == "__main__":
    os.makedirs(os.path.dirname(SAIDA), exist_ok=True)
    with open(SAIDA, "w") as f:
        for nome, corpo in programas:
            f.write(f"#=== {nome}\n{corpo}\n")
    print(len(programas), "programas gerados em", os.path.normpath(SAIDA))
