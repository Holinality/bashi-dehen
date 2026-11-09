"""Rebuild the public offline catalogue from shipped ODbL rail sections only.

No operator map image, proprietary schematic, timetable or AMap snapshot is read.
Every graph edge must exist in an OSM section; missing links remain disconnected.
Run from any working directory: python data/rebuild_osm_catalog.py
"""
import collections
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AREAS = ("BEIJING", "GUANGFO", "SHANGHAI", "SHENZHEN")
PALETTE = ("#2466A8", "#007F78", "#A45B1C", "#7753A6", "#A33D67", "#546A35")

def km(a, b):
    a, b, c, d = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((c-a)/2)**2 + math.cos(a)*math.cos(c)*math.sin((d-b)/2)**2
    return 12742 * math.asin(min(1, math.sqrt(h)))

def edge(a, b):
    return tuple(sorted((a, b)))

def chains(adjacency):
    """Maximal unbranched physical paths, not invented operator through-services."""
    used, result = set(), []
    def walk(a, b):
        path = [a, b]
        used.add(edge(a, b))
        while len(adjacency[b]) == 2:
            nxt = next(v for v in sorted(adjacency[b]) if v != a)
            if edge(b, nxt) in used:
                return path, nxt == path[0]
            used.add(edge(b, nxt))
            if nxt == path[0]:
                return path, True
            path.append(nxt)
            a, b = b, nxt
        return path, False
    for start in sorted(adjacency):
        if len(adjacency[start]) == 2:
            continue
        for following in sorted(adjacency[start]):
            if edge(start, following) not in used:
                result.append(walk(start, following))
    for start in sorted(adjacency):
        for following in sorted(adjacency[start]):
            if edge(start, following) not in used:
                result.append(walk(start, following))
    return result

def main():
    metadata = json.loads((ROOT/'data/osm-line-metadata.json').read_text(encoding='utf-8'))
    output, checks = [], []
    for area in AREAS:
        source = ROOT/f'assets/metro-geography-{area}.json'
        data = json.loads(source.read_text(encoding='utf-8'))
        assert data.get('coordinates') == 'WGS84'
        positions, sections = collections.defaultdict(dict), collections.defaultdict(dict)
        for line, name, lat, lon in data['stations']:
            positions[line][name] = (lat, lon)
        for line, first, last, coords in data['sections']:
            assert len(coords) >= 4 and len(coords) % 2 == 0
            if first != last and first in positions[line] and last in positions[line]:
                sections[line].setdefault(edge(first, last), coords)
        for physical in sorted(sections):
            adjacency = collections.defaultdict(set)
            for first, last in sections[physical]:
                adjacency[first].add(last); adjacency[last].add(first)
            pieces = chains(adjacency)
            details = metadata.get(area, {}).get(physical, {})
            colour = details.get('colour') or PALETTE[int(hashlib.sha256(physical.encode()).hexdigest()[:4],16)%len(PALETTE)]
            emitted = set()
            for number, (names, ring) in enumerate(pieces, 1):
                display = physical if len(pieces) == 1 else f'{physical}（OSM区间{number}）'
                stops = []
                for i, name in enumerate(names):
                    lat, lon = positions[physical][name]
                    segment = 0
                    if i+1 < len(names) or ring:
                        nxt = names[(i+1)%len(names)]
                        key = edge(name, nxt)
                        assert key in sections[physical], (physical, name, nxt)
                        assert key not in emitted, (physical, 'duplicated physical interval')
                        emitted.add(key)
                        coords = sections[physical][key]
                        points = list(zip(coords[::2], coords[1::2]))
                        segment = sum(km(a,b) for a,b in zip(points,points[1:]))
                    stops.append({'n':name, 'lat':lat, 'lon':lon, 'segKm':round(segment,4),
                                  'segMin':0, 'f':'', 'l':'', 'fr':'', 'lr':''})
                output.append({'name':display, 'networkId':physical,
                               'serviceLabel':f'{names[0]} ↔ {names[-1]}', 'colour':colour,
                               'brn':'OSM轨道拓扑分段，非官方运营交路' if len(pieces)>1 else 'OSM轨道拓扑',
                               'ring':int(ring), 'hasTimetable':False,
                               'dataSource':'OpenStreetMap contributors / ODbL-1.0',
                               'sc':{'f':'','l':'','fr':'','lr':''}, 'stops':stops})
            assert emitted == set(sections[physical]), (physical,'lost edge')
            checks.append({'area':area, 'line':physical, 'segments':len(pieces),
                           'intervals':len(emitted), 'stationRows':len(positions[physical]),
                           'connectedStations':len(adjacency)})
    names = [line['name'] for line in output]
    assert len(names) == len(set(names)), 'Public display identities must be unique'
    result = {'ver':1, 'schema':1, 'updated':'2026-11-05', 'crs':'WGS84',
              'source':'OpenStreetMap contributors / Geofabrik', 'license':'ODbL-1.0',
              'hasTimetable':False, 'lines':output}
    (ROOT/'assets/metro_data.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    (ROOT/'data/osm-catalogue-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'OSM catalogue: {len(output)} topology segments, {sum(len(x["stops"]) for x in output)} station rows, '
          f'{sum(x["intervals"] for x in checks)} physical intervals; all edges verified, no service times invented.')

if __name__ == '__main__':
    main()
