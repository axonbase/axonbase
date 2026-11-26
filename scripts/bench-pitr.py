#!/usr/bin/env python3
"""Portão de custo da E2: vazão de escrita da base (0.10.x), do motor novo (carimbo HLC) e com o journal de PITR.

Cada medida é um processo novo, e a ordem das variantes gira a cada rodada. O resultado é a mediana das razões
pareadas por rodada, que resiste melhor ao ruído da máquina que a razão das médias.

Uso:
    scripts/bench-pitr.py --base <classpath da versão anterior> --new <classpath atual com as classes de teste>
        [--rounds 7] [--seconds 8] [--threads 8] [--modes kv,sql] [--java <java>] [--work <diretório>]
"""
import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile

BENCH = "com.axonbase.core.journal.WriteCostBench"


def run(java, classpath, variant, mode, work, seconds, threads):
    directory = tempfile.mkdtemp(prefix="axon-bench-", dir=work)
    try:
        out = subprocess.run(
            [java, "-Xmx1g", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, BENCH,
             variant, mode, directory, str(seconds), str(threads)],
            capture_output=True, text=True, timeout=600)
        match = re.search(r"RESULT ops=(\d+)(.*)", out.stdout)
        if not match:
            sys.exit(f"medida falhou ({variant} {mode}):\n{out.stdout}\n{out.stderr}")
        return int(match.group(1)), match.group(2).strip()
    finally:
        shutil.rmtree(directory, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--new", required=True)
    parser.add_argument("--rounds", type=int, default=7)
    parser.add_argument("--seconds", type=int, default=8)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--modes", default="kv,sql")
    parser.add_argument("--java", default="java")
    parser.add_argument("--work", default=None)
    args = parser.parse_args()

    # a classe do benchmark vem do classpath novo; as do motor vêm primeiro de --base
    variants = {
        "base": (args.base + os.pathsep + args.new, "plain"),
        "stamp": (args.new, "plain"),
        "journal": (args.new, "journal"),
    }
    names = list(variants)
    for mode in args.modes.split(","):
        results = {name: [] for name in names}
        for round_number in range(args.rounds):
            order = names[round_number % 3:] + names[:round_number % 3]
            for name in order:
                classpath, variant = variants[name]
                ops, extra = run(args.java, classpath, variant, mode, args.work, args.seconds, args.threads)
                results[name].append(ops)
                print(f"{mode} rodada {round_number} {name}: {ops} escritas/s {extra} "
                      f"carga {os.getloadavg()[0]:.0f}", flush=True)
        for label, top, bottom in (("carimbo e !hlc", "stamp", "base"), ("journal", "journal", "stamp")):
            ratios = [a / b for a, b in zip(results[top], results[bottom])]
            cost = (1 - statistics.median(ratios)) * 100
            spread = f"razões de {min(ratios):.3f} a {max(ratios):.3f}"
            print(f"RESUMO {mode} custo do {label}: {cost:.1f}% (mediana de {args.rounds} razões, {spread})")
        medians = ", ".join(f"{n} {statistics.median(results[n]):.0f}" for n in names)
        print(f"RESUMO {mode} medianas de escritas/s: {medians}")


if __name__ == "__main__":
    main()
