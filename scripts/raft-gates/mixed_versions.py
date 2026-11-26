#!/usr/bin/env python3
"""Cluster com nós de duas versões: atualização de um nó de cada vez, com escrita contínua.

Uso: mixed_versions.py <diretório de trabalho> <classpath antigo> <classpath novo>

Com o mesmo classpath antigo nos dois argumentos, o roteiro mede a linha de base de um cluster só antigo.

Linhas de diagnóstico: "MIXED ANOMALIA" quando um nó tem o log mais curto que o já aplicado, e "MIXED PERDA"
agrupando as escritas perdidas por quem as confirmou, em que mandato, e onde o log dele estava na hora.

Cada classpath precisa conter a classe MixedVersionNode compilada contra aquela versão. O roteiro sobe três
nós antigos, troca um de cada vez pela versão nova (derrubando também o líder), e no fim confere que nenhuma
escrita confirmada se perdeu e que os três nós têm o mesmo conteúdo. Uma segunda rodada mantém um nó antigo
parado até o log do líder novo ser compactado, para o nó antigo voltar por snapshot.
"""
import os
import random
import subprocess
import sys
import threading
import time

NODES = ["n1", "n2", "n3"]


class Node:
    def __init__(self, work, name, ports, classpath, extra_env, version):
        self.name = name
        self.version = version
        self.reported = set()
        env = dict(os.environ, **extra_env)
        peers = ",".join(str(ports[n]) for n in NODES if n != name)
        self.errors = open(os.path.join(work, name + ".err"), "a")
        self.process = subprocess.Popen(
            ["java", "-Xmx256m", "-cp", classpath, "MixedVersionNode", name, os.path.join(work, name),
             str(ports[name]), peers],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errors, text=True, env=env)
        self.role = ""
        self.commit = -1
        self.last = -1
        self.result = None
        self.lost = []
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        for line in self.process.stdout:
            part = line.split()
            if len(part) == 6 and part[0] == "ACK":
                returned, commit, last, term = (int(v) for v in part[2:])
                INFO[part[1]] = (self.name, self.version, term, returned, commit, last)
                ACKED.add(part[1])
                if last < commit:
                    self.anomaly("confirmou", commit, last, term)
            elif len(part) == 5 and part[0] == "STATE":
                self.role, self.commit, self.last = part[1], int(part[2]), int(part[3])
                if self.last < self.commit:
                    self.anomaly(self.role, self.commit, self.last, int(part[4]))
            elif len(part) == 2 and part[0] == "LOSTKEY":
                self.lost.append(part[1])
            elif part and part[0].startswith("CHECK"):
                self.result = part
        self.role = ""

    def anomaly(self, what, commit, last, term):
        """O log mais curto que o aplicado: uma linha por nó, situação e mandato."""
        if (what, term) not in self.reported:
            self.reported.add((what, term))
            print(f"MIXED ANOMALIA no={self.name} versao={self.version} situacao={what} mandato={term} "
                  f"aplicado={commit} ultimo_do_log={last} confirmadas={len(ACKED)}", flush=True)

    def send(self, command):
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def kill(self):
        self.process.kill()
        self.process.wait(30)
        self.role = ""


ACKED = set()
INFO = {}


def report_losses(lost):
    groups = {}
    for key in lost:
        name, version, term, returned, commit, last = INFO[key]
        groups.setdefault((name, version, term), []).append((returned, commit, last))
    for (name, version, term), rows in sorted(groups.items(), key=lambda item: item[0][2]):
        returned = [r[0] for r in rows]
        short = sum(1 for r in rows if r[2] < r[1])
        print(f"MIXED PERDA escritor={name} versao={version} mandato={term} escritas={len(rows)} "
              f"indice_devolvido={min(returned)}..{max(returned)} aplicado={min(r[1] for r in rows)}.."
              f"{max(r[1] for r in rows)} ultimo_do_log={min(r[2] for r in rows)}..{max(r[2] for r in rows)} "
              f"com_log_curto={short}", flush=True)


def wait(what, condition, nodes, seconds=90):
    limit = time.time() + seconds
    while not condition():
        if time.time() > limit:
            state = {n: (v.role, v.commit, v.last, v.process.poll()) for n, v in nodes.items()}
            raise SystemExit(f"FALHOU: {what}: {state} confirmadas={len(ACKED)}")
        time.sleep(0.1)


