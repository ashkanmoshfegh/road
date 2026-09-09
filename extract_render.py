
import osmium, os, sys
from osmium import file_processor, SimpleWriter
from osmium.osm import Node, Way, ALL

pbf_in = sys.argv[1]
xml_out = sys.argv[2]

print(f"Input: {os.path.getsize(pbf_in):,} bytes")
print(f"Output: {xml_out}")

# First pass: collect IDs of ways we want (highway, building, water)
KEEP_HIGHWAY = {'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
    'secondary','secondary_link','tertiary','tertiary_link','residential','living_street',
    'unclassified','service','track','footway','path','cycleway','steps','pedestrian',
    'bridleway','road','construction'}
KEEP_BUILDING = True  # any building tag
KEEP_WATER = True     # natural=water, waterway=*

way_ids_to_keep = set()
total_ways = 0
kept_ways = 0

class WayCollector(osmium.SimpleHandler):
    def way(self, w):
        global total_ways, kept_ways, way_ids_to_keep
        total_ways += 1
        tags = {t.k: t.v for t in w.tags}
        keep = False
        if 'highway' in tags and tags['highway'] in KEEP_HIGHWAY:
            keep = True
        if KEEP_BUILDING and 'building' in tags:
            keep = True
        if KEEP_WATER and ('natural' in tags and tags['natural'] == 'water' or
                            'waterway' in tags):
            keep = True
        if keep:
            way_ids_to_keep.add(w.id)
            kept_ways += 1

print(f"Pass 1: collecting way IDs...")
proc1 = file_processor.FileProcessor(pbf_in, ALL)
proc1 = proc1.with_locations('flex_mem')
handler1 = WayCollector()
for obj in proc1:
    handler1.add(obj)  # not needed but the iteration drives it
    # Actually we need to iterate properly
    pass
# Actually FileProcessor iteration calls handler methods automatically
# Let me fix this

proc1 = file_processor.FileProcessor(pbf_in, ALL)
proc1 = proc1.with_locations('flex_mem')
handler1 = WayCollector()
# FileProcessor iteration: for obj in proc calls handler methods
# But we need to attach the handler
# In osmium 4.x, we use with_handler or just iterate
# FileProcessor without handler: iteration yields objects
# We need to manually dispatch to handler

for obj in proc1:
    if isinstance(obj, Way):
        handler1.way(obj)
    # Skip nodes and relations in pass 1

print(f"  Total ways: {total_ways:,}, kept: {kept_ways:,}")

# Second pass: collect nodes that are referenced by kept ways
print(f"Pass 2: collecting node IDs for {len(way_ids_to_keep):,} ways...")
node_ids_needed = set()
proc2 = file_processor.FileProcessor(pbf_in, ALL)
proc2 = proc2.with_locations('flex_mem')

for obj in proc2:
    if isinstance(obj, Way) and obj.id in way_ids_to_keep:
        for nref in obj.nodes:
            node_ids_needed.add(nref.ref)

print(f"  Nodes needed: {len(node_ids_needed):,}")

# Third pass: write only kept ways + needed nodes to XML
print(f"Pass 3: writing XML...")
writer = SimpleWriter(xml_out)
nodes_written = 0
ways_written = 0

proc3 = file_processor.FileProcessor(pbf_in, ALL)
proc3 = proc3.with_locations('flex_mem')

for obj in proc3:
    if isinstance(obj, Node):
        if obj.id in node_ids_needed:
            writer.add_node(obj)
            nodes_written += 1
    elif isinstance(obj, Way):
        if obj.id in way_ids_to_keep:
            writer.add_way(obj)
            ways_written += 1

writer.close()

sz = os.path.getsize(xml_out)
print(f"\n=== RESULTS ===")
print(f"XML: {sz:,} bytes ({sz/1e6:.1f} MB)")
print(f"Nodes: {nodes_written:,}, Ways: {ways_written:,}")
print(f"Way reduction: {total_ways-kept_ways:,} ways dropped ({(total_ways-kept_ways)/total_ways*100:.0f}%)")
if sz < 50_000_000:
    print("GOOD SIZE!")
elif sz < 100_000_000:
    print("ACCEPTABLE SIZE")
else:
    print(f"LARGE ({sz/1e6:.0f}MB)")
