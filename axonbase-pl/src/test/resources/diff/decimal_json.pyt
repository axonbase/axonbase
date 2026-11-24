#=== dc_basico
from decimal import Decimal

def main():
    a = Decimal("10.50")
    b = Decimal("2")
    return [a + b, a - b, a * b, a / b, a // b, a % b, a ** 2, -a, abs(Decimal("-3.2")), a > b, a == Decimal("10.5"), a == Decimal("10.50")]

#=== dc_precisao_financeira
from decimal import Decimal

def main():
    total = Decimal("0")
    for p in ["0.1", "0.2", "0.3"]:
        total += Decimal(p)
    return [total, total == Decimal("0.6"), 0.1 + 0.2 == 0.3, Decimal("1.10") + Decimal("2.20"), Decimal("19.99") * 3, Decimal("100") / Decimal("3")]

#=== dc_str_repr
from decimal import Decimal

def main():
    d = Decimal("3.14")
    return [str(d), repr(d), str(Decimal("1E+3")), str(Decimal("0.0000001")), str(Decimal("100")), str(Decimal("-0.50")), f"{d}", f"{d:.1f}", f"{Decimal('1234.5'):,.2f}"]

#=== dc_conversoes
from decimal import Decimal

def main():
    return [Decimal(5), Decimal("5.0"), Decimal(0.5), int(Decimal("7.9")), float(Decimal("1.25")), Decimal(True), str(Decimal(" 3 ")), Decimal("1_0.5")]

#=== dc_arredondamento
from decimal import Decimal

def main():
    d = Decimal("2.675")
    return [round(d, 2), round(Decimal("2.5")), round(Decimal("3.5")), round(Decimal("-2.5")), d.quantize(Decimal("0.01")), Decimal("1.005").quantize(Decimal("0.01")), round(Decimal("12.345"), 1)]

#=== dc_comparacao_mista
from decimal import Decimal

def main():
    return [Decimal("1") == 1, Decimal("1.5") == 1.5, Decimal("0.1") == 0.1, Decimal("2") > 1, Decimal("2") < 2.5, sorted([Decimal("3"), 1, Decimal("2.5"), 2]), max(Decimal("1"), 2, Decimal("1.5")), sum([Decimal("1.1"), Decimal("2.2")]), bool(Decimal("0")), bool(Decimal("0.01"))]

#=== dc_erros_tipo
from decimal import Decimal

def main():
    r = []
    try:
        Decimal("1") + 1.5
    except TypeError:
        r.append("soma float")
    try:
        Decimal("abc")
    except Exception:
        r.append("literal")
    try:
        Decimal("1") / Decimal("0")
    except ZeroDivisionError:
        r.append("zero")
    return r

#=== dc_soma_lista_precos
from decimal import Decimal

def main():
    itens = [("lapis", "1.50", 3), ("caderno", "12.90", 2), ("caneta", "3.25", 4)]
    total = sum((Decimal(p) * q for _, p, q in itens), Decimal("0"))
    imposto = (total * Decimal("0.07")).quantize(Decimal("0.01"))
    return [total, imposto, total + imposto, total / len(itens)]

#=== js_dumps_basico
import json

def main():
    return [json.dumps(1), json.dumps("a\"b"), json.dumps([1, 2.5, None, True]), json.dumps({"a": 1, "b": [1, 2]}), json.dumps({}), json.dumps([]), json.dumps("é"), json.dumps((1, 2)), json.dumps(1.0), json.dumps(10 ** 10), json.dumps(1e20)]

#=== js_dumps_opcoes
import json

def main():
    d = {"b": 2, "a": {"x": [1, 2], "y": None}}
    return [json.dumps(d, sort_keys=True), json.dumps(d, indent=2), json.dumps([1, [2, 3]], indent=1), json.dumps({"k": "é"}, ensure_ascii=False), json.dumps({"a": 1}, separators=(",", ":"))]

#=== js_dumps_chaves_nao_texto
import json

def main():
    return [json.dumps({1: "a", 2.5: "b", None: "d"}), json.dumps({"z": 1, "a": 2})]

#=== js_loads_basico
import json

def main():
    return [json.loads("1"), json.loads("1.5"), json.loads('"x"'), json.loads("[1, 2, [3]]"), json.loads('{"a": {"b": [true, false, null]}}'), json.loads(" [ ] "), json.loads("1e2"), json.loads('"\\u00e9\\n"'), json.loads("-0.5")]

#=== js_ida_e_volta
import json

def main():
    d = {"nome": "Ana", "tags": ["a", "b"], "n": 3, "pi": 3.14, "ok": True, "nada": None, "sub": {"k": [1, {"z": 2}]}}
    return [json.loads(json.dumps(d)) == d, json.dumps(json.loads(json.dumps(d))) == json.dumps(d), json.loads(json.dumps(d, indent=4)) == d]

#=== js_loads_erro
import json

def main():
    r = []
    for t in ["{", "[1,]", "nul", '{"a" 1}', "", "[1 2]"]:
        try:
            json.loads(t)
            r.append("ok")
        except ValueError:
            r.append("erro")
    return r

#=== js_dumps_tipo_invalido
import json

def main():
    r = []
    for v in [{1, 2}, (lambda: 1), range(3)]:
        try:
            json.dumps(v)
            r.append("ok")
        except TypeError:
            r.append("erro")
    return r

#=== mt_math_funcoes
import math

def main():
    return [math.floor(-0.5), math.ceil(-0.5), math.floor(5), math.ceil(5), math.sqrt(2), math.sqrt(0), math.exp(1), math.log(1), math.log(100, 10), math.log10(0.001), math.sin(0), math.cos(0), math.tan(0), math.atan2(1, 1), math.hypot(3, 4), math.degrees(math.pi), math.radians(180), math.factorial(10), math.copysign(3, -1), math.fsum([0.1] * 10)]

#=== mt_math_erros
import math

def main():
    r = []
    for f in [lambda: math.sqrt(-1), lambda: math.log(0), lambda: math.log(-1), lambda: math.factorial(-1)]:
        try:
            f()
            r.append("ok")
        except ValueError:
            r.append("erro")
    return r

#=== mt_math_isnan_inf
import math

def main():
    return [math.isnan(float("nan")), math.isnan(1.0), math.isinf(float("-inf")), math.isinf(10 ** 10), math.isfinite(1.0), math.isfinite(math.inf), math.inf > 0, -math.inf < 0, math.tau]

#=== mt_round_repr_float_casos
def main():
    return [0.1, 0.5, 1e-7, 123456.789e3, 1.0e22, 1.7976931348623157e308, 5e-324, 2.5e-5, 1234567890123456789.0, 0.000123, 3.0e-10, 100000000000000000000.0, 1e16 + 2]
