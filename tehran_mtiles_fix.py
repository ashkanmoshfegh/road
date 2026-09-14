#!/usr/bin/env python3
"""
tehran_mtiles_fix.py — v2
테헤란 OSM 데이터(tehran-map.osm.stripped)로부터 올바른 지리적 범위의
MBTiles 파일을 생성한다.

버전 1 문제점:
- road_ways가 0개 (highway 태그가 소문자로 들어있는데 KEEP_HIGHWAY는 소문자 비교 안 함)
- 출력 파일이 WAL 모드로 잠겨서 다른 프로세스에서 접근 불가
- 타일 생성 루프에서 예외 발생 시 조용히 종료됨

v2 수정:
- highway.lower() 비교 적용
- WAL 대신 일반 저널 모드 사용
- 진행 상황 상세 로깅
- 타일 생성 실패 시 예외 명시적 출력
- 기존 tehran.mbtiles를 tehran.mbtiles.bak으로 백업 후 새 파일로 교체
"""
import os
import sys
import math
import sqlite3
import struct
import zlib
from xml.etree import ElementTree as ET

# ---------------------------------------------------------------------------
# 설정
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
OSM_FILE = os.path.join(HERE, "app/src/main/assets/tehran-map.osm.stripped")
MB_SOURCE = os.path.join(HERE, "app/src/main/assets/tehran.mbtiles")
MB_DEST = os.path.join(HERE, "app/src/main/assets/tehran.mbtiles")
MB_TMP = os.path.join(HERE, "app/src/main/assets/_tehran_new.mbtiles")
MIN_ZOOM = 10
MAX_ZOOM = 17

# OSM 파일에서 확인된 테헤란 범위
TARGET_BBOX = (51.15, 35.40, 51.80, 35.95)  # lon_min, lat_min, lon_max, lat_max

KEEP_HIGHWAY = {
    'motorway', 'motorway_link', 'trunk', 'trunk_link',
    'primary', 'primary_link', 'secondary', 'secondary_link',
    'tertiary', 'tertiary_link', 'residential', 'living_street',
    'unclassified', 'service'
}

print("설정:", file=sys.stderr)
print(f"  OSM: {OSM_FILE}", file=sys.stderr)
print(f"  OSM 존재: {os.path.exists(OSM_FILE)}", file=sys.stderr)
print(f"  MBTiles 원본: {MB_SOURCE} (존재: {os.path.exists(MB_SOURCE)})", file=sys.stderr)
print(f"  MBTiles 대상: {MB_DEST}", file=sys.stderr)
print(f"  MBTiles 임시: {MB_TMP}", file=sys.stderr)
print(f"  줌 범위: {MIN_ZOOM}-{MAX_ZOOM}", file=sys.stderr)

# ---------------------------------------------------------------------------
# Web Mercator <-> 타일 좌표 (Google/OSM 스타일: Y는 상단 0)
# MBTiles는 TMS 방식이므로 저장 시 Y 반전 필요.
# ---------------------------------------------------------------------------
def lonlat_to_tile_web(z, lon, lat):
    """Google/OSM 스타일 타일 좌표 (Y=0이 북쪽)."""
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    return x, y

def tile_to_lonlat(z, x, y):
    """Web Mercator 타일 좌표 -> lat/lon (y=0 북쪽 기준)"""
    n = 2 ** z
    lon = x / n * 360.0 - 180.0
    lat_rad = math.atan(math.sinh(math.pi * (1.0 - 2.0 * y / n)))
    lat = math.degrees(lat_rad)
    return lat, lon

def tms_y(z, web_y):
    """Web Mercator Y -> TMS Y (MBTiles 저장용)."""
    return (2 ** z - 1) - web_y

# ---------------------------------------------------------------------------
# OSM 파싱
# ---------------------------------------------------------------------------
print("\n=== OSM 파싱 시작 ===", file=sys.stderr)

nodes = {}       # id -> (lat, lon)
ways = []        # list of [node_id, ...]
way_highway = [] # parallel list of highway tag value (or '')

node_count = 0
way_count = 0

ctx = ET.iterparse(OSM_FILE, events=('end',))
for event, elem in ctx:
    if elem.tag == 'node':
        nid = elem.get('id')
        lat = float(elem.get('lat'))
        lon = float(elem.get('lon'))
        nodes[nid] = (lat, lon)
        node_count += 1
        elem.clear()
    elif elem.tag == 'way':
        nids = []
        highway = ''
        for nd in elem.findall('nd'):
            nids.append(nd.get('ref'))
        tag_el = elem.find('tag[@k="highway"]')
        if tag_el is not None:
            highway = tag_el.get('v', '').strip().lower()
        if nids:
            ways.append(nids)
            way_highway.append(highway)
            way_count += 1
        elem.clear()
    if node_count % 500000 == 0:
        print(f"  노드 {node_count:,} | 웨이 {way_count:,}", file=sys.stderr)

