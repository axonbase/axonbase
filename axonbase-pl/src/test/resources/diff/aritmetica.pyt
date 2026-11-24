#=== ar_soma_basica
def main():
    return 1 + 2 * 3 - 4

#=== ar_precedencia
def main():
    return (2 + 3) * 4 ** 2 // 3 % 5

#=== ar_divisao_verdadeira
def main():
    return [7 / 2, 8 / 2, -7 / 2, 1 / 3, 10 / 4]

#=== ar_piso_negativos
def main():
    return [7 // 2, -7 // 2, 7 // -2, -7 // -2, 7 % 3, -7 % 3, 7 % -3, -7 % -3]

#=== ar_piso_float
def main():
    return [7.5 // 2, -7.5 // 2, 7.5 % 2, -7.5 % 2, 7.5 % -2, 5.0 // 0.3]

#=== ar_potencias
def main():
    return [2 ** 10, 2 ** 0, 2 ** -1, (-2) ** 3, 2 ** 0.5, 10 ** 15, 0 ** 0, (-1) ** 100]

#=== ar_divmod
def main():
    return [divmod(17, 5), divmod(-17, 5), divmod(17, -5), divmod(7.5, 2)]

#=== ar_abs_round
def main():
    return [abs(-5), abs(3.5), abs(-0.0), round(2.5), round(3.5), round(-2.5), round(2.675, 2), round(1234, -2), round(0.5), round(1.5)]

#=== ar_round_float_casas
def main():
    return [round(3.14159, 3), round(2.5, 0), round(1e10 + 0.5, 0), round(-1.005, 2), round(123.456, 1)]

#=== ar_bitwise
def main():
    return [5 & 3, 5 | 3, 5 ^ 3, ~5, 1 << 10, 1024 >> 3, -16 >> 2, 6 & -2]

#=== ar_comparacoes_encadeadas
def main():
    x = 5
    return [1 < x < 10, 1 < x < 3, 5 == x == 5, 1 < 2 < 3 < 4, 3 > 2 > 2]

#=== ar_float_repr
def main():
    return [0.1 + 0.2, 1e16, 1e-5, 123456789.123456789, 1.5e300, 2.0 ** 70, 1 / 3, 100.0, 1e15, 12345678901234567.0]

#=== ar_float_especiais
def main():
    inf = float("inf")
    nan = float("nan")
    return [inf, -inf, nan != nan, inf > 10 ** 18, -inf < -10 ** 18, inf + 1, inf - inf != inf - inf]

#=== ar_int_float_mistura
def main():
    return [1 + 2.0, 3 * 1.5, 10 - 0.5, True + True, True * 5, 7 / 2.0, 2 ** 3.0]

#=== ar_int_conversoes
def main():
    return [int(3.9), int(-3.9), int("42"), int(" 17 "), int("-5"), int("ff", 16), int("101", 2), int("0x1f", 16), int(True)]

#=== ar_float_conversoes
def main():
    return [float("3.5"), float(" 2 "), float("1e3"), float("-0.5"), float(3), float("inf"), float("1_000.5")]

#=== ar_hex_bin_oct
def main():
    return [hex(255), hex(-255), bin(10), bin(-10), oct(8), hex(0), chr(65), ord("a"), chr(8364), ord("€")]

#=== ar_sum_min_max
def main():
    xs = [3, 1, 4, 1, 5, 9, 2, 6]
    return [sum(xs), min(xs), max(xs), sum(xs, 10), min(3, 1, 2), max(2.5, 1), sum([0.1] * 10), sum([0.1, 0.2, 0.3]), sum([1, 2.5, 3])]

#=== ar_min_max_key
def main():
    ws = ["pera", "uva", "banana", "kiwi", "maca"]
    return [min(ws, key=len), max(ws, key=len), min(ws), max(ws), max([], default=-1), min([5, 3], default=0)]

#=== ar_pow_modular
def main():
    return [pow(2, 10), pow(2, 10, 1000), pow(3, 100, 7), pow(2, -2), pow(5, 3, 13)]

#=== ar_inteiros_grandes_64
def main():
    return [2 ** 62, 2 ** 62 + (2 ** 62 - 1), 9223372036854775807, -9223372036854775808, 3037000499 ** 2]

#=== ar_fatorial_iter
def main():
    r = 1
    for i in range(1, 21):
        r *= i
    return r

#=== ar_fibonacci_iter
def main():
    a, b = 0, 1
    out = []
    for _ in range(20):
        out.append(a)
        a, b = b, a + b
    return out

#=== ar_primos
def main():
    primos = []
    for n in range(2, 60):
        ok = True
        for d in range(2, int(n ** 0.5) + 1):
            if n % d == 0:
                ok = False
                break
        if ok:
            primos.append(n)
    return primos

#=== ar_mdc
def mdc(a, b):
    while b:
        a, b = b, a % b
    return a

def main():
    return [mdc(48, 18), mdc(17, 5), mdc(0, 9), mdc(100, 75)]

#=== ar_matematica_modulo
import math

def main():
    return [math.floor(2.7), math.ceil(2.1), math.floor(-2.1), math.ceil(-2.9), math.sqrt(16), math.pow(2, 3), math.trunc(-2.7), math.gcd(12, 18), math.fabs(-3)]

#=== ar_matematica_constantes
import math

def main():
    return [math.pi, math.e, math.isnan(math.nan), math.isinf(math.inf), round(math.sin(math.pi / 2), 10), round(math.log(math.e), 10), math.log10(1000), math.exp(0), math.log(8, 2)]

#=== ar_bool_aritmetica
def main():
    return [True + 1, False * 10, sum([True, False, True]), int(True), True and 5, 0 or "x", None or 0, not 0, not "a"]

#=== ar_atribuicao_aumentada
def main():
    x = 10
    x += 5
    x -= 3
    x *= 2
    x //= 5
    x **= 3
    x %= 50
    y = 7.5
    y /= 2
    z = 5
    z <<= 2
    z >>= 1
    z |= 1
    z &= 7
    z ^= 2
    return [x, y, z]

#=== ar_swap_encadeada
def main():
    a = b = c = 3
    a, b = b + 1, a + 2
    x, y, z = 1, 2, 3
    x, y, z = z, x, y
    return [a, b, c, x, y, z]

#=== ar_zero_divisao_tipos
def main():
    out = []
    for expr in ["1 / 0", "1 // 0", "1 % 0", "1.0 / 0", "2 ** -1 / 0"]:
        try:
            out.append(eval_like(expr))
        except ZeroDivisionError:
            out.append("zero")
    return out

def eval_like(e):
    if e == "1 / 0":
        return 1 / 0
    if e == "1 // 0":
        return 1 // 0
    if e == "1 % 0":
        return 1 % 0
    if e == "1.0 / 0":
        return 1.0 / 0
    return 2 ** -1 / 0

#=== ar_float_hash_igualdade
def main():
    return [1 == 1.0, 0.1 + 0.2 == 0.3, abs(0.1 + 0.2 - 0.3) < 1e-9, 1 != 2, 3 >= 3.0, True == 1, 2 > True]

#=== ar_numeros_literais
def main():
    return [0xff, 0b101, 0o17, 1_000_000, 1e3, 1.5e-3, .5, 5., 0x_ff, 1_0.5_0]

#=== ar_loop_acumulador_float
def main():
    total = 0.0
    for i in range(1, 11):
        total += 1 / i
    return total

#=== ar_media_variancia
def main():
    xs = [2, 4, 4, 4, 5, 5, 7, 9]
    n = len(xs)
    media = sum(xs) / n
    var = sum((x - media) ** 2 for x in xs) / n
    return [media, var, var ** 0.5]

#=== ar_collatz
def main():
    n = 27
    passos = 0
    while n != 1:
        n = n // 2 if n % 2 == 0 else 3 * n + 1
        passos += 1
    return passos
