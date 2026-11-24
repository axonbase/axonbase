#=== fl_if_elif_else
def classifica(n):
    if n < 0:
        return "neg"
    elif n == 0:
        return "zero"
    elif n < 10:
        return "pequeno"
    else:
        return "grande"

def main():
    return [classifica(x) for x in [-5, 0, 3, 10, 99]]

#=== fl_while_else_break
def main():
    out = []
    i = 0
    while i < 5:
        if i == 3:
            break
        i += 1
    else:
        out.append("sem break")
    j = 0
    while j < 3:
        j += 1
    else:
        out.append("fim normal")
    return [i, j, out]

#=== fl_for_else
def acha(xs, alvo):
    for x in xs:
        if x == alvo:
            r = "achou"
            break
    else:
        r = "nao achou"
    return r

def main():
    return [acha([1, 2, 3], 2), acha([1, 2, 3], 9), acha([], 1)]

#=== fl_continue_aninhado
def main():
    out = []
    for i in range(4):
        for j in range(4):
            if j == i:
                continue
            if j > 2:
                break
            out.append((i, j))
    return out

#=== fl_ternario_booleanos
def main():
    x = 7
    return ["par" if x % 2 == 0 else "impar", x > 5 and x < 10, x < 5 or x == 7, not x, x and "y", 0 and "y", "" or "padrao", None or [] or "ultimo", 1 if 0 else 2 if 0 else 3]

#=== fl_curto_circuito
def main():
    log = []
    def a():
        log.append("a")
        return False
    def b():
        log.append("b")
        return True
    r1 = a() and b()
    r2 = a() or b()
    r3 = b() or a()
    r4 = b() and a()
    return [r1, r2, r3, r4, log]

#=== fl_funcao_padroes
def f(a, b=2, c=3):
    return [a, b, c]

def main():
    return [f(1), f(1, 5), f(1, c=9), f(c=1, a=0), f(1, 2, 3), f(b=7, a=8)]

#=== fl_funcao_args_kwargs
def f(*args, **kw):
    return [args, sorted(kw.items())]

def g(a, *rest, k=10, **extra):
    return [a, rest, k, sorted(extra.items())]

def main():
    return [f(), f(1, 2), f(x=1, y=2), f(1, x=2), g(1), g(1, 2, 3), g(1, k=5), g(1, 2, z=3), g(*[1, 2], **{"k": 4, "w": 5})]

#=== fl_somente_nomeados
def f(a, *, b, c=3):
    return a + b + c

def h(*, x=1):
    return x

def main():
    return [f(1, b=2), f(1, b=2, c=10), h(), h(x=5)]

#=== fl_padrao_mutavel
def acumula(x, lista=[]):
    lista.append(x)
    return lista

def main():
    a = acumula(1)
    b = acumula(2)
    c = acumula(3, [])
    return [a, b, c, a is b]

#=== fl_anotacoes_ignoradas
def soma(a: int, b: int = 2) -> int:
    return a + b

def concat(xs: list, sep: str = ",") -> str:
    return sep.join(xs)

def main():
    x: int = 5
    y: str
    return [soma(1), soma(1, 5), concat(["a", "b"]), x]

#=== fl_docstring_e_pass
def f():
    """Docstring
    de duas linhas."""
    pass

def g():
    '''outra'''
    return 1

def main():
    return [f(), g()]

#=== fl_recursao_fatorial
def fat(n):
    return 1 if n <= 1 else n * fat(n - 1)

def main():
    return [fat(0), fat(5), fat(10), fat(20)]

#=== fl_recursao_fibonacci
def fib(n):
    if n < 2:
        return n
    return fib(n - 1) + fib(n - 2)

def main():
    return [fib(i) for i in range(15)]

#=== fl_recursao_mutua
def par(n):
    return True if n == 0 else impar(n - 1)

def impar(n):
    return False if n == 0 else par(n - 1)