print(f"OSM 파싱 완료: 노드 {node_count:,}, 웨이 {way_count:,}", file=sys.stderr)

# 도로 웨이 필터링
road_ways = []
for nids, hw in zip(ways, way_highway):
    if hw and hw in KEEP_HIGHWAY:
        if all(nid in nodes for nid in nids):
            road_ways.append((nids, hw))

print(f"도로 웨이: {len(road_ways):,} / 전체 {way_count:,}", file=sys.stderr)

if not road_ways:
    print("ERROR: 도로 웨이가 하나도 없습니다! OSM 파일의 highway 태그가 예상과 다릅니다.", file=sys.stderr)
    sys.exit(1)

# ---------------------------------------------------------------------------
# MBTiles 생성
# ---------------------------------------------------------------------------
print(f"\n=== MBTiles 생성 시작: {MB_TMP} ===", file=sys.stderr)

# 기존 임시 파일 제거
if os.path.exists(MB_TMP):
    os.remove(MB_TMP)

conn = sqlite3.connect(MB_TMP)
conn.execute("PRAGMA journal_mode=DELETE")  # WAL 대신 DELETE 모드로 잠금 문제 방지
c = conn.cursor()

c.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
c.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
c.execute("INSERT INTO metadata VALUES ('name', 'Tehran Offline Map')")
c.execute("INSERT INTO metadata VALUES ('minzoom', ?)", (str(MIN_ZOOM),))
c.execute("INSERT INTO metadata VALUES ('maxzoom', ?)", (str(MAX_ZOOM),))
c.execute("INSERT INTO metadata VALUES ('bounds', ?)", (f'{TARGET_BBOX[0]},{TARGET_BBOX[1]},{TARGET_BBOX[2]},{TARGET_BBOX[3]}',))
c.execute("INSERT INTO metadata VALUES ('tilejson', ?)", ('1.0.0',))
conn.commit()

# 타일 범위 계산
print("\n타일 범위 계산:", file=sys.stderr)
tile_ranges = {}
for z in range(MIN_ZOOM, MAX_ZOOM + 1):
    # SW 코너 (최소 lat, 최소 lon)
    x_sw, y_sw = lonlat_to_tile_web(z, TARGET_BBOX[0], TARGET_BBOX[1])
    # NE 코너 (최대 lat, 최대 lon)
    x_ne, y_ne = lonlat_to_tile_web(z, TARGET_BBOX[2], TARGET_BBOX[3])
    
    x_min = min(x_sw, x_ne)
    x_max = max(x_sw, x_ne)
    y_min = min(y_sw, y_ne)  # web 스타일에서 y_min이 북쪽
    y_max = max(y_sw, y_ne)  # y_max가 남쪽
    
    tile_ranges[z] = (x_min, x_max, y_min, y_max)
    print(f"  z{z}: x[{x_min}-{x_max}] y[{y_min}-{y_max}] → 타일 {(x_max-x_min+1) * (y_max-y_min+1)}개", file=sys.stderr)

