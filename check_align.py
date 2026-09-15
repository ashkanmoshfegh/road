#!/usr/bin/env python3
"""Decode one tile from tehran.mbtiles and overlay graph.json roads to
check whether the routing graph aligns with the rendered map."""
import sqlite3, shutil, os, math, json, io, zlib, struct
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
MB = os.path.join(HERE, "app/src/main/assets/tehran.mbtiles")
GJ = os.path.join(HERE, "app/src/main/assets/graph.json")

def lon2x(z, lon): return (lon + 180.0) / 360.0 * (2**z)
def lat2y(z, lat):
    n = 2**z
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0/math.cos(r)) / math.pi) / 2.0 * n

# ---- pick a tile near Tehran center (35.70, 51.39) at z15 ----
Z = 15
CLAT, CLON = 35.705, 51.389
x = int(lon2x(Z, CLON)); y_web = int(lat2y(Z, CLAT))
y_tms = (2**Z - 1) - y_web
tile_lat_n = math.degrees(math.atan(math.sinh(math.pi * (1 - 2*y_web/(2**Z)))))
tile_lat_s = math.degrees(math.atan(math.sinh(math.pi * (1 - 2*(y_web+1)/(2**Z)))))
tile_lon_w = x / 2**Z * 360 - 180
tile_lon_e = (x+1) / 2**Z * 360 - 180
print(f"tile z{Z} x={x} y_web={y_web} tms={y_tms}")
print(f"bbox lon {tile_lon_w:.5f}..{tile_lon_e:.5f} lat {tile_lat_s:.5f}..{tile_lat_n:.5f}")

tmp = os.path.join(os.environ.get('LOCALAPPDATA','C:/Users/user/AppData/Local/Temp'), 'tile_check.mbtiles')
shutil.copy2(MB, tmp)
con = sqlite3.connect(tmp)
cur = con.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                  (Z, x, y_tms))
row = cur.fetchone()
con.close(); os.remove(tmp)
if not row:
    print("TILE NOT FOUND"); raise SystemExit(1)
img = Image.open(io.BytesIO(bytes(row[0]))).convert("RGB")
img.save(os.path.join(HERE, "tile_raw.png"))
print("tile_raw.png saved", img.size)

# ---- project graph.json roads into this tile ----
data = json.load(open(GJ, encoding="utf-8"))
nodes = {n["id"]: (n["lat"], n["lon"]) for n in data["nodes"]}

def px(lon, lat):
    px_ = (lon2x(Z, lon) - x) * 256.0
    py_ = (lat2y(Z, lat) - y_web) * 256.0
    return px_, py_

over = img.copy()
# semi-transparent red overlay of graph edges
rgba = over.convert("RGBA")
pxls = rgba.load()
edge_px = []
for e in data["edges"]:
    f = nodes.get(e["from"]); t = nodes.get(e["to"])
    if not f or not t: continue
    x1,y1 = px(f[1], f[0]); x2,y2 = px(t[1], t[0])
    edge_px.append((x1,y1,x2,y2))
print("edges near tile:", len(edge_px))
# draw with Bresenham on temp stamp then blend
import array
stamp = Image.new("RGBA", rgba.size, (0,0,0,0))
spx = stamp.load()
def bline(x1,y1,x2,y2):
    x1,y1,x2,y2 = int(x1),int(y1),int(x2),int(y2)
    dx=abs(x2-x1); dy=-abs(y2-y1); ex=1 if x1<x2 else -1; ey=1 if y1<y2 else -1
    err=dx+dy
    while True:
        if 0<=x1<rgba.size[0] and 0<=y1<rgba.size[1]:
            spx[x1,y1]=(255,0,0,200)
        if x1==x2 and y1==y2: break
        e2=2*err
        if e2>=dy: err+=dy; x1+=ex
        if e2<=dx: err+=dx; y1+=ey
for (x1,y1,x2,y2) in edge_px:
    bline(x1,y1,x2,y2)
out = Image.alpha_composite(rgba, stamp)
out.convert("RGB").save(os.path.join(HERE, "tile_overlay.png"))
print("tile_overlay.png saved")

# stats: how much of the tile is non-background (roads drawn by Maperitive)
import numpy as np
a = np.asarray(img)
nonbg = (a < 245).any(axis=2).mean()
print(f"non-background fraction of raw tile: {nonbg:.3f}")