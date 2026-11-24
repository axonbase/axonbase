#=== st_metodos_caixa
def main():
    s = "Ola Mundo, tudo BEM?"
    return [s.upper(), s.lower(), s.title(), s.capitalize(), s.swapcase(), "ÁÉÍ".lower(), "straße".upper()]

#=== st_strip_variantes
def main():
    s = "  \t xyz  \n"
    return [s.strip(), s.lstrip(), s.rstrip(), "xxabcxx".strip("x"), "abc".strip("ac"), "..a.b..".lstrip("."), "..a.b..".rstrip(".")]

#=== st_split_varios
def main():
    return ["a,b,c".split(","), "a b  c".split(), " a b ".split(), "a,b,c".split(",", 1), "a,b,c".rsplit(",", 1), "".split(","), "".split(), "abc".split("b"), "a--b--c".split("--"), "  a  b  c ".split(None, 1)]

#=== st_join_replace
def main():
    return ["-".join(["a", "b", "c"]), "".join(["x", "y"]), ",".join([]), "aaa".replace("a", "b"), "aaa".replace("a", "b", 2), "abc".replace("", "-"), "hello".replace("l", ""), ", ".join(str(i) for i in range(4))]

#=== st_busca
def main():
    s = "banana"
    return [s.find("an"), s.find("x"), s.rfind("an"), s.index("n"), s.count("a"), s.count("an"), s.find("a", 2), s.find("a", 2, 3), s.startswith("ban"), s.endswith("na"), s.startswith(("x", "b")), s.count("")]

#=== st_in_e_comparacao
def main():
    return ["an" in "banana", "x" not in "banana", "a" < "b", "abc" < "abd", "Z" < "a", "abc" == "abc", "abc" != "ABC", "" < "a", "10" < "9"]

#=== st_indexacao_fatias
def main():
    s = "abcdefghij"
    return [s[0], s[-1], s[2:5], s[:3], s[7:], s[::2], s[::-1], s[-3:], s[1:-1], s[8:3], s[100:], s[-100:2], s[::3], s[5:1:-1], s[:-1]]

#=== st_repeticao_concat
def main():
    return ["ab" * 3, 3 * "ab", "a" + "b" + "c", "x" * 0, "x" * -2, "=" * 10]

#=== st_zfill_justify
def main():
    return ["42".zfill(5), "-42".zfill(6), "+7".zfill(4), "abc".center(9, "*"), "abc".center(8, "*"), "abc".ljust(6, "."), "abc".rjust(6, "."), "abcdef".center(3), "x".center(4)]

#=== st_is_predicados
def main():
    return ["123".isdigit(), "12a".isdigit(), "abc".isalpha(), "ab1".isalpha(), "ab1".isalnum(), " \t".isspace(), "".isdigit(), "ABC".isupper(), "abc".islower(), "Abc".isupper(), "a b".isalnum()]

#=== st_partition_splitlines
def main():
    return ["a=b=c".partition("="), "abc".partition("x"), "a=b=c".rpartition("="), "l1\nl2\r\nl3".splitlines(), "l1\nl2\n".splitlines(), "l1\nl2".splitlines(True), "".splitlines()]

#=== st_format_metodo
def main():
    return ["{} e {}".format(1, "dois"), "{0} {1} {0}".format("a", "b"), "{nome} tem {idade}".format(nome="Ana", idade=30), "{:>6}|{:<6}|{:^6}".format("a", "b", "c"), "{:.2f}".format(3.14159), "{:05d}".format(42), "{!r}".format("x"), "{{}}".format(), "{:,}".format(1234567)]

#=== st_format_indice_chave
def main():
    d = {"k": [10, 20]}
    return ["{0[1]}".format([5, 6]), "{x[k]}".format(x={"k": 9}), "{:>{}}".format("r", 4), "{:{}.{}f}".format(3.14159, 8, 2)]

#=== st_percent_formato
def main():
    return ["%d items" % 5, "%s e %s" % ("a", "b"), "%5.1f|" % 3.14159, "%-5d|" % 42, "%05d" % 42, "%x" % 255, "%%", "100%%" % (), "%r" % "x", "%s" % [1, 2]]

#=== st_fstring_basica
def main():
    nome = "Ana"
    n = 3
    return [f"Ola {nome}", f"{n} + {n} = {n + n}", f"{nome!r}", f"{nome.upper()}", f"{{literal}}", f"{n:03d}", f"{'a' + 'b'}", f"{n * 2.5}"]

#=== st_fstring_formatos
def main():
    x = 3.14159265
    n = 1234567
    return [f"{x:.2f}", f"{x:8.3f}|", f"{x:<10.1f}|", f"{x:^10.2f}|", f"{n:,}", f"{n:_}", f"{n:>12,}", f"{x:e}", f"{x:.3e}", f"{x:g}", f"{0.5:.0%}", f"{255:x}", f"{255:#x}", f"{255:08b}", f"{5:+d}", f"{-5:+d}", f"{5: d}"]

