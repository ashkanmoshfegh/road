
import osmium
from osmium import file_processor, simple_handler, filter as osm_filter
from osmium.osm import Node, Way, ALL, Box
from osmium import SimpleWriter
import time, os

pbf_in = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/iran-260828.osm.pbf"
pbf_out = "C:/Users/user/AndroidStudioProjects/road/app/src/main/assets/tehran-extract.osm.pbf"

print(f"Input: {pbf_in} ({os.path.getsize(pbf_in):,} bytes)")
print(f"Output: {pbf_out}")

MIN_LON, MIN_LAT, MAX_LON, MAX_LAT = 51.15, 35.40, 51.80, 35.95

# Check if SimpleWriter needs a temp file on same drive
out_dir = os.path.dirname(pbf_out)
print(f"Output dir exists: {os.path.exists(out_dir)}")
print(f"Output dir writable: {os.access(out_dir, os.W_OK)}")

# Try writing to a temp location on C: first
import tempfile
tmp_path = os.path.join(os.environ.get('TEMP', 'C:/temp'), 'test_pbf.osm.pbf')
print(f"Temp path: {tmp_path}")
print(f"TEMP env: {os.environ.get('TEMP', 'NOT SET')}")
print(f"TMP env: {os.environ.get('TMP', 'NOT SET')}")

# Also try the project root
proj_root = "C:/Users/user/AndroidStudioProjects/road"
print(f"Project root writable: {os.access(proj_root, os.W_OK)}")

# Check if we can write to current working directory
print(f"CWD: {os.getcwd()}")
print(f"CWD writable: {os.access(os.getcwd(), os.W_OK)}")

# Try SimpleWriter with a path on C: drive
test_outputs = [
    pbf_out,
    "C:/temp/tehran-test.osm.pbf",
    "C:/Users/user/AndroidStudioProjects/road/tehran-test.osm.pbf",
    os.path.join(os.getcwd(), "tehran-test.osm.pbf"),
]

for tpath in test_outputs:
    print(f"\nTrying SimpleWriter at: {tpath}")
    try:
        w = SimpleWriter(tpath)
        print(f"  SUCCESS: {tpath}")
        w.close()
        if os.path.exists(tpath):
            sz = os.path.getsize(tpath)
            print(f"  File created: {sz} bytes")
            os.remove(tpath)
    except Exception as e:
        print(f"  FAILED: {e}")
