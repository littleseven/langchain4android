#!/usr/bin/env python3
"""
将 PNNX 导出的 NCNN 模型转换为只使用标准 NCNN 层的模型。

方法：
1. 对所有标准 weight 层（Convolution/ConvDW），逐个读取 .bin flag 精确计算大小
2. PNNX 层和 helper 层不推进 offset
3. 最后用 total_PNNX = bin_size - final_offset 算出 PNNX 数据块大小
4. PNNX 块后的 weight 层 offset 增加 total_PNNX
5. 不重映射 blob ID
"""
import sys
import os
import struct

FLOAT16_TAG = 0x01306B47
INT8_TAG = 0x000D4B38
FLOAT32_TAG = 0x0002C056

EXTRA_REMOVE_NAMES = {
    'unsqueeze_102', 'unsqueeze_103', 'unsqueeze_104', 'unsqueeze_105',
    'splitncnn_5', 'cat_0', 'cat_1',
}


def align_size(sz, n):
    return (sz + n - 1) & ~(n - 1)


def calc_weight_data_size(flag_tag, flag_bytes, ws):
    """计算 4-byte flag 之后的 weight 数据大小"""
    flag_sum = sum(flag_bytes)
    if flag_tag == FLOAT16_TAG:
        return align_size(ws * 2, 4)
    elif flag_tag == INT8_TAG:
        return align_size(ws, 4)
    elif flag_tag == FLOAT32_TAG:
        return ws * 4
    elif flag_sum != 0:
        return 256 * 4 + align_size(ws, 4)
    else:
        return ws * 4


def is_pnnx_layer(t):
    return t.startswith('pnnx.') or t.startswith('F.')


def is_std_weight(t):
    return t in ('Convolution', 'ConvolutionDepthWise', 'InnerProduct',
                 'Deconvolution', 'DeconvolutionDepthWise')


def parse_param_line(line):
    parts = line.strip().split()
    if len(parts) < 5:
        return None
    t, name = parts[0], parts[1]
    ic, oc = int(parts[2]), int(parts[3])
    idx = 4
    inputs, outputs = [], []
    for _ in range(ic):
        v = parts[idx]
        try:
            inputs.append(int(v))
        except ValueError:
            inputs.append(v)
        idx += 1
    for _ in range(oc):
        v = parts[idx]
        try:
            outputs.append(int(v))
        except ValueError:
            outputs.append(v)
        idx += 1
    params = {}
    while idx < len(parts):
        if '=' in parts[idx]:
            k, v = parts[idx].split('=', 1)
            try:
                params[int(k)] = int(v)
            except ValueError:
                try:
                    params[int(k)] = float(v)
                except ValueError:
                    params[k] = v
        idx += 1
    return {'type': t, 'name': name, 'input_count': ic, 'output_count': oc,
            'inputs': inputs, 'outputs': outputs, 'params': params}