#=== st_fstring_alinhamento
def main():
    s = "ab"
    return [f"{s:>5}", f"{s:<5}|", f"{s:^5}|", f"{s:*^7}", f"{s:->6}", f"{s:.1}", f"{42:*<6}", f"{42:^7}|", f"{'x':5}|", f"{7:5}|"]

#=== st_fstring_aninhada
def main():
    w = 8
    p = 2
    v = 2.71828
    return [f"{v:{w}.{p}f}", f"{v:{w}}", f"{'t':{w}}|", f"{[1, 2][0]}", f"{ {'a': 1}['a'] }", f"{1 if True else 2}"]

#=== st_fstring_debug
def main():
    x = 5
    nome = "a"
    return [f"{x=}", f"{x + 1=}", f"{nome=}", f"{x=:03d}"]

#=== st_fstring_multilinha
def main():
    a = 1
    b = 2
    return f"""linha {a}
linha {b}
fim"""

#=== st_escapes
def main():
    return ["a\tb", "a\\b", "\x41é", 'it\'s', "say \"hi\"", "\101", len("\n"), "tab\there".split("\t"), "€"]

#=== st_raw_strings
def main():
    return [r"a\nb", r"\d+\.\d", len(r"\\"), R"x\ty", r'\'']

#=== st_triplas_aspas
def main():
    a = """um
dois"""
    b = '''x
y'''
    return [a, b, len(a), a.split("\n")]

#=== st_concat_implicita
def main():
    x = ("ab" "cd"
         "ef")
    return [x, "a" 'b' """c"""]

#=== st_repr_strings
def main():
    return [repr("a"), repr("it's"), repr('say "x"'), repr("a\nb"), repr("tab\t"), repr('both \' and "'), repr(""), repr("\\"), repr("é"), repr("\x01")]

#=== st_str_de_tipos
def main():
    return [str(1), str(1.5), str(True), str(None), str([1, "a"]), str((1,)), str({"a": 1}), str(1e20), str(-0.0), str(10 ** 15), str(2 ** 0.5)]

#=== st_ord_chr_ciclo
def main():
    s = "Hello"
    c = "".join(chr(ord(ch) + 1) for ch in s)
    return [c, [ord(ch) for ch in s], sum(ord(ch) for ch in s)]

#=== st_palindromo_vogais
def eh_palindromo(s):
    t = [c.lower() for c in s if c.isalnum()]
    return t == t[::-1]

def main():
    return [eh_palindromo("A man, a plan, a canal: Panama"), eh_palindromo("abc"), sum(1 for c in "education" if c in "aeiou")]

#=== st_contagem_palavras
def main():
    texto = "o rato roeu a roupa do rei de roma o rato"
    cont = {}
    for p in texto.split():
        cont[p] = cont.get(p, 0) + 1
    ordenado = sorted(cont.items(), key=lambda kv: (-kv[1], kv[0]))
    return ordenado[:4]

#=== st_cifra_cesar
def cesar(s, k):
    out = []
    for c in s:
        if c.isalpha():
            base = ord("a") if c.islower() else ord("A")
            out.append(chr((ord(c) - base + k) % 26 + base))
        else:
            out.append(c)
    return "".join(out)

def main():
    return [cesar("Hello, World!", 3), cesar(cesar("abc xyz", 5), -5)]

#=== st_comparacao_ordem_sorted
def main():
    return [sorted(["banana", "Apple", "cherry", "apple"]), sorted(["b", "a", "B", "A"], key=str.lower) if False else sorted(["b", "a", "B", "A"], key=lambda s: s.lower()), sorted("hello"), sorted(["10", "9", "100"]), max("hello"), min("hello")]

#=== st_unicode_basico
def main():
    s = "olá, mundo ñ"
    return [len(s), s.upper(), s[::-1], s.encode_like() if False else s.count("o"), "ÀÉ".lower(), "é" < "z"]

#=== st_startswith_variantes
def main():
    s = "arquivo.tar.gz"
    return [s.endswith(".gz"), s.endswith((".zip", ".gz")), s.startswith("arq", 0), s.startswith("quivo", 3), s.removesuffix(".gz"), s.removeprefix("arquivo."), s.rsplit(".", 1)[-1], s.split(".")[0]]

#=== st_enumerate_zip_string
def main():
    a = "abc"
    b = "xyz"
    return [list(enumerate(a)), list(zip(a, b)), [x + y for x, y in zip(a, b)], list(enumerate(a, 1)), dict(zip(a, range(3)))]

#=== st_bytes_basico
def main():
    b = b"abc"
    return [len(b), b[0], list(b), b + b"d", b == b"abc", b"x" in b"xyz", b.decode(), bytes([104, 105]), b"ab\x00"]
