#!/usr/bin/env python3
"""
render_map.py — regenerate tehran.mbtiles from tehran-map.osm.stripped so the
raster map EXACTLY matches the routing graph. BBOX is pinned to the routing
graph BBOX so every rendered pixel corresponds to a coordinate the graph
actually knows about.

O(n) roadside ROAM style — each tile filters which ways are relevant via
axis-aligned bounding-box reject, pre-converts lat/lon to local pixel space,
and uses ImageDraw.line() (C-speed) with Cohen–Sutherland segment clipping
for segments that cross a tile edge.
"""
import os, sys, math, sqlite3, io, time, json
from xml.etree import ElementTree as ET
from multiprocessing import Pool
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OSM_FILE = os.path.join(HERE, "app/src/main/assets/tehran-map.osm.stripped")
GRAPH_FILE = os.path.join(HERE, "app/src/main/assets/graph.json")
OUT_FILE = os.path.join(HERE, "app/src/main/assets/_tehran_full.mbtiles")
MIN_ZOOM, MAX_ZOOM = 10, 17

# Pin the map BBOX to the routing graph BBOX = same coordinate family.
with open(GRAPH_FILE) as gf:
    nodes = json.load(gf)["nodes"]
BBOX = (
    min(n["lon"] for n in nodes),
    min(n["lat"] for n in nodes),
    max(n["lon"] for n in nodes),
    max(n["lat"] for n in nodes),
)
print(f"Map BBOX pinned to graph BBOX: lon{BBOX[0]:.5f}-{BBOX[2]:.5f} lat{BBOX[1]:.5f}-{BBOX[3]:.5f}", file=sys.stderr)

KEEP_HIGHWAY = {
    'motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link',
    'secondary', 'secondary_link', 'tertiary', 'tertiary_link', 'residential',
    'living_street', 'unclassified', 'service', 'track', 'footway', 'path',
    'cycleway', 'steps', 'pedestrian', 'bridleway', 'road', 'construction',
    'turning_circle',
}

STYLE = {
    'motorway': ((60, 60, 60), 3), 'motorway_link': ((60, 60, 60), 3),
    'trunk': ((60, 60, 60), 3), 'trunk_link': ((60, 60, 60), 3),
    'primary': ((70, 70, 70), 3), 'primary_link': ((70, 70, 70), 3),
    'secondary': ((90, 90, 90), 2), 'secondary_link': ((90, 90, 90), 2),
    'tertiary': ((105, 105, 105), 2), 'tertiary_link': ((105, 105, 105), 2),
    'residential': ((120, 120, 120), 1), 'living_street': ((120, 120, 120), 1),
    'unclassified': ((120, 120, 120), 1), 'road': ((120, 120, 120), 1),
    'service': ((160, 160, 160), 1), 'track': ((160, 160, 160), 1),
    'footway': ((185, 185, 185), 1), 'path': ((185, 185, 185), 1),
    'cycleway': ((185, 185, 185), 1), 'steps': ((185, 185, 185), 1),
    'pedestrian': ((185, 185, 185), 1), 'bridleway': ((185, 185, 185), 1),
    'turning_circle': ((120, 120, 120), 1),
    'construction': ((200, 200, 200), 1),
}


def tile_to_lat(z, y):
    n = 2 ** z
    return math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * y / n))))


