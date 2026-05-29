import struct

with open('/Users/guoshuai/AndroidStudioProjects/PicMe/beauty-engine/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so', 'rb') as f:
    data = f.read()

if data[:4] != b'\x7fELF':
    print('Not an ELF file')
else:
    print('ELF file confirmed')
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]

    dyn_offset = None
    dyn_size = None
    for i in range(e_phnum):
        p_type = struct.unpack_from('<I', data, e_phoff + i * e_phentsize)[0]
        if p_type == 2:
            dyn_offset = struct.unpack_from('<Q', data, e_phoff + i * e_phentsize + 0x08)[0]
            dyn_size = struct.unpack_from('<Q', data, e_phoff + i * e_phentsize + 0x20)[0]
            break

    if dyn_offset is None:
        print('No PT_DYNAMIC found')
    else:
        print(f'DYNAMIC at file offset {dyn_offset}, size {dyn_size}')

        DT_NEEDED = 1
        DT_STRTAB = 5
        strtab_addr = None
        needed_offsets = []

        for j in range(0, dyn_size, 16):
            tag = struct.unpack_from('<Q', data, dyn_offset + j)[0]
            val = struct.unpack_from('<Q', data, dyn_offset + j + 8)[0]
            if tag == DT_NEEDED:
                needed_offsets.append(val)
            elif tag == DT_STRTAB:
                strtab_addr = val
            elif tag == 0:
                break

        print(f'String table at virtual addr: {strtab_addr}')
        print(f'NEEDED count: {len(needed_offsets)}')

        if strtab_addr:
            for off in needed_offsets:
                s = b''
                k = strtab_addr + off
                while data[k] != 0:
                    s += bytes([data[k]])
                    k += 1
                print(f'  NEEDED: {s.decode()}')
