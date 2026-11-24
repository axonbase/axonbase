#!/usr/bin/env python3
"""Cria as tabelas `transito` e `trechos` de um AxonBase com dados simulados de trânsito em São Paulo.

Uso:
    AXON_TOKEN=<jwt> python3 seed.py [--url http://127.0.0.1:8000/sql] [--ns app] [--db main] [--days 21]

`transito` guarda (quando, placa, pos): um ping por minuto por carro, em UTC (Brasília é UTC-3, sem horário de
verão). No pico das 18:00 às 19:30 (Brasília) algumas vias andam devagar, então o mesmo trecho recebe muito mais
pings. `trechos` guarda 15 corredores poligonais (3 por via) para as consultas por trecho nomeado.
Os dados são simulados e a semente é fixa, então a carga é sempre a mesma.
"""
import argparse, json, math, os, random, sys, urllib.request
from datetime import datetime, timedelta, timezone

# (nome, apelido para o id, vértices lon/lat, gravidade no pico 0..1, trecho gargalo como fração do percurso)
ROUTES = [
    ('Marginal Pinheiros', 'pinheiros', [(-46.7440, -23.5370), (-46.7210, -23.5560), (-46.7010, -23.5850), (-46.6980, -23.6120)], 0.92, (0.45, 0.75)),
    ('Av. 23 de Maio', 'maio23', [(-46.6385, -23.5440), (-46.6500, -23.5600), (-46.6550, -23.5800), (-46.6585, -23.5950)], 0.85, (0.30, 0.60)),
    ('Av. Paulista', 'paulista', [(-46.6613, -23.5557), (-46.6549, -23.5610), (-46.6467, -23.5670), (-46.6392, -23.5741)], 0.65, (0.20, 0.55)),
    ('Av. Rebouças', 'reboucas', [(-46.6700, -23.5560), (-46.6760, -23.5700), (-46.6790, -23.5850)], 0.55, (0.50, 0.90)),
    ('Radial Leste', 'radial', [(-46.6290, -23.5450), (-46.6000, -23.5440), (-46.5700, -23.5420)], 0.20, (0.40, 0.70)),
]
M_LAT = 111_000.0
M_LON = 111_000.0 * math.cos(math.radians(23.55))
HALF_WIDTH_M = 150.0  # largura do corredor de cada trecho, para cada lado do eixo
BRT = timezone(timedelta(hours=-3))


def seg_len(a, b):
    return math.hypot((b[0] - a[0]) * M_LON, (b[1] - a[1]) * M_LAT)


def point_at(vertices, meters):
    for a, b in zip(vertices, vertices[1:]):
        d = seg_len(a, b)
        if meters <= d:
            f = meters / d
            return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        meters -= d
    return vertices[-1]


def peak_shape(local_minutes):
    """0..1, máximo às 18:45 de Brasília, quase zero antes das 17:30 e depois das 20:00."""
    return math.exp(-(((local_minutes - 18 * 60 - 45) / 55.0) ** 2))


def speed_mps(rng, route, frac, local_minutes, weekday):
    severity, (b0, b1) = route[3], route[4]
    in_bottleneck = 1.0 if b0 <= frac <= b1 else 0.35
    jam = severity * peak_shape(local_minutes) * in_bottleneck * (1.0 if weekday else 0.2)
    return max(3.0, 42.0 * (1.0 - jam) + rng.uniform(-3, 3)) / 3.6


def build_trechos():
    rows = []
    for name, slug, verts, _, _ in ROUTES:
        total = sum(seg_len(a, b) for a, b in zip(verts, verts[1:]))
        for k in range(3):
            s0, s1 = total * k / 3, total * (k + 1) / 3
            pts = [point_at(verts, s0 + (s1 - s0) * j / 6) for j in range(7)]
            left, right = [], []
            for j, (x, y) in enumerate(pts):
                a, b = pts[max(j - 1, 0)], pts[min(j + 1, len(pts) - 1)]
                dx, dy = (b[0] - a[0]) * M_LON, (b[1] - a[1]) * M_LAT
                n = math.hypot(dx, dy) or 1
                nx, ny = -dy / n, dx / n
                left.append([round(x + nx * HALF_WIDTH_M / M_LON, 6), round(y + ny * HALF_WIDTH_M / M_LAT, 6)])
                right.append([round(x - nx * HALF_WIDTH_M / M_LON, 6), round(y - ny * HALF_WIDTH_M / M_LAT, 6)])
            ring = left + right[::-1] + [left[0]]
            rows.append({'id': f'{slug}_{k + 1}', 'nome': f'{name}, trecho {k + 1}', 'via': name, 'ordem': k + 1,
                         'geo': {'type': 'Polygon', 'coordinates': [ring]}})
    return rows


