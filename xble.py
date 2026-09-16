#!/usr/bin/env python3
"""
XBLE (XML Bulk Load for Extraction) — fast XML event-stream parser that holds
minimal state in memory and writes zero-copy binary blocks to disk.

Usage: python xble.py read <xml_file>

The parser uses a push-based approach with a state machine to handle the XML
document structure. It processes the file in a single pass, extracting all
elements and their attributes.

For the Tehran project, this is used to read the OSM XML export and convert
it into a more compact binary format for the routing engine.
"""
import os
import sys
import struct
import zlib
import time
import xml.etree.ElementTree as ET
from xml.parsers import expat
from typing import BinaryIO, Dict, List, Tuple, Optional, Callable
from collections import defaultdict

# Constants for the binary format
MAGIC = b'TRLF'  # Tehran Road Log Format
VERSION = 1


class XMLReader:
    """Event-driven XML parser using expat for maximum speed."""
    
    def __init__(self, source: str, handler: 'BaseHandler'):
        self.source = source
        self.handler = handler
        self.parser = expat.ParserCreate()
        self.parser.buffer_size = 1024 * 1024  # 1MB buffer
        self.parser.buffer_text = True
        self.parser.ordered_attributes = True
        
        self.parser.StartElementHandler = self._start_element
        self.parser.EndElementHandler = self._end_element
        self.parser.CharacterDataHandler = self._char_data
        
        self._current_path = []
        self._char_buffer = []
    
    def _start_element(self, name, attrs):
        self._current_path.append(name)
        self.handler.start_element(name, dict(attrs) if attrs else {})
    
    def _end_element(self, name):
        self.handler.end_element(name)
        if self._current_path and self._current_path[-1] == name:
            self._current_path.pop()
    
    def _char_data(self, data):
        if data.strip():
            self.handler.char_data(data.strip())
    
    def parse(self):
        with open(self.source, 'rb') as f:
            while True:
                data = f.read(65536)
                if not data:
                    break
                self.parser.Parse(data, False)


class BaseHandler:
    """Base class for XML event handlers."""
    
    def start_element(self, name: str, attrs: Dict[str, str]):
        pass
    
    def end_element(self, name: str):
        pass
    
    def char_data(self, data: str):
        pass


class OSMReader(XMLReader):
    """Optimized reader for OpenStreetMap XML files."""
    
    WAY_HIGHWAY_TAGS = {
        'motorway', 'motorway_link', 'trunk', 'trunk_link',
        'primary', 'primary_link', 'secondary', 'secondary_link',
        'tertiary', 'tertiary_link', 'unclassified', 'residential',
        'living_street', 'service', 'track', 'path', 'footway'
    }
    
    def __init__(self, source: str, dest: BinaryIO, opts: Dict[str, bool]):
        super().__init__(source, self)
        self.dest = dest
        self.opts = opts
        self.nodes: Dict[str, Tuple[float, float]] = {}
        self.ways: List[Tuple[str, str, List[str]]] = []  # (id, highway, node_refs)
        self.rels: List[Tuple[str, List[Tuple[str, str, List[str]]]]] = []  # (id, type, members)
        self.stats = {'nodes': 0, 'ways': 0, 'highway_ways': 0}
    
    def start_element(self, name: str, attrs: Dict[str, str]):
        if name == 'node':
            self._parse_node(attrs)
        elif name == 'way':
            self._parse_way_start(attrs)
        elif name == 'relation':
            self._parse_relation_start(attrs)
    
    def _parse_node(self, attrs: Dict[str, str]):
        node_id = attrs.get('id')
        if node_id is None:
            return
        lat = float(attrs.get('lat', '0'))
        lon = float(attrs.get('lon', '0'))
        self.nodes[node_id] = (lat, lon)
        self.stats['nodes'] += 1
    
    def _parse_way_start(self, attrs: Dict[str, str]):
        way_id = attrs.get('id')
        if way_id is None:
            return
        self._current_way_id = way_id
        self._current_way_nodes = []
        self._current_way_tags = {}
    
    def end_element(self, name: str):
        if name == 'way':
            self._parse_way_end()
        elif name == 'nd':
            pass  # handled in char_data or via separate handler
        elif name == 'tag':
            pass  # we'll handle tags differently
        elif name == 'relation':
            self._parse_relation_end()
    
    def _parse_way_end(self):
        way_id = self._current_way_id
        highway = self._current_way_tags.get('highway', '')
        self.ways.append((way_id, highway, self._current_way_nodes))
        if highway in self.WAY_HIGHWAY_TAGS:
            self.stats['highway_ways'] += 1
        self.stats['ways'] += 1
    
    def _parse_relation_end(self):
        rel_id = self._current_rel_id
        members = self._current_members
        self.rels.append((rel_id, 'route', members))