def describe_flag(flag_tag, flag_bytes):
    flag_sum = sum(flag_bytes)
    if flag_tag == FLOAT16_TAG:
        return 'f16'
    elif flag_tag == INT8_TAG:
        return 'i8'
    elif flag_tag == FLOAT32_TAG:
        return 'f32_raw'
    elif flag_sum != 0:
        return 'qtz'
    else:
        return 'f32'


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <input.param> <input.bin> [output.param] [output.bin]")
        sys.exit(1)

    input_param = sys.argv[1]
    input_bin = sys.argv[2]
    output_param = sys.argv[3] if len(sys.argv) >= 4 else input_param.replace('.param', '_std.param')
    output_bin = sys.argv[4] if len(sys.argv) >= 5 else input_bin.replace('.bin', '_std.bin')

    # 读取 .bin
    with open(input_bin, 'rb') as f:
        bin_data = f.read()
    bin_size = len(bin_data)

    # 解析 .param
    with open(input_param, 'r') as f:
        lines = [l.rstrip('\n\r') for l in f.readlines()]
    assert lines[0].strip() == '7767517'
    hl = lines[1].strip().split()
    orig_lc, orig_bc = int(hl[0]), int(hl[1])

    layers = []
    for line in lines[2:]:
        if line.strip():
            layers.append(parse_param_line(line))
    assert len(layers) == orig_lc

    print(f"Input: {orig_lc} layers, {orig_bc} blobs, {bin_size} bytes .bin")

    # ================================================================
    # Step 1: 逐层计算偏移和大小
    # 策略：只对标准 weight 层推进 offset。PNNX/helper 层 size=0，不推进。
    # 最后 total_PNNX = bin_size - 最终 offset
    # ================================================================
    layer_info = []  # [(raw_offset, size, fmt)]
    raw_offset = 0

    # 第一次 pass：只计算顺序 offset（忽略 PNNX 数据的间隙）
    for i, layer in enumerate(layers):
        t = layer['type']
        p = layer['params']

        if is_std_weight(t):
            ws = p.get(6, 0)
            no = p.get(0, 0)
            bt = p.get(5, 0)

            if raw_offset + 4 > bin_size:
                layer_info.append((raw_offset, 0, 'ERR_OOB'))
                continue

            flag_bytes = bin_data[raw_offset:raw_offset + 4]
            flag_tag = struct.unpack('I', flag_bytes)[0]
            data_size = calc_weight_data_size(flag_tag, flag_bytes, ws)
            total = 4 + data_size + (no * 4 if bt == 1 else 0)
            fmt = describe_flag(flag_tag, flag_bytes)
            detail = f'{fmt}(ws={ws},no={no})'
            layer_info.append((raw_offset, total, detail))
            raw_offset += total
        else:
            # No weight data (Input, Split, PNNX, etc.)
            layer_info.append((raw_offset, 0, 'skip'))

    # 计算 PNNX 块大小
    pnnx_block_size = bin_size - raw_offset
    print(f"\nRaw sequential offset: {raw_offset}")
    print(f"PNNX block size:       {pnnx_block_size} bytes")

    # 第二次 pass：精确标记要移除的层
    # PNNX 层 + 仅用于 PNNX 子图的 helper 层
    # 注意：add_0 (41+53->54), splitncnn_6 (54->55,56), add_1 (37+62->63)
    # 是标准 NCNN 层，作用于 Interp 的输出，必须保留！
    # F.interpolate 不标记移除，而是替换为 Interp
    pnnx_block_indices = set()
    f_interpolate_indices = set()  # 这些要替换为 Interp
    for i, layer in enumerate(layers):
        if layer['type'] == 'F.interpolate':
            f_interpolate_indices.add(i)
        elif is_pnnx_layer(layer['type']):
            pnnx_block_indices.add(i)
        if layer['name'] in EXTRA_REMOVE_NAMES:
            pnnx_block_indices.add(i)

    print(f"PNNX block layer indices: {sorted(pnnx_block_indices)}")

    # 修正 offset：PNNX 块后的所有层 offset 增加 pnnx_block_size
    # 确定哪些层在 PNNX 块之后
    max_pnnx_idx = max(pnnx_block_indices) if pnnx_block_indices else -1

    corrected_info = []
    for i, (off, sz, fmt) in enumerate(layer_info):
        if i > max_pnnx_idx and sz > 0:
            # Layer is after PNNX block, correct offset
            corrected_info.append((off + pnnx_block_size, sz, fmt))
        elif i in pnnx_block_indices:
            corrected_info.append((off, 0, f'REMOVED({fmt})'))
        else:
            corrected_info.append((off, sz, fmt))

    # 验证
    final_verified_offset = 0
    for off, sz, fmt in corrected_info:
        end = off + sz if sz > 0 else off
        if end > final_verified_offset:
            final_verified_offset = end
    print(f"Final verified offset: {final_verified_offset}/{bin_size}")

    # 打印前几个和关键层的 offset
    print("\n=== Key weight layer offsets ===")
    for i, (off, sz, fmt) in enumerate(corrected_info):
        if sz > 0 or i in pnnx_block_indices:
            layer = layers[i]
            marker = ' [REMOVE]' if i in pnnx_block_indices else ''
            print(f"  layer {i:3d}: offset={off:8d} sz={sz:6d} {layer['type']:25s} {layer['name']:20s} {fmt}{marker}")

    # 构建新 param
    new_layers = []
    for i, layer in enumerate(layers):
        if i in pnnx_block_indices:
            continue

        new_layer = dict(layer)
        if i in f_interpolate_indices:
            # 替换为 Interp：只需要 data 输入，去掉 size 输入
            new_layer['type'] = 'Interp'
            new_layer['inputs'] = [layer['inputs'][0]]
            new_layer['input_count'] = 1
            new_layer['params'] = {0: 2, 1: 2.0, 2: 2.0}

        new_layers.append(new_layer)

    new_layer_count = len(new_layers)

    # 计算 blob count
    used_blobs = set()
    string_blobs = set()
    for layer in new_layers:
        for b in layer['inputs']:
            if isinstance(b, int):
                used_blobs.add(b)
            elif isinstance(b, str):
                string_blobs.add(b)
        for b in layer['outputs']:
            if isinstance(b, int):
                used_blobs.add(b)
            elif isinstance(b, str):
                string_blobs.add(b)

    max_blob_id = max(used_blobs) if used_blobs else 0
    new_blob_count = max_blob_id + 1 + len(string_blobs)

    with open(output_param, 'w') as f:
        f.write('7767517\n')
        f.write(f'{new_layer_count} {new_blob_count}\n')
        for layer in new_layers:
            parts = [layer['type'], layer['name'],
                     str(layer['input_count']), str(layer['output_count'])]
            for b in layer['inputs']:
                parts.append(str(b))
            for b in layer['outputs']:
                parts.append(str(b))
            for key, val in layer['params'].items():
                if isinstance(val, float):
                    parts.append(f'{key}={val}')
                else:
                    parts.append(f'{key}={val}')
            f.write(' '.join(parts) + '\n')

    print(f"\nNew .param: {output_param}")
    print(f"  {new_layer_count} layers, {new_blob_count} blobs")

    # ================================================================
    # Step 3: 写新 .bin
    # ================================================================
    with open(output_bin, 'wb') as f:
        for i, (off, sz, fmt) in enumerate(corrected_info):
            if i in pnnx_block_indices:
                continue
            if sz > 0 and off + sz <= bin_size:
                f.write(bin_data[off:off + sz])

    new_bin_size = os.path.getsize(output_bin)
    print(f"New .bin: {output_bin} ({new_bin_size} bytes)")

    # Summary
    removed_weight = sum(sz for i, (_, sz, _) in enumerate(corrected_info)
                         if i in pnnx_block_indices and sz > 0)
    print(f"\n=== Summary ===")
    print(f"Original:  {orig_lc:3d} layers, {bin_size:9d} bytes .bin")
    print(f"New:       {new_layer_count:3d} layers, {new_bin_size:9d} bytes .bin")
    print(f"Removed:   {len(pnnx_block_indices):3d} layers ({removed_weight:9d} bytes)")
    print(f"\nChanges:")
    print(f"  - 5 pnnx.Expression removed")
    print(f"  - 2 F.interpolate -> Interp (bilinear, scale=2.0)")
    print(f"  - Helper layers removed (unsqueeze_10x, splitncnn_5, cat_0, cat_1)")
    print(f"  - Blob IDs preserved")


if __name__ == '__main__':
    main()