def main():
    return [par(10), par(7), impar(7)]

#=== fl_recursao_hanoi
def hanoi(n, a, b, c, mov):
    if n == 0:
        return
    hanoi(n - 1, a, c, b, mov)
    mov.append((a, c))
    hanoi(n - 1, b, a, c, mov)

def main():
    m = []
    hanoi(4, "A", "B", "C", m)
    return [len(m), m[:3], m[-1]]

#=== fl_recursao_permutacoes
def perms(xs):
    if len(xs) <= 1:
        return [xs]
    out = []
    for i in range(len(xs)):
        for p in perms(xs[:i] + xs[i + 1:]):
            out.append([xs[i]] + p)
    return out

def main():
    return [perms([1, 2, 3]), len(perms(list(range(5))))]

#=== fl_recursao_ackermann
def ack(m, n):
    if m == 0:
        return n + 1
    if n == 0:
        return ack(m - 1, 1)
    return ack(m - 1, ack(m, n - 1))

def main():
    return [ack(2, 3), ack(3, 3)]

#=== fl_closure_contador
def make():
    n = 0
    def inc():
        nonlocal n
        n += 1
        return n
    return inc

def main():
    a = make()
    b = make()
    return [a(), a(), a(), b(), a()]

#=== fl_closure_captura_tardia
def main():
    fs = []
    for i in range(3):
        fs.append(lambda: i)
    gs = []
    for i in range(3):
        gs.append(lambda i=i: i)
    return [[f() for f in fs], [g() for g in gs]]

#=== fl_closure_aninhada
def a(x):
    def b(y):
        def c(z):
            return x + y + z
        return c
    return b

def main():
    return [a(1)(2)(3), a(10)(20)(30)]

#=== fl_closure_acumulador
def acum(inicio):
    total = [inicio]
    def add(x):
        total[0] += x
        return total[0]
    return add

def main():
    f = acum(100)
    return [f(1), f(2), f(3)]

#=== fl_global_nonlocal
CONT = 0

def inc():
    global CONT
    CONT += 1
    return CONT

def main():
    inc()
    inc()
    def outer():
        x = 1
        def inner():
            nonlocal x
            x = x * 10
        inner()
        inner()
        return x
    return [CONT, outer(), inc()]

#=== fl_global_leitura_modulo
LIMITE = 10
FATOR = 2.5
NOMES = ["a", "b"]

def f(x):
    return x * FATOR + LIMITE + len(NOMES)

def main():
    return [f(2), f(0)]

#=== fl_lambda_variados
def main():
    sq = lambda x: x * x
    add = lambda a, b=1: a + b
    var = lambda *a, **k: (a, sorted(k.items()))
    comp = lambda f, g: lambda x: f(g(x))
    return [sq(5), add(1), add(1, 2), var(1, 2, z=3), comp(sq, add)(3), (lambda: 42)(), list(map(lambda p: p[0] + p[1], [(1, 2), (3, 4)]))]

#=== fl_funcoes_como_valores
def dobra(x):
    return x * 2

def aplica(f, xs):
    return [f(x) for x in xs]

def main():
    ops = {"dobra": dobra, "neg": lambda x: -x}
    return [aplica(dobra, [1, 2]), aplica(ops["neg"], [1, 2]), [f(3) for f in ops.values()], callable(dobra), callable(5), sorted(ops)]

#=== fl_decorador_manual
def logado(f):
    def wrapper(*a, **k):
        r = f(*a, **k)
        return ("res", r)
    return wrapper

def soma(a, b):
    return a + b

def main():
    s2 = logado(soma)
    return [s2(1, 2), s2(b=5, a=1)]

#=== fl_excecao_basica
def main():
    r = []
    try:
        x = 1 / 0
    except ZeroDivisionError:
        r.append("zero")
    try:
        [1][5]
    except IndexError:
        r.append("indice")
    try:
        {}["k"]
    except KeyError:
        r.append("chave")
    try:
        int("abc")
    except ValueError:
        r.append("valor")
    try:
        1 + "a"
    except TypeError:
        r.append("tipo")
    try:
        undefined_name
    except NameError:
        r.append("nome")
    return r