def build_transito(days):
    rng = random.Random(20261123)
    letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    plates = sorted({''.join(rng.choice(letters) for _ in range(3)) + str(rng.randint(0, 9)) + rng.choice(letters)
                     + f'{rng.randint(0, 99):02d}' for _ in range(600)})
    rows = []
    first_day = datetime(2026, 11, 2, tzinfo=BRT)
    for day_i in range(days):
        day = first_day + timedelta(days=day_i)
        weekday = day.weekday() < 5
        for route in ROUTES:
            verts = route[2]
            total = sum(seg_len(a, b) for a, b in zip(verts, verts[1:]))
            if weekday:
                starts = [rng.randint(17 * 60 + 30, 19 * 60 + 15) for _ in range(14)]
                starts += [rng.choice([rng.randint(6 * 60, 16 * 60), rng.randint(20 * 60, 22 * 60)]) for _ in range(4)]
            else:
                starts = [rng.randint(9 * 60, 21 * 60) for _ in range(3)]
            for start_min in starts:
                reverse = rng.random() < 0.5
                path = list(reversed(verts)) if reverse else verts
                plate = rng.choice(plates)
                travelled, minute = 0.0, start_min
                while travelled < total and minute < start_min + 150:
                    frac = travelled / total
                    lon, lat = point_at(path, travelled)
                    lon += rng.uniform(-0.00008, 0.00008)
                    lat += rng.uniform(-0.00008, 0.00008)
                    when = (day + timedelta(minutes=minute, seconds=rng.randint(0, 20))).astimezone(timezone.utc)
                    rows.append({'quando': when.strftime('%Y-%m-%dT%H:%M:%SZ'), 'placa': plate,
                                 'pos': {'type': 'Point', 'coordinates': [round(lon, 6), round(lat, 6)]}})
                    travelled += speed_mps(rng, route, frac if not reverse else 1 - frac, minute, weekday) * 60
                    minute += 1
    rng.shuffle(rows)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--url', default=os.environ.get('AXON_URL', 'http://127.0.0.1:8000/sql'))
    ap.add_argument('--ns', default=os.environ.get('AXON_NS', 'app'))
    ap.add_argument('--db', default=os.environ.get('AXON_DB', 'main'))
    ap.add_argument('--days', type=int, default=21)
    ap.add_argument('--batch', type=int, default=400)
    args = ap.parse_args()
    token = os.environ.get('AXON_TOKEN')
    if not token:
        sys.exit('defina AXON_TOKEN com o token JWT (JSON Web Token) de um usuário com permissão de escrita')

    def sql(text):
        req = urllib.request.Request(args.url, data=text.encode(), headers={
            'Authorization': 'Bearer ' + token, 'Axon-Ns': args.ns, 'Axon-Db': args.db, 'Content-Type': 'text/plain'})
        with urllib.request.urlopen(req, timeout=300) as r:
            out = json.loads(r.read())
        if isinstance(out, dict) and out.get('status') == 'ERR':
            sys.exit('erro do servidor: %s' % out)
        return out

    sql('DELETE trechos')
    sql('INSERT INTO trechos ' + json.dumps(build_trechos(), separators=(',', ':')))
    rows = build_transito(args.days)
    sql('DELETE transito')
    for i in range(0, len(rows), args.batch):
        sql('INSERT INTO transito ' + json.dumps(rows[i:i + args.batch], separators=(',', ':')))
    print(f'{len(rows)} registros em transito e {len(ROUTES) * 3} trechos em {args.ns}/{args.db}')


if __name__ == '__main__':
    main()
