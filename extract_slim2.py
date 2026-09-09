
import osmium, os, sys
from osmium import file_processor, SimpleWriter
from osmium.osm import Node, Way, ALL, Relation

pbf_in = sys.argv[1]
xml_out = sys.argv[2]

KEEP_HIGHWAY = {
    'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
    'secondary','secondary_link','tertiary','tertiary_link','residential','living_street',
    'unclassified','service','track','footway','path','cycleway','steps','pedestrian',
}

MIN_LON, MIN_LAT, MAX_LON, MAX_LAT = 51.15, 35.40, 51.80, 35.95

# Pass 1: scan ways, collect what to keep
node_ids = set()
way_ids = set()
total_ways = 0

print("Pass 1: scanning PBF...")
proc1 = file_processor.FileProcessor(pbf_in, ALL)
proc1 = proc1.with_locations('flex_mem')

for obj in proc1:
    if isinstance(obj, Way):
        total_ways += 1
        tags = {t.k: t.v for t in obj.tags}
        keep = False
        if 'highway' in tags and tags['highway'] in KEEP_HIGHWAY:
            keep = True
        if 'building' in tags:
            keep = True
        if 'natural' in tags and tags['natural'] == 'water':
            keep = True
        if 'waterway' in tags:
            keep = True
        if keep:
            way_ids.add(obj.id)
            for nref in obj.nodes:
                # nref has .ref (node id) and .location (lat/lon after with_locations)
                node_ids.add(nref.ref)
    # nodes and relations ignored in pass 1

print(f"  Total ways: {total_ways:,}, kept: {len(way_ids):,}")
print(f"  Node refs to keep: {len(node_ids):,}")

# Pass 2: also collect stand-alone nodes (not referenced by ways) that are in bbox
# These are POIs etc. - skip for now to save space
# Actually we need ALL nodes referenced by kept ways, PLUS nodes that are way members
# The above already collects node refs from kept ways

# Pass 3: write XML
print("Pass 2: writing XML...")
writer = SimpleWriter(xml_out)

n_written = 0
w_written = 0

proc2 = file_processor.FileProcessor(pbf_in, ALL)
proc2 = proc2.with_locations('flex_mem')

for obj in proc2:
    if isinstance(obj, Node) and obj.id in node_ids:
        writer.add_node(obj)
        n_written += 1
    elif isinstance(obj, Way) and obj.id in way_ids:
        writer.add_way(obj)
        w_written += 1

writer.close()

sz = os.path.getsize(xml_out)
print(f"\n=== RESULTS ===")
print(f"XML: {sz:,} bytes ({sz/1e6:.1f} MB)")
print(f"Nodes: {n_written:,}, Ways: {w_written:,}")
print(f"Way reduction: {total_ways-len(way_ids):,} dropped ({(total_ways-len(way_ids))/total_ways*100:.0f}%)")
print(f"vs full XML 231MB: {231*1e6/sz:.1f}x smaller")