#=== fl_excecao_hierarquia
def main():
    r = []
    for exc in [KeyError("k"), IndexError(1), ZeroDivisionError(), OverflowError(), ValueError(), TypeError(), RuntimeError(), AssertionError(), AttributeError()]:
        try:
            raise exc
        except LookupError:
            r.append("lookup")
        except ArithmeticError:
            r.append("arit")
        except (ValueError, TypeError):
            r.append("vt")
        except Exception as e:
            r.append("outro")
    return r

#=== fl_excecao_mensagem_args
def main():
    try:
        raise ValueError("falhou", 42)
    except ValueError as e:
        a = e.args
        s = str(e)
    try:
        raise KeyError("chave")
    except KeyError as e:
        k = str(e)
        ka = e.args
    try:
        raise RuntimeError("so msg")
    except RuntimeError as e:
        m = str(e)
        r = repr(e)
    return [a, s, k, ka, m, r]

#=== fl_excecao_else_finally
def f(x):
    log = []
    try:
        log.append("try")
        r = 10 / x
    except ZeroDivisionError:
        log.append("except")
        r = None
    else:
        log.append("else")
    finally:
        log.append("finally")
    return [r, log]

def main():
    return [f(2), f(0)]

#=== fl_finally_com_return
def f():
    try:
        return "try"
    finally:
        pass

def g():
    for i in range(3):
        try:
            if i == 1:
                return i
        finally:
            pass
    return -1

def h():
    try:
        return 1
    finally:
        return 2

def main():
    return [f(), g(), h()]

#=== fl_finally_em_loop
def main():
    log = []
    for i in range(3):
        try:
            if i == 1:
                continue
            log.append(("corpo", i))
        finally:
            log.append(("fim", i))
    return log

#=== fl_excecao_relancar
def interno():
    raise ValueError("original")

def meio():
    try:
        interno()
    except ValueError as e:
        raise

def main():
    r = []
    try:
        meio()
    except ValueError as e:
        r.append(str(e))
    try:
        try:
            1 / 0
        except ZeroDivisionError:
            raise RuntimeError("novo")
    except RuntimeError as e:
        r.append(str(e))
    return r

#=== fl_raise_from
def main():
    try:
        try:
            int("x")
        except ValueError as e:
            raise RuntimeError("encadeado") from e
    except RuntimeError as e:
        return [str(e), type(e) is RuntimeError]

#=== fl_excecao_propaga_funcao
def a():
    return b()

def b():
    return c()

def c():
    raise KeyError("fundo")

def main():
    try:
        a()
    except KeyError as e:
        return ["pego", e.args]

#=== fl_assert
def main():
    r = []
    try:
        assert 1 == 1
        assert 1 == 2, "msg customizada"
    except AssertionError as e:
        r.append(str(e))
    try:
        assert False
    except AssertionError as e:
        r.append(str(e))
    return r

#=== fl_excecao_custom_nome
def main():
    r = []
    try:
        raise Exception("generica")
    except Exception as e:
        r.append(str(e))
    try:
        raise ValueError
    except ValueError as e:
        r.append(repr(str(e)))
    try:
        raise TypeError("a", "b")
    except TypeError as e:
        r.append(str(e))
    return r

#=== fl_excecao_nao_tratada_tipo
def main():
    xs = [1, 2, 3]
    return xs[10]

#=== fl_excecao_nao_tratada_custom
def valida(x):
    if x < 0:
        raise ValueError("negativo: " + str(x))
    return x

def main():
    return [valida(1), valida(-1)]

