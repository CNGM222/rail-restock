import struct
import sys
from pathlib import Path

SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
         12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def utf8_constants(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    count = struct.unpack_from('>H', data, 8)[0]
    offset, index = 10, 1
    out = []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            out.append(data[offset:offset + length].decode('utf-8', 'replace'))
            offset += length
        else:
            offset += SIZES[tag]
        index += 2 if tag in (5, 6) else 1
    return out


for arg in sys.argv[1:]:
    path = Path(arg)
    print('==', path)
    for value in utf8_constants(path.read_bytes()):
        if any(key in value for key in ('doItemUse', 'doAttack', 'setScreen', 'method_', 'class_', 'onInventory', 'onMouseClick', 'keyPressed')):
            print('   ', value)