def progress(nodes, more, what):
    target = len(ACKED) + more
    wait(what, lambda: len(ACKED) >= target, nodes)


def verify(work, nodes, label):
    for node in nodes.values():
        node.send("stop")
    time.sleep(1)
    wait("convergir (" + label + ")",
         lambda: len({(n.commit, n.last) for n in nodes.values()}) == 1
         and all(n.commit == n.last for n in nodes.values()), nodes, 120)
    time.sleep(0.5)
    expected = os.path.join(work, "acked.txt")
    with open(expected, "w") as out:
        out.write("\n".join(sorted(ACKED)))
    for node in nodes.values():
        node.result = None
        node.lost = []
        node.send("check " + expected)
    wait("conferir (" + label + ")", lambda: all(n.result for n in nodes.values()), nodes, 120)
    results = {name: node.result for name, node in nodes.items()}
    print(f"MIXED {label} confirmadas={len(ACKED)} " + " ".join(
        f"{name}:perdidas={r[1]},chaves={r[2]},resumo={r[3][:12]}" for name, r in results.items()))
    report_losses(sorted(set().union(*(node.lost for node in nodes.values()))))
    if any(r[1] != "0" for r in results.values()) or len({r[3] for r in results.values()}) != 1:
        raise SystemExit("FALHOU: " + label + " perdeu escrita ou divergiu: " + str(results))


def main():
    work, old, new = sys.argv[1:4]
    upgraded_label = "antiga" if new == old else "nova"
    ports = dict(zip(NODES, random.sample(range(20000, 32000), 3)))
    nodes = {}
    try:
        # Rodada 1: atualização de um nó de cada vez.
        first = os.path.join(work, "upgrade")
        os.makedirs(first, exist_ok=True)
        for name in NODES:
            nodes[name] = Node(first, name, ports, old, {}, "antiga")
        progress(nodes, 200, "cluster antigo escrevendo")
        for step, name in enumerate(NODES, 1):
            nodes[name].kill()
            progress(nodes, 100, f"escrita com {name} parado")
            nodes[name] = Node(first, name, ports, new, {}, upgraded_label)
            progress(nodes, 100, f"escrita depois de atualizar {name}")
            print(f"MIXED atualizado {name} ({step}/3), líder={[n for n, v in nodes.items() if v.role == 'LEADER']}")
            # Derruba o líder do momento para exercitar as duas direções (líder novo e líder antigo).
            leaders = [n for n, v in nodes.items() if v.role == "LEADER"]
            if leaders:
                upgraded = NODES.index(leaders[0]) < step
                nodes[leaders[0]].kill()
                progress(nodes, 50, "escrita sem o líder")
                nodes[leaders[0]] = Node(first, leaders[0], ports, new if upgraded else old, {},
                                         upgraded_label if upgraded else "antiga")
                progress(nodes, 50, "escrita com o líder de volta")
        verify(first, nodes, "atualizacao")
        for node in nodes.values():
            node.kill()

        # Rodada 2: um nó antigo volta atrás do log compactado do líder novo.
        ACKED.clear()
        INFO.clear()
        second = os.path.join(work, "snapshot")
        os.makedirs(second, exist_ok=True)
        small = {"AXON_RAFT_RETAIN_ENTRIES": "50", "AXON_RAFT_RETAIN_MINUTES": "0"}
        nodes = {name: Node(second, name, ports, new, small, upgraded_label) for name in ["n1", "n2"]}
        nodes["n3"] = Node(second, "n3", ports, old, {}, "antiga")
        progress(nodes, 100, "cluster misto escrevendo")
        nodes["n3"].kill()
        progress(nodes, 900, "escrita com o nó antigo parado")
        nodes["n3"] = Node(second, "n3", ports, old, {}, "antiga")
        progress(nodes, 200, "escrita depois da volta do nó antigo")
        verify(second, nodes, "snapshot-para-no-antigo")
        print("MIXED OK")
    finally:
        for node in nodes.values():
            if node.process.poll() is None:
                node.process.kill()


if __name__ == "__main__":
    main()
