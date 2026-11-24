#!/usr/bin/env python3
"""Gera diff/expected.json: roda cada programa do corpus (*.pyt) no CPython e grava o resultado.

Cada programa define main(). O resultado esperado e repr(main()), mais o que foi impresso
(stdout) e, se a execucao terminar em excecao, o nome do tipo dela.

Uso: python3 axonbase-pl/scripts/gen_expected.py
A suite Java NAO depende do python3: ela le o expected.json versionado.
"""
import contextlib
import io
import json
import os
import re
import sys
import warnings

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "diff")
MARCA = re.compile(r"^#=== (\S+)\s*$")


def carregar():
    casos = []
    for arq in sorted(os.listdir(DIR)):
        if not arq.endswith(".pyt"):
            continue
        nome = None
        linhas = []
        with open(os.path.join(DIR, arq), encoding="utf-8") as f:
            for linha in f:
                m = MARCA.match(linha)
                if m:
                    if nome:
                        casos.append((arq, nome, "".join(linhas)))
                    nome, linhas = m.group(1), []
                else:
                    linhas.append(linha)
        if nome:
            casos.append((arq, nome, "".join(linhas)))
    return casos


def rodar(fonte):
    ns = {"__name__": "axon_diff"}
    buf = io.StringIO()
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            code = compile(fonte, "<corpus>", "exec")
        with contextlib.redirect_stdout(buf):
            exec(code, ns)
            valor = ns["main"]()
            resultado = repr(valor)
        return {"resultado": resultado, "stdout": buf.getvalue(), "erro": None}
    except BaseException as e:  # noqa: BLE001
        return {"resultado": None, "stdout": buf.getvalue(), "erro": type(e).__name__}


def main():
    sys.setrecursionlimit(5000)
    casos = carregar()
    nomes = [c[1] for c in casos]
    if len(nomes) != len(set(nomes)):
        dup = sorted({n for n in nomes if nomes.count(n) > 1})
        raise SystemExit("nomes duplicados no corpus: %s" % dup)
    saida = {"python": sys.version.split()[0], "casos": []}
    for arq, nome, fonte in casos:
        r = rodar(fonte)
        r.update({"nome": nome, "arquivo": arq})
        saida["casos"].append(r)
    with open(os.path.join(DIR, "expected.json"), "w", encoding="utf-8") as f:
        json.dump(saida, f, ensure_ascii=False, indent=1)
        f.write("\n")
    erros = sum(1 for c in saida["casos"] if c["erro"])
    print("%d casos gravados (%d terminam em excecao) com python %s" % (len(casos), erros, saida["python"]))


if __name__ == "__main__":
    main()
