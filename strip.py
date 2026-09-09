
import re, os, sys

src = sys.argv[1]
dst = sys.argv[2]
print(f"Stripping: {os.path.getsize(src):,} bytes -> {dst}")

KEEP_HIGHWAY = {
    'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
    'secondary','secondary_link','tertiary','tertiary_link','residential','living_street',
    'unclassified','service','track','footway','path','cycleway','steps','pedestrian',
    'bridleway','road','construction','service','turning_circle'
}

out = []
in_way = False
way_id = None
way_nds = []
way_tags = []
way_class = False

nodes_kept = 0
ways_kept = 0
nodes_total = 0
ways_total = 0

with open(src, 'r', encoding='utf-8') as f:
    for i, line in enumerate(f):
        if i % 1000000 == 0:
            print(f"  Line {i:,}... ({nodes_kept:,} nodes, {ways_kept:,} ways)")
        
        s = line.strip()
        
        # <node ...> - keep only id, lat, lon, drop ALL other attributes and tags
        if s.startswith('<node ') and not in_way:
            nodes_total += 1
            # Extract only id, lat, lon
            m = re.search(r'id="(-?\d+)"', s)
            lat_m = re.search(r'lat="([^"]*)"', s)
            lon_m = re.search(r'lon="([^"]*)"', s)
            if m and lat_m and lon_m:
                out.append(f'<node id="{m.group(1)}" lat="{lat_m.group(1)}" lon="{lon_m.group(1)}"/>')
                nodes_kept += 1
            # Skip closing </node> and all <tag> inside - handled by "in_way" check
            # But nodes don't have nested content in OSM XML (they're self-closing or have tags then close)
            # Actually nodes CAN have <tag> children. We need to skip those too.
            # Since we check `not in_way`, and nodes don't set in_way=True, 
            # any <tag> inside a node will just be skipped (not matched by any condition)
            # And </node> will also be skipped
            continue
        
        # <way ...>
        if s.startswith('<way '):
            ways_total += 1
            in_way = True
            way_id = re.search(r'id="(-?\d+)"', s).group(1)
            way_nds = []
            way_tags = []
            way_class = False
            continue
        
        if not in_way:
            # Outside a way: skip <relation>, </node>, <tag> (from nodes), etc.
            continue
        
        # Inside a way:
        # <nd ref="...">
        if s.startswith('<nd '):
            m = re.search(r'ref="(-?\d+)"', s)
            if m:
                way_nds.append(m.group(1))
            continue
        
        # <tag k="..." v="...">
        if s.startswith('<tag '):
            m = re.search(r'k="([^"]*)"', s)
            v_m = re.search(r'v="([^"]*)"', s)
            if m:
                k = m.group(1)
                v = v_m.group(1) if v_m else ''
                if k in ('highway','building','natural','waterway','landuse','surface','oneway','name'):
                    way_tags.append((k, v))
                    if k in ('highway','building','natural','waterway'):
                        way_class = True
            continue
        
        # </way>
        if s == '</way>':
            if way_class:
                parts = [f'<way id="{way_id}">']
                for ref in way_nds:
                    parts.append(f'<nd ref="{ref}"/>')
                for k, v in way_tags:
                    parts.append(f'<tag k="{k}" v="{v}"/>')
                parts.append('</way>')
                out.append(''.join(parts))
                ways_kept += 1
            in_way = False
            way_id = None
            way_nds = None
            way_tags = None
            way_class = False

print(f"\n=== Statistics ===")
print(f"Input nodes: {nodes_total:,}, kept: {nodes_kept:,} ({nodes_kept/nodes_total*100:.1f}%)")
print(f"Input ways: {ways_total:,}, kept: {ways_kept:,} ({ways_kept/ways_total*100:.1f}%)")

print(f"Writing output...")
with open(dst, 'w', encoding='utf-8') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<osm version="0.6" generator="compact"/>\n')

print(f"Done. Size: {os.path.getsize(dst):,} bytes")
