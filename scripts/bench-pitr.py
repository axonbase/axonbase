#!/usr/bin/env python3
"""Portão de custo da E2: vazão de escrita da base (0.10.x), do motor novo (carimbo HLC) e com o journal de PITR.

Cada medida é um processo novo, e a ordem das variantes gira a cada rodada. O resultado é a mediana das razões
pareadas por rodada, que resiste melhor ao ruído da máquina que a razão das médias.

No Linux, cada medida mostra também como a máquina gastou o tempo (CPU em usuário, em sistema, esperando disco e
parada) e quanto tempo houve tarefa presa em entrada e saída ou esperando CPU (PSI, Pressure Stall Information).

Uso:
    scripts/bench-pitr.py --base <classpath da versão anterior> --new <classpath atual com as classes de teste>
        [--rounds 7] [--seconds 8] [--threads 8] [--modes kv,sql] [--java <java>] [--work <diretório>]
        [--extra copy-only,journal-tmpfs,journal-nosync,journal-sync4m,journal-sync200ms]

--extra acrescenta variantes do WriteCostBench para separar de onde vem o custo do journal; cada uma é comparada com
o motor só com o carimbo, como o journal.
"""
import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

BENCH = "com.axonbase.core.journal.WriteCostBench"


def cpu_times():
    """Tiques de CPU da máquina inteira (Linux): usuário, sistema, esperando disco, parada e total."""
    try:
        with open("/proc/stat") as stat:
            values = [int(v) for v in stat.readline().split()[1:9]]
    except OSError:
        return None
    user, nice, system, idle, iowait, irq, softirq, steal = values
    return {"usuario": user + nice, "sistema": system + irq + softirq, "disco": iowait, "parada": idle,
            "total": sum(values)}


def stalls():
    """Microssegundos acumulados em que alguma tarefa ficou presa em disco ou esperando CPU (Linux PSI)."""
    totals = {}
    for resource in ("io", "cpu"):
        try:
            with open(f"/proc/pressure/{resource}") as pressure:
                match = re.search(r"some .*total=(\d+)", pressure.read())
        except OSError:
            return None
        if match:
            totals[resource] = int(match.group(1))
    return totals


def machine_report(cpu_before, cpu_after, stall_before, stall_after, elapsed):
    """O que a máquina fez durante a medida inteira (subida e aquecimento incluídos)."""
    parts = []
    if cpu_before and cpu_after:
        total = cpu_after["total"] - cpu_before["total"] or 1
        parts += [f"{name} {100 * (cpu_after[name] - cpu_before[name]) / total:.1f}%"
                  for name in ("usuario", "sistema", "disco", "parada")]
    if stall_before and stall_after:
        parts += [f"preso-{name} {(stall_after[name] - stall_before[name]) / 1e4 / elapsed:.1f}%"
                  for name in stall_after]
    return " ".join(parts)


def run(java, classpath, variant, mode, work, seconds, threads):
    directory = tempfile.mkdtemp(prefix="axon-bench-", dir=work)
    try:
        cpu_before, stall_before, started = cpu_times(), stalls(), time.monotonic()
        out = subprocess.run(
            [java, "-Xmx1g", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, BENCH,
             variant, mode, directory, str(seconds), str(threads)],
            capture_output=True, text=True, timeout=600)
        match = re.search(r"RESULT ops=(\d+)(.*)", out.stdout)
        if not match:
            sys.exit(f"medida falhou ({variant} {mode}):\n{out.stdout}\n{out.stderr}")
        machine = machine_report(cpu_before, cpu_times(), stall_before, stalls(), time.monotonic() - started)
        return int(match.group(1)), f"{match.group(2).strip()} {machine}".strip()
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
    parser.add_argument("--extra", default="")
    args = parser.parse_args()

    # a classe do benchmark vem do classpath novo; as do motor vêm primeiro de --base
    variants = {
        "base": (args.base + os.pathsep + args.new, "plain"),
        "stamp": (args.new, "plain"),
        "journal": (args.new, "journal"),
    }
    extras = [name for name in args.extra.split(",") if name]
    variants.update({name: (args.new, name) for name in extras})
    names = list(variants)
    # o journal e cada variante extra são comparados com o motor só com o carimbo
    comparisons = [("carimbo e !hlc", "stamp", "base"), ("journal", "journal", "stamp")]
    comparisons += [(name, name, "stamp") for name in extras]
    for mode in args.modes.split(","):
        results = {name: [] for name in names}
        for round_number in range(args.rounds):
            start = round_number % len(names)
            order = names[start:] + names[:start]
            for name in order:
                classpath, variant = variants[name]
                ops, extra = run(args.java, classpath, variant, mode, args.work, args.seconds, args.threads)
                results[name].append(ops)
                print(f"{mode} rodada {round_number} {name}: {ops} escritas/s {extra} "
                      f"carga {os.getloadavg()[0]:.0f}", flush=True)
        for label, top, bottom in comparisons:
            ratios = [a / b for a, b in zip(results[top], results[bottom])]
            cost = (1 - statistics.median(ratios)) * 100
            spread = f"razões de {min(ratios):.3f} a {max(ratios):.3f}"
            print(f"RESUMO {mode} custo do {label}: {cost:.1f}% (mediana de {args.rounds} razões, {spread})")
        medians = ", ".join(f"{n} {statistics.median(results[n]):.0f}" for n in names)
        print(f"RESUMO {mode} medianas de escritas/s: {medians}")


if __name__ == "__main__":
    main()
