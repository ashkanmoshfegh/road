
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

nodes_out = set()
ways_out = set()

# Pass 1: collect way IDs to keep + nodes referenced by those ways
class Pass1Handler(osmium.SimpleHandler):
    def node(self, n):
        # Only collect nodes inside our Tehran bbox
        lat, lon = n.location.lat, n.location.lon
        if MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON:
            nodes_out.add(n.id)
    
    def way(self, w):
        tags = {t.k: t.v for t in w.tags}
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
            ways_out.add(w.id)
            # Also ensure the nodes of this way are collected (they're within bbox mostly)
            for nref in w.nodes:
                nodes_out.add(nref.ref)

print("Pass 1: classifying ways and collecting nodes...")
proc = file_processor.FileProcessor(pbf_in, ALL)
proc = proc.with_locations('flex_mem')
h = Pass1Handler()
pass  # handler methods are called automatically

writer = SimpleWriter(xml_out)
nodes_written = 0
ways_written = 0
ignored = 0

# Pass 2: write
print("Pass 2: writing XML...")
proc2 = file_processor.FileProcessor(pbf_in, ALL)
proc2 = proc2.with_locations('flex_mem')
for obj in proc2:
    if isinstance(obj, Node) and obj.id in nodes_out:
        writer.add_node(obj)
        nodes_written += 1
    elif isinstance(obj, Way) and obj.id in ways_out:
        writer.add_way(obj)
        ways_written += 1
    else:
        ignored += 1

writer.close()
sz = os.path.getsize(xml_out)
total_elems = nodes_written + ways_written + ignored
print(f"\n=== RESULTS ===")
print(f"XML: {sz:,} bytes ({sz/1e6:.1f} MB)")
print(f"Nodes written: {nodes_written:,}")
print(f"Ways written: {ways_written:,}")
print(f"Ignored: {ignored:,}")
print(f"Compression vs full XML (231MB): {231*1e6/sz:.1f}x")
