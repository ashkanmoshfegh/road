
import osmium
from osmium import file_processor, simple_handler
from osmium import file_processor as fp
from osmium.osm import Node, Way
import osmium.osm

pbf_in = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/iran-260828.osm.pbf"
pbf_out = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/tehran-extract.osm.pbf"

print(f"Input: {pbf_in} ({__import__('os').path.getsize(pbf_in):,} bytes)")

# FileProcessor approach
proc = fp.FileProcessor(pbf_in, osmium.osm.ALL)
print(f"FileProcessor: {proc}")
print(f"  header: {proc.header}")
print(f"  node_location_storage: {proc.node_location_storage}")
print(f"  type: {type(proc)}")

# Create writer
writer = simple_handler.SimpleWriter(pbf_out)
print(f"Writer: {writer}")

# Create filter handler
class TehranFilter(simple_handler.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.nodes_extracted = 0
        self.ways_extracted = 0
    
    def node(self, n):
        lat, lon = n.location
        if 51.15 <= lon <= 51.80 and 35.40 <= lat <= 35.95:
            self.add_node(n)
            self.nodes_extracted += 1
    
    def way(self, w):
        for nref in w.nodes:
            lat, lon = nref.location
            if 51.15 <= lon <= 51.80 and 35.40 <= lat <= 35.95:
                self.add_way(w)
                self.ways_extracted += 1
                return

handler = TehranFilter()

# Chain: with_locations -> handler -> writer
# FileProcessor.with_locations() adds NodeLocationsForWays internally
# with_filter() adds a spatial filter
# handler is applied after locations

print("\nProcessing...")
proc = proc.with_locations(handler)

# Actually, the osmium 4.x chain works like:
# FileProcessor(input, entities)
#   .with_locations(storage)  # adds NodeLocationsForWays
#   .with_filter(handler)      # adds a filter handler
# Then iterate or process

# Let's check the chain methods
print("After with_locations, type:", type(proc))
print("Methods:", [m for m in dir(proc) if not m.startswith('_')])

# The chain should be:
# FileProcessor(input).with_locations(index).with_filter(filter_handler)
# Then process somehow

# Let's try iterating
try:
    for obj in proc:
        tname = type(obj).__name__
        oid = getattr(obj, 'id', '?')
        print(f"  {tname} id={oid}")
        if isinstance(obj, Node):
            lat, lon = obj.location
            print(f"    loc: {lat:.4f}, {lon:.4f}")
        if isinstance(obj, Way):
            print(f"    nodes: {len(obj.nodes) if obj.nodes else 0}")
        break
except Exception as e:
    print(f"Iteration error: {e}")
    import traceback
    traceback.print_exc()

# Also try the with_filter approach
print("\n=== Trying with_filter ===")
try:
    proc2 = fp.FileProcessor(pbf_in, osmium.osm.ALL)
    proc2 = proc2.with_locations(handler)
    proc2 = proc2.with_filter(handler)
    print(f"proc2: {type(proc2)}")
    print(f"methods: {[m for m in dir(proc2) if not m.startswith('_')]}")
except Exception as e:
    print(f"with_filter error: {e}")
    import traceback
    traceback.print_exc()