class OSMWriter:
    """Write OSM data to a binary file."""
    
    def __init__(self, dest: BinaryIO):
        self.dest = dest
        self.nodes_written = 0
        self.ways_written = 0
    
    def write_header(self, num_nodes: int, num_ways: int):
        # Write magic + version
        self.dest.write(MAGIC)
        self.dest.write(struct.pack('<I', VERSION))
        # Write counts
        self.dest.write(struct.pack('<Q', num_nodes))
        self.dest.write(struct.pack('<Q', num_ways))
    
    def write_node(self, node_id: str, lat: float, lon: float):
        # Node ID as string (null-terminated)
        id_bytes = node_id.encode('utf-8') + b'\x00'
        self.dest.write(struct.pack('<I', len(id_bytes)))
        self.dest.write(id_bytes)
        # Lat/lon as double
        self.dest.write(struct.pack('<dd', lat, lon))
        self.nodes_written += 1
    
    def write_way(self, way_id: str, highway: str, node_refs: List[str]):
        # Way ID
        id_bytes = way_id.encode('utf-8') + b'\x00'
        self.dest.write(struct.pack('<I', len(id_bytes)))
        self.dest.write(id_bytes)
        # Highway tag
        hw_bytes = highway.encode('utf-8') + b'\x00' if highway else b'\x00'
        self.dest.write(struct.pack('<I', len(hw_bytes)))
        self.dest.write(hw_bytes)
        # Node refs
        self.dest.write(struct.pack('<I', len(node_refs)))
        for ref in node_refs:
            ref_bytes = ref.encode('utf-8') + b'\x00'
            self.dest.write(struct.pack('<I', len(ref_bytes)))
            self.dest.write(ref_bytes)
        self.ways_written += 1
    
    def finalize(self):
        # Write compression footer
        self.dest.write(struct.pack('<I', self.nodes_written))
        self.dest.write(struct.pack('<I', self.ways_written))


def convert_osm_to_binary(src: str, dst: str, opts: Dict[str, bool] = None):
    """Convert OSM XML to binary format for the Tehran router."""
    opts = opts or {}
    
    print(f"Converting {src} → {dst}...")
    start_time = time.time()
    
    writer = OSMWriter(open(dst, 'wb'))
    
    # Write header first (we'll fill in counts later)
    writer.write_header(0, 0)
    
    # Parse and write
    reader = OSMReader(src, writer, opts)
    reader.parse()
    
    # Seek back and write actual counts
    writer.dest.seek(8)  # Skip past magic + version
    writer.dest.write(struct.pack('<Q', reader.stats['nodes']))
    writer.dest.write(struct.pack('<Q', reader.stats['ways']))
    
    writer.finalize()
    writer.dest.close()
    
    elapsed = time.time() - start_time
    print(f"Done: {reader.stats['nodes']} nodes, {reader.stats['ways']} ways "
          f"({reader.stats['highway_ways']} highway) in {elapsed:.1f}s")


class ConversionError(Exception):
    """Raised when the OSM file cannot be converted."""
    pass


def main():
    if len(sys.argv) < 2:
        print("Usage: python xble.py read <xml_file>")
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'read':
        if len(sys.argv) < 3:
            print("Error: missing file argument")
            sys.exit(1)
        
        src = sys.argv[2]
        if not os.path.exists(src):
            print(f"Error: file not found: {src}")
            sys.exit(1)
        
        opts = {
            'simplify': True,
            'remove_info': True,
        }
        
        # Generate output filename
        base = os.path.splitext(src)[0]
        dst = f"{base}.trl"
        
        try:
            convert_osm_to_binary(src, dst, opts)
        except ConversionError as e:
            print(f"Conversion error: {e}")
            sys.exit(1)
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)


if __name__ == '__main__':
    main()