# 타일 렌더링 함수
def make_tile_png(z, web_tx, web_ty, road_ways, nodes):
    """
    256x256 RGBA PNG 바이트 생성.
    web_tx, web_ty: Web Mercator 타일 좌표 (y=0 북쪽 기준).
    타일 영역이 포함하는 도로 선만 검은색(진회색)으로 그림.
    """
    width, height = 256, 256
    raw = bytearray(width * height * 4)
    # 밝은 회색 바탕
    for i in range(0, len(raw), 4):
        raw[i] = 248     # R
        raw[i+1] = 248   # G
        raw[i+2] = 248   # B
        raw[i+3] = 255   # A
    
    # 타일 경계 lat/lon (web 스타일)
    lat_n, lon_w = tile_to_lonlat(z, web_tx, web_ty)         # 북쪽-서쪽
    lat_s, lon_e = tile_to_lonlat(z, web_tx, web_ty + 1)     # 남쪽-동쪽
    
    # lonlat -> 타일 내 픽셀 (0~256)
    n = 2 ** z
    def ll2px(lon, lat):
        px = (lon + 180.0) / 360.0 * n - web_tx
        lat_rad = math.radians(lat)
        py = (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n - web_ty
        return px * 256.0, py * 256.0
    
    # 각 도로 웨이 처리
    for nids, hw in road_ways:
        pts = []
        for nid in nids:
            if nid in nodes:
                lat, lon = nodes[nid]
                if lat_s <= lat <= lat_n and lon_w <= lon <= lon_e:
                    px, py = ll2px(lon, lat)
                    pts.append((px, py))
        # 타일 경계 밖 점 클리핑
        if len(pts) < 2:
            continue
        
        # 선 그리기 (Bresenham)
        for i in range(len(pts) - 1):
            draw_line(raw, width, height, pts[i], pts[i+1])
    
    return raw_to_png(raw, width, height)

def draw_line(raw, w, h, p1, p2):
    """Bresenham 알고리즘으로 RGBA 버퍼에 검은색 선 그리기."""
    x1, y1 = int(round(p1[0])), int(round(p1[1]))
    x2, y2 = int(round(p2[0])), int(round(p2[1]))
    
    dx = abs(x2 - x1)
    dy = abs(y2 - y1)
    sx = 1 if x1 < x2 else -1
    sy = 1 if y1 < y2 else -1
    err = dx - dy
    
    while True:
        if 0 <= x1 < w and 0 <= y1 < h:
            idx = (y1 * w + x1) * 4
            raw[idx] = 40
            raw[idx+1] = 40
            raw[idx+2] = 40
            raw[idx+3] = 255
        if x1 == x2 and y1 == y2:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy
            x1 += sx
        if e2 < dx:
            err += dx
            y1 += sy

def raw_to_png(raw, width, height):
    """Raw RGBA 바이트 배열 -> PNG 바이트."""
    def png_chunk(chunk_type, data):
        chunk_len = struct.pack('>I', len(data))
        chunk_crc = struct.pack('>I', zlib.crc32(chunk_type + data) & 0xffffffff)
        return chunk_len + chunk_type + data + chunk_crc
    
    png = b'\x89PNG\r\n\x1a\n'
    
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    png += png_chunk(b'IHDR', ihdr_data)
    
    # 필터: 각 행 앞에 0 바이트 추가
    filtered = bytearray()
    for y in range(height):
        filtered.append(0)
        filtered.extend(raw[y * width * 4:(y + 1) * width * 4])
    
    compressed = zlib.compress(bytes(filtered), 9)
    png += png_chunk(b'IDAT', compressed)
    png += png_chunk(b'IEND', b'')
    return bytes(png)

# 타일 생성 루프
total_tiles = 0
for z in range(MIN_ZOOM, MAX_ZOOM + 1):
    x_min, x_max, y_min, y_max = tile_ranges[z]
    print(f"\n  줌 {z}: {x_max - x_min + 1} x {y_max - y_min + 1} = {(x_max - x_min + 1) * (y_max - y_min + 1)} 타일", file=sys.stderr)
    
    z_tile_count = 0
    for web_tx in range(x_min, x_max + 1):
        for web_ty in range(y_min, y_max + 1):
            tms_ty = tms_y(z, web_ty)
            
            try:
                png = make_tile_png(z, web_tx, web_ty, road_ways, nodes)
                blob = sqlite3.Binary(png)
                c.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)",
                          (z, web_tx, tms_ty, blob))
                z_tile_count += 1
                total_tiles += 1
            except Exception as e:
                print(f"    ERROR 타일 z{z} ({web_tx},{web_ty}): {e}", file=sys.stderr)
                import traceback
                traceback.print_exc(file=sys.stderr)
    
    print(f"    줌 {z} 완료: {z_tile_count} 타일 생성됨 (누적 {total_tiles})", file=sys.stderr)
    conn.commit()
    print(f"    커밋 완료", file=sys.stderr)

conn.close()
print(f"\n=== MBTiles 생성 완료: 총 {total_tiles:,} 타일 ===", file=sys.stderr)

# ---------------------------------------------------------------------------
# 대상 위치로 이동 (원본 백업 후 교체)
# ---------------------------------------------------------------------------
if os.path.exists(MB_DEST):
    bak = MB_DEST + ".bak"
    print(f"\n원본 MBTiles 백업: {MB_DEST} -> {bak}", file=sys.stderr)
    if os.path.exists(bak):
        os.remove(bak)
    os.rename(MB_DEST, bak)

print(f"새 MBTiles 이동: {MB_TMP} -> {MB_DEST}", file=sys.stderr)
os.rename(MB_TMP, MB_DEST)
print(f"\n완료! 새 MBTiles: {MB_DEST}", file=sys.stderr)
print(f"  크기: {os.path.getsize(MB_DEST):,} bytes", file=sys.stderr)
