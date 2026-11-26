#!/usr/bin/env python3
"""Perfil (JFR, Java Flight Recorder) do custo do carimbo e do journal. Só na máquina dedicada: é carga.

Roda uma vez cada variante do scripts/bench-pitr.py gravando um perfil, e resume onde o tempo vai: bloqueio em
monitores, estacionamento de threads, amostras de CPU em Java e dentro de código nativo (RocksDB), CPU por thread,
alocação, coletor de lixo e escrita em arquivo. Os arquivos .jfr ficam no diretório de saída.

Uso:
    scripts/profile-pitr.py --base <classpath da versão anterior> --new <classpath atual com as classes de teste>
        --out <diretório> [--seconds 15] [--threads 8] [--modes kv,sql] [--java <java>] [--jfr <jfr>]
        [--work <diretório>]

    scripts/profile-pitr.py --summarize arquivo.jfr [...]   (só resume gravações já feitas)
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

BENCH = "com.axonbase.core.journal.WriteCostBench"
# limiar baixo: as disputas de trava deste caminho duram microssegundos
SETTINGS = (
    "settings=profile",
    "jdk.JavaMonitorEnter#threshold=20us",
    "jdk.ThreadPark#threshold=20us",
    "jdk.ExecutionSample#period=10ms",
    "jdk.NativeMethodSample#period=10ms",
    "jdk.ThreadCPULoad#period=1s",
    "jdk.FileWrite#threshold=0ms",
    "jdk.FileForce#threshold=0ms",
)
EVENTS = (
    "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ExecutionSample", "jdk.NativeMethodSample",
    "jdk.ThreadCPULoad", "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.FileWrite",
    "jdk.FileForce",
)
TOP = 12


def millis(duration):
    """Converte a duração ISO-8601 do `jfr print --json` (por exemplo PT0.000123S) em milissegundos."""
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(-?[\d.]+)S)?", duration or "PT0S")
    if not match:
        return 0.0
    hours, minutes, seconds = match.groups()
    return (int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(seconds or 0)) * 1000


def frames(values, depth):
    stack = values.get("stackTrace") or {}
    names = []
    for frame in (stack.get("frames") or [])[:depth]:
        method = frame.get("method") or {}
        owner = ((method.get("type") or {}).get("name") or "?").replace("/", ".")
        names.append(f"{owner.rsplit('.', 1)[-1]}.{method.get('name', '?')}:{frame.get('lineNumber', 0)}")
    return " < ".join(names) or "(sem pilha)"


def axon_frame(values):
    """O primeiro quadro do AxonBase na pilha: diz de onde veio o tempo gasto no JDK ou no RocksDB."""
    stack = values.get("stackTrace") or {}
    for frame in stack.get("frames") or []:
        method = frame.get("method") or {}
        owner = ((method.get("type") or {}).get("name") or "").replace("/", ".")
        if owner.startswith("com.axonbase"):
            return f"{owner.rsplit('.', 1)[-1]}.{method.get('name', '?')}:{frame.get('lineNumber', 0)}"
    return "(fora do AxonBase)"


def class_name(value):
    return ((value or {}).get("name") or "?").replace("/", ".")


def thread_name(values, key="eventThread"):
    thread = values.get(key) or {}
    return thread.get("javaName") or thread.get("osName") or "?"


def table(title, rows, unit):
    print(f"\n-- {title}")
    for key, (total, count) in sorted(rows.items(), key=lambda item: -item[1][0])[:TOP]:
        print(f"  {total:12.1f} {unit}  {count:8d}x  {key}")


def summarize(jfr, recording):
    # o padrão do jfr é imprimir 5 quadros, o que esconde de onde veio uma espera dentro do JDK
    out = subprocess.run([jfr, "print", "--json", "--stack-depth", "40", "--events", ",".join(EVENTS), recording],
                         capture_output=True, text=True, check=True)
    events = json.loads(out.stdout)["recording"]["events"]
    monitors, parks, io = (collections.defaultdict(lambda: [0.0, 0]) for _ in range(3))
    java, native, allocation = (collections.defaultdict(lambda: [0.0, 0]) for _ in range(3))
    cpu = collections.defaultdict(lambda: [0.0, 0])
    gc = [0.0, 0.0, 0]

    def add(rows, key, amount):
        rows[key][0] += amount
        rows[key][1] += 1

    for event in events:
        kind, values = event["type"], event["values"]
        if kind == "jdk.JavaMonitorEnter":
            add(monitors, f"{class_name(values.get('monitorClass'))} em {axon_frame(values)}",
                millis(values.get("duration")))
        elif kind == "jdk.ThreadPark":
            add(parks, f"{class_name(values.get('parkedClass'))} em {axon_frame(values)} "
                       f"[{re.sub(r'[-#]?[0-9]+$', '', thread_name(values))}]", millis(values.get("duration")))
        elif kind == "jdk.ExecutionSample":
            add(java, frames(values, 3), 1)
        elif kind == "jdk.NativeMethodSample":
            add(native, f"{frames(values, 1)} em {axon_frame(values)}", 1)
        elif kind == "jdk.ThreadCPULoad":
            add(cpu, thread_name(values), (values.get("user", 0) + values.get("system", 0)) * 100)
        elif kind == "jdk.ObjectAllocationSample":
            add(allocation, f"{class_name(values.get('objectClass'))} em {axon_frame(values)}",
                values.get("weight", 0) / 1e6)
        elif kind == "jdk.GarbageCollection":
            gc[0] += millis(values.get("sumOfPauses"))
            gc[1] = max(gc[1], millis(values.get("longestPause")))
            gc[2] += 1
        else:
            path = os.path.basename(values.get("path") or values.get("filePath") or "?")
            add(io, f"{kind} {re.sub(r'[0-9]+', 'N', path)}", millis(values.get("duration")))

    print(f"\n===== {os.path.basename(recording)}")
    print(f"coletor de lixo: {gc[2]} pausas, {gc[0]:.0f} ms no total, maior {gc[1]:.1f} ms")
    table("espera para entrar em monitor", monitors, "ms")
    table("threads estacionadas", parks, "ms")
    table("amostras de CPU em Java (topo da pilha)", java, "amostras")
    table("amostras dentro de código nativo", native, "amostras")
    table("alocação estimada", allocation, "MB")
    table("escrita e sincronização de arquivos", io, "ms")
    print("\n-- CPU média por thread (por cento da máquina inteira)")
    averages = sorted(((total / count, name) for name, (total, count) in cpu.items()), reverse=True)
    for average, name in averages[:TOP + 8]:
        print(f"  {average:6.2f}  {name}")


def record(args, name, classpath, variant, mode):
    directory = tempfile.mkdtemp(prefix="axon-perfil-", dir=args.work)
    recording = os.path.join(args.out, f"{mode}-{name}-{args.threads}t.jfr")
    try:
        flight = "-XX:StartFlightRecording=" + ",".join((f"filename={recording}",) + SETTINGS)
        out = subprocess.run(
            [args.java, "-Xmx1g", "--enable-native-access=ALL-UNNAMED", flight, "-cp", classpath, BENCH,
             variant, mode, directory, str(args.seconds), str(args.threads)],
            capture_output=True, text=True, timeout=600)
        match = re.search(r"RESULT .*", out.stdout)
        if not match or not os.path.exists(recording):
            sys.exit(f"perfil falhou ({name} {mode}):\n{out.stdout}\n{out.stderr}")
        print(f"\nPERFIL {mode} {name} {args.threads} threads (com o JFR ligado): {match.group(0)}", flush=True)
        return recording
    finally:
        shutil.rmtree(directory, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--new")
    parser.add_argument("--out")
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--modes", default="kv,sql")
    parser.add_argument("--java", default="java")
    parser.add_argument("--jfr", default=None)
    parser.add_argument("--work", default=None)
    parser.add_argument("--summarize", nargs="+")
    args = parser.parse_args()
    if not args.jfr:
        # o jfr vem no mesmo diretório do java; nem toda instalação o põe no PATH
        sibling = os.path.join(os.path.dirname(os.path.realpath(shutil.which(args.java) or args.java)), "jfr")
        args.jfr = sibling if os.path.exists(sibling) else "jfr"

    if args.summarize:
        for recording in args.summarize:
            summarize(args.jfr, recording)
        return
    if not (args.base and args.new and args.out):
        parser.error("--base, --new e --out são obrigatórios para gravar")
    os.makedirs(args.out, exist_ok=True)
    variants = (
        ("base", args.base + os.pathsep + args.new, "plain"),
        ("stamp", args.new, "plain"),
        ("journal", args.new, "journal"),
    )
    for mode in args.modes.split(","):
        for name, classpath, variant in variants:
            summarize(args.jfr, record(args, name, classpath, variant, mode))


if __name__ == "__main__":
    main()
