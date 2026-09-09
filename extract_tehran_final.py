
import osmium
from osmium import file_processor
from osmium.osm import Node, Way, ALL
import time

pbf_in = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/iran-260828.osm.pbf"
pbf_out = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/tehran-extract.osm.pbf"

print(f"Input: {pbf_in} ({__import__('os').path.getsize(pbf_in):,} bytes)")
print(f"Output: {pbf_out}")

MIN_LON, MIN_LAT, MAX_LON, MAX_LAT = 51.15, 35.40, 51.80, 35.95

class TehranFilter(osmium.BaseFilter):
    def filter(self, obj):
        if isinstance(obj, Node):
            lat, lon = obj.location
            return MIN_LON <= lon <= MAX_LON and MIN_LAT <= lat <= MAX_LAT
        elif isinstance(obj, Way):
            for nref in obj.nodes:
                lat, lon = nref.location
                if MIN_LON <= lon <= MAX_LON and MIN_LAT <= lat <= MAX_LAT:
                    return True
            return False
        return False

writer = osmium.SimpleWriter(pbf_out)
filter_obj = TehranFilter()

print("\nBuilding pipeline...")
# FileProcessor(pbf_in, ALL)
#   .with_locations('flex_mem')  # resolve node locations for way processing
#   .with_filter(TehranFilter)    # drop objects outside Tehran bbox
#   .handler_for_filtered(writer) # write what passes
proc = file_processor.FileProcessor(pbf_in, ALL)
proc = proc.with_locations('flex_mem')
proc = proc.with_filter(filter_obj)
proc = proc.handler_for_filtered(writer)

print(f"Pipeline ready. Processing (this will take several minutes)...")
t0 = time.time()
count = 0
try:
    for obj in proc:
        count += 1
        if count % 500000 == 0:
            elapsed = time.time() - t0
            print(f"  {count:,} objects processed in {elapsed:.1f}s")
except Exception as e:
    print(f"Error: {e}")
    import traceback
    traceback.print_exc()

elapsed = time.time() - t0
print(f"\nTotal: {count:,} objects in {elapsed:.1f}s")

# Validate output
sz = __import__('os').path.getsize(pbf_out)
print(f"Output size: {sz:,} bytes ({sz/1e6:.1f} MB)")
if sz < 1000:
    print("ERROR: Output file is too small — extraction likely failed")