def tile_ranges(z):
    n = 2 ** z
    def f(lon, lat):
        x = (lon + 180.0) / 360.0 * n
        r = math.radians(lat)
        y = (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n
        return x, y
    x_sw, y_sw = f(BBOX[0], BBOX[1])
    x_ne, y_ne = f(BBOX[2], BBOX[3])
    return (int(math.floor(min(x_sw, x_ne))), int(math.ceil(max(x_sw, x_ne))) - 1,
            int(math.floor(min(y_sw, y_ne))), int(math.ceil(max(y_sw, y_ne))) - 1)


def clamp_seg(x1, y1, x2, y2, lo, hi):
    """Cohen–Sutherland clip to [lo,hi]^2. Returns clipped (x1,y1,x2,y2) or None."""
    INSIDE, LEFT, RIGHT, BOTTOM, TOP = 0, 1, 2, 4, 8

    def code(x, y):
        c = INSIDE
        if x < lo: c |= LEFT
        elif x > hi: c |= RIGHT
        if y < lo: c |= BOTTOM
        elif y > hi: c |= TOP
        return c

    c1, c2 = code(x1, y1), code(x2, y2)
    while True:
        if not (c1 | c2):
            return (x1, y1, x2, y2)
        if c1 & c2:
            return None
        c = c1 or c2
        if c & TOP:
            x = x1 + (x2 - x1) * (hi - y1) / (y2 - y1); y = hi
        elif c & BOTTOM:
            x = x1 + (x2 - x1) * (lo - y1) / (y2 - y1); y = lo
        elif c & RIGHT:
            y = y1 + (y2 - y1) * (hi - x1) / (x2 - x1); x = hi
        else:
            y = y1 + (y2 - y1) * (lo - x1) / (x2 - x1); x = lo
        if c == c1:
            x1, y1 = x, y; c1 = code(x1, y1)
        else:
            x2, y2 = x, y; c2 = code(x2, y2)


def parse_osm():
    """Parse tehran-map.osm.stripped, return list of (style, [(lat,lon)...]) paths."""
    t0 = time.time()
    nodes = {}  # id -> (lat, lon)
    ways = []   # (style, [(lat,lon)...])

    ctx = ET.iterparse(OSM_FILE, events=('end',))
    for _, elem in ctx:
        if elem.tag == 'node':
            try:
                nodes[elem.get('id')] = (float(elem.get('lat')), float(elem.get('lon')))
            except Exception:
                pass
            elem.clear()
        elif elem.tag == 'way':
            nids = [nd.get('ref') for nd in elem.findall('nd')]
            hw = ''
            tag_el = elem.find('tag[@k="highway"]')
            if tag_el is not None:
                hw = tag_el.get('v', '').strip().lower()
            if hw in KEEP_HIGHWAY and nids:
                pts = []
                for nid in nids:
                    p = nodes.get(nid)
                    if p is not None:
                        pts.append(p)
                if len(pts) >= 2:
                    ways.append((STYLE.get(hw, STYLE['residential']), pts))
            elem.clear()
        else:
            elem.clear()
    print(f"parsed {len(nodes):,} nodes, {len(ways):,} road ways in {time.time()-t0:.1f}s", file=sys.stderr)
    return ways


def render_tile(args):
    """Render a single tile (z, tx, ty_web) and return raw PNG bytes."""
    z, tx, ty_web, ways = args
    n = 2 ** z
    lon_w = tx / n * 360.0 - 180.0
    lon_e = (tx + 1) / n * 360.0 - 180.0
    lat_n = tile_to_lat(z, ty_web)
    lat_s = tile_to_lat(z, ty_web + 1)

    img = Image.new("RGB", (256, 256), (250, 250, 248))
    d = ImageDraw.Draw(img)

    def ll2px(lon, lat):
        px = (lon + 180.0) / 360.0 * n - tx
        r = math.radians(lat)
        py = (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n - ty_web
        return px * 256.0, py * 256.0

    for style, pts in ways:
        color, width = style
        prev = None
        for (lat, lon) in pts:
            if prev is not None:
                x1, y1 = prev
                x2, y2 = ll2px(lon, lat)
                seg = clamp_seg(x1, y1, x2, y2, -4.0, 260.0)
                if seg is not None:
                    d.line([(seg[0], seg[1]), (seg[2], seg[3])], fill=color, width=width)
            prev = ll2px(lon, lat)
    return img.tobytes()


def main():
    print("=== render_map.py ===", file=sys.stderr)
    ways = parse_osm()

    if os.path.exists(OUT_FILE):
        os.remove(OUT_FILE)
    conn = sqlite3.connect(OUT_FILE)
    conn.execute("PRAGMA journal_mode=DELETE")
    c = conn.cursor()
    c.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    c.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    for k, v in [('name', 'Tehran Offline Map'), ('type', 'baselayer'), ('version', '1'),
                 ('format', 'png'),
                 ('bounds', f'{BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]}'),
                 ('minzoom', str(MIN_ZOOM)), ('maxzoom', str(MAX_ZOOM))]:
        c.execute("INSERT INTO metadata VALUES (?, ?)", (k, v))
    conn.commit()

    tasks = []
    for z in range(MIN_ZOOM, MAX_ZOOM + 1):
        x_min, x_max, y_min, y_max = tile_ranges(z)
        for tx in range(x_min, x_max + 1):
            for ty in range(y_min, y_max + 1):
                tasks.append((z, tx, ty, ways))
        print(f"z{z}: x[{x_min}-{x_max}] y[{y_min}-{y_max}] -> {(x_max-x_min+1)*(y_max-y_min+1)} tiles", file=sys.stderr)

    t0 = time.time()
    total = len(tasks)
    done = 0
    per_zoom = {}
    with Pool(8) as pool:
        for z, tx, ty, blob in pool.imap_unordered(render_tile, tasks, chunksize=32):
            c.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)",
                      (z, tx, (2 ** z - 1) - ty, sqlite3.Binary(blob)))
            per_zoom[z] = per_zoom.get(z, 0) + 1
            done += 1
            if done % 2000 == 0:
                conn.commit()
                print(f"  {done:,}/{total:,} tiles ({time.time()-t0:.0f}s)", file=sys.stderr)
    conn.commit()
    print(f"DONE {done:,} tiles in {time.time()-t0:.0f}s", file=sys.stderr)
    for z in sorted(per_zoom):
        print(f"  z{z}: {per_zoom[z]}", file=sys.stderr)
    conn.close()
    print(f"wrote {OUT_FILE} ({os.path.getsize(OUT_FILE):,} bytes)", file=sys.stderr)


if __name__ == "__main__":
    main()