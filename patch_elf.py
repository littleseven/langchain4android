import struct
import shutil
import sys

def patch_sherpa_add_mnn_express_needed(input_path, output_path):
    with open(input_path, 'rb') as f:
        data = bytearray(f.read())

    if data[:4] != b'\x7fELF':
        print('Not an ELF file')
        return False

    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]

    # Read section header string table
    shstr_offset = e_shoff + e_shstrndx * e_shentsize
    shstr_fileoff = struct.unpack_from('<Q', data, shstr_offset + 0x18)[0]

    def read_str(addr):
        s = b''
        while data[addr] != 0:
            s += bytes([data[addr]])
            addr += 1
        return s.decode()

    def read_sh_name(offset):
        return read_str(shstr_fileoff + offset)

    # Find sections
    dynamic_section = None
    dynstr_section = None

    for i in range(e_shnum):
        sh_offset = e_shoff + i * e_shentsize
        sh_name = struct.unpack_from('<I', data, sh_offset)[0]
        sh_type = struct.unpack_from('<I', data, sh_offset + 4)[0]
        sh_fileoff = struct.unpack_from('<Q', data, sh_offset + 0x18)[0]
        sh_size = struct.unpack_from('<Q', data, sh_offset + 0x20)[0]

        name = read_sh_name(sh_name)
        if name == '.dynamic':
            dynamic_section = {'offset': sh_fileoff, 'size': sh_size, 'idx': i}
        elif name == '.dynstr':
            dynstr_section = {'offset': sh_fileoff, 'size': sh_size, 'idx': i}

    if not dynamic_section or not dynstr_section:
        print('Could not find .dynamic or .dynstr section')
        return False

    print(f'.dynamic: offset={dynamic_section["offset"]}, size={dynamic_section["size"]}')
    print(f'.dynstr: offset={dynstr_section["offset"]}, size={dynstr_section["size"]}')

    # Find DT_STRTAB and DT_STRSZ in .dynamic to verify
    DT_NEEDED = 1
    DT_STRTAB = 5
    DT_STRSZ = 10
    DT_NULL = 0

    strtab_addr = None
    strsz = None
    dyn_entries = []

    for j in range(0, dynamic_section['size'], 16):
        tag = struct.unpack_from('<Q', data, dynamic_section['offset'] + j)[0]
        val = struct.unpack_from('<Q', data, dynamic_section['offset'] + j + 8)[0]
        dyn_entries.append((tag, val))
        if tag == DT_STRTAB:
            strtab_addr = val
        elif tag == DT_STRSZ:
            strsz = val
        elif tag == DT_NULL:
            break

    print(f'Current DT_STRTAB: {strtab_addr}, DT_STRSZ: {strsz}')
    print(f'Current NEEDED entries:')
    for tag, val in dyn_entries:
        if tag == DT_NEEDED:
            s = read_str(dynstr_section['offset'] + val)
            print(f'  {s}')

    # Check if libMNN_Express.so is already in NEEDED
    for tag, val in dyn_entries:
        if tag == DT_NEEDED:
            s = read_str(dynstr_section['offset'] + val)
            if s == 'libMNN_Express.so':
                print('libMNN_Express.so already in NEEDED')
                return True

    # Strategy: Append new string to .dynstr and add new DT_NEEDED to .dynamic
    # But we need to ensure there's enough space in the sections.
    # Simple approach: expand the file by inserting space after .dynstr and .dynamic

    # Find where .dynstr ends in the file
    dynstr_end = dynstr_section['offset'] + dynstr_section['size']
    dynamic_end = dynamic_section['offset'] + dynamic_section['size']

    new_string = b'libMNN_Express.so\x00'
    new_string_offset_in_dynstr = dynstr_section['size']  # offset of new string within .dynstr

    # We need to insert new_string bytes after .dynstr section
    # And add 16 bytes (one DT_NEEDED entry) to .dynamic section

    # Find the last section that ends before or at dynstr_end to know insertion point
    # Actually, simpler: just append to the end of the file and update section headers
    # But that would break the layout. Better: insert in place.

    # Let's find the right insertion point - after the section that ends last among
    # sections that come before or at the same position as .dynstr

    # For simplicity, let's insert after the later of .dynstr and .dynamic
    insert_point = max(dynstr_end, dynamic_end)

    # Build new data
    new_data = bytearray(data[:insert_point])

    # Add padding for alignment (16 bytes)
    pad_len = (16 - (len(new_data) % 16)) % 16
    new_data.extend(b'\x00' * pad_len)

    # Append new dynstr content
    new_dynstr_offset = len(new_data)
    new_data.extend(new_string)

    # Append new dynamic entry
    new_dynamic_offset = len(new_data)
    new_data.extend(struct.pack('<QQ', DT_NEEDED, new_string_offset_in_dynstr))
    new_data.extend(struct.pack('<QQ', DT_NULL, 0))  # New terminator

    # Append rest of original file after insert_point
    new_data.extend(data[insert_point:])

    # Now update section headers
    # .dynstr: size increases by len(new_string)
    # .dynamic: size increases by 16

    # Calculate shift for sections after insert_point
    shift = len(new_data) - len(data)
    print(f'File size change: {shift} bytes')

    # Update .dynstr section header
    dynstr_sh = e_shoff + dynstr_section['idx'] * e_shentsize
    struct.pack_into('<Q', new_data, dynstr_sh + 0x20, dynstr_section['size'] + len(new_string))

    # Update .dynamic section header
    dynamic_sh = e_shoff + dynamic_section['idx'] * e_shentsize
    struct.pack_into('<Q', new_data, dynamic_sh + 0x20, dynamic_section['size'] + 16)

    # Update program headers for PT_LOAD/PT_DYNAMIC segments that contain these sections
    for i in range(e_phnum):
        ph_offset = e_phoff + i * e_phentsize
        p_type = struct.unpack_from('<I', new_data, ph_offset)[0]
        p_offset = struct.unpack_from('<Q', new_data, ph_offset + 0x08)[0]
        p_filesz = struct.unpack_from('<Q', new_data, ph_offset + 0x20)[0]
        p_memsz = struct.unpack_from('<Q', new_data, ph_offset + 0x28)[0]

        if p_type == 2:  # PT_DYNAMIC
            # Update to cover new dynamic entries
            if p_offset <= dynamic_section['offset'] < p_offset + p_filesz:
                struct.pack_into('<Q', new_data, ph_offset + 0x20, p_filesz + 16 + pad_len)
                struct.pack_into('<Q', new_data, ph_offset + 0x28, p_memsz + 16 + pad_len)
        elif p_type == 1:  # PT_LOAD
            # Check if this segment contains .dynstr
            if p_offset <= dynstr_section['offset'] < p_offset + p_filesz:
                struct.pack_into('<Q', new_data, ph_offset + 0x20, p_filesz + shift)
                struct.pack_into('<Q', new_data, ph_offset + 0x28, p_memsz + shift)

    # Write output
    with open(output_path, 'wb') as f:
        f.write(new_data)

    print(f'Patched file written to {output_path}')
    print(f'New file size: {len(new_data)}')

    # Verify
    with open(output_path, 'rb') as f:
        verify_data = bytearray(f.read())

    print('Verification:')
    for j in range(0, dynamic_section['size'] + 16, 16):
        tag = struct.unpack_from('<Q', verify_data, dynamic_section['offset'] + j)[0]
        val = struct.unpack_from('<Q', verify_data, dynamic_section['offset'] + j + 8)[0]
        if tag == DT_NEEDED:
            s = read_str(dynstr_section['offset'] + val)
            print(f'  NEEDED: {s}')
        elif tag == DT_NULL:
            break

    return True

if __name__ == '__main__':
    input_file = '/Users/guoshuai/AndroidStudioProjects/PicMe/beauty-engine/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so'
    output_file = '/Users/guoshuai/AndroidStudioProjects/PicMe/beauty-engine/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni-patched.so'
    patch_sherpa_add_mnn_express_needed(input_file, output_file)