#=== fl_isinstance_type
def main():
    return [isinstance(1, int), isinstance(True, int), isinstance(1.5, float), isinstance("a", str), isinstance([1], list), isinstance((1,), tuple), isinstance({}, dict), isinstance(None, type(None)), isinstance(1, (str, int)), isinstance("a", (int, float)), type(1) is int, type("a") == str, type([]) is list, type(1.5) is float, type(True) is bool, isinstance(KeyError("x"), LookupError)]

#=== fl_conversoes_construtores
def main():
    return [list("abc"), list((1, 2)), list({"a": 1}), tuple([1]), set([1, 1]) == {1}, dict([(1, 2)]), str(12), int("12") + 1, float("1.5") * 2, bool(""), bool([0]), bool(0.0), bool("x"), bool(None), int(), str(), list(), dict(), float(), bool()]

#=== fl_while_true_break
def main():
    n = 0
    total = 0
    while True:
        n += 1
        if n > 100:
            break
        if n % 3 == 0 or n % 5 == 0:
            total += n
    return total

#=== fl_pass_e_blocos_vazios
def vazia():
    pass

class_ = None

def main():
    if True:
        pass
    for _ in range(3):
        pass
    while False:
        pass
    return [vazia(), class_]

#=== fl_ponto_virgula_linhas
def main():
    a = 1; b = 2; c = a + b
    if a: x = 1; y = 2
    return [a, b, c, x, y]

#=== fl_continuacao_linha
def main():
    a = 1 + \
        2 + \
        3
    b = (1 +
         2 +
         3)
    c = [1,
         2,
         3,
         ]
    d = {
        "a": 1,
        "b": 2,
    }
    return [a, b, c, d, sum([1,
        2]), max(1,
        2)]

#=== fl_comentarios_indentacao
def main():
    # comentario
    x = 1  # outro
    if x:
        # dentro
        y = 2

        z = 3  # fim
    # solto
    return [x, y, z]

#=== fl_del_variavel
def main():
    a = 1
    b = 2
    del a
    try:
        a
    except NameError:
        r = "apagado"
    x, y = 1, 2
    del x, y
    return [r, b]

#=== fl_variavel_nao_inicializada
def f(flag):
    if flag:
        v = 1
    return v

def main():
    try:
        return f(False)
    except NameError:
        return "unbound"

#=== fl_operador_in_not_in_is
def main():
    xs = [1, 2, 3]
    return [2 in xs, 5 not in xs, "a" in {"a": 1}, 1 in (1, 2), "ab" in "cabd", None is None, xs is not None, not (1 in xs)]

#=== fl_encadeamento_metodos
def main():
    s = "  Ola, Mundo!  "
    return s.strip().lower().replace(",", "").split()

#=== fl_escopo_loop_variavel
def main():
    for i in range(3):
        pass
    for j in []:
        pass
    try:
        k = j
    except NameError:
        k = "indefinida"
    return [i, k]

#=== fl_retorno_multiplo
def divide(a, b):
    return a // b, a % b

def main():
    q, r = divide(17, 5)
    t = divide(9, 2)
    return [q, r, t, divide(1, 1)[0]]

#=== fl_geracao_sequencia_while
def main():
    xs = []
    n = 1
    while n < 200:
        xs.append(n)
        n = n * 2 + 1
    return xs

#=== fl_numeros_perfeitos
def main():
    return [n for n in range(2, 500) if sum(d for d in range(1, n) if n % d == 0) == n]

#=== fl_triangulo_pascal
def main():
    linha = [1]
    out = [linha]
    for _ in range(6):
        linha = [1] + [a + b for a, b in zip(linha, linha[1:])] + [1]
        out.append(linha)
    return out

#=== fl_crivo
def main():
    n = 50
    ok = [True] * (n + 1)
    ok[0] = ok[1] = False
    i = 2
    while i * i <= n:
        if ok[i]:
            for j in range(i * i, n + 1, i):
                ok[j] = False
        i += 1
    return [i for i, v in enumerate(ok) if v]
