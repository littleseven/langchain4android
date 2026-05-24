#!/usr/bin/env python3
"""
修复 NCNN param 文件中的行截断问题。
某些转换工具会将一行分成两行（操作符名称单独一行，参数在下一行）。
"""
import sys
import os

OP_NAMES = {
    'Input', 'Convolution', 'ConvolutionDepthWise', 'ReLU', 'PReLU', 'BatchNorm',
    'Pooling', 'MaxPool', 'AveragePool', 'Split', 'BinaryOp', 'Add', 'Mul',
    'Permute', 'Reshape', 'Sigmoid', 'Shape', 'Crop', 'ExpandDims', 'Unsqueeze',
    'Interp', 'Resize', 'Flatten', 'InnerProduct', 'Concat', 'Slice', 'Gather',
    'Transpose', 'Softmax', 'Dropout', 'LRN', 'LSTM', 'RNN', 'GRU', 'MemoryData'
}


def fix_param_file(input_path, output_path=None):
    with open(input_path, 'r') as f:
        lines = f.readlines()

    fixed_lines = []
    i = 0
    merge_count = 0

    while i < len(lines):
        line = lines[i].rstrip('\n\r')
        stripped = line.strip()

        if stripped in OP_NAMES and i + 1 < len(lines):
            next_line = lines[i + 1].rstrip('\n\r')
            next_stripped = next_line.strip()

            # 检查下一行是否以层名开头（不是以数字参数开头）
            if next_stripped and not next_stripped.startswith('0=') and \
               not next_stripped.startswith('1=') and not next_stripped.startswith('-') and \
               not next_stripped.startswith('2='):
                parts = next_stripped.split()
                if len(parts) >= 2 and parts[0] not in OP_NAMES:
                    # 合并两行
                    fixed_lines.append(line + ' ' + next_stripped)
                    merge_count += 1
                    i += 2
                    continue

        fixed_lines.append(line)
        i += 1

    # 确定输出路径
    if output_path is None:
        base, ext = os.path.splitext(input_path)
        output_path = base + '_fixed' + ext

    with open(output_path, 'w') as f:
        for line in fixed_lines:
            f.write(line + '\n')

    print(f'Fixed {merge_count} broken lines.')
    print(f'Output: {output_path}')
    print(f'Total lines: {len(lines)} -> {len(fixed_lines)}')

    # 验证层数
    layer_count = len([l for l in fixed_lines if l.strip() and not l.strip().isdigit()]) - 1  # 减去 header
    header_parts = fixed_lines[1].strip().split() if len(fixed_lines) > 1 else ['0', '0']
    declared_layers = int(header_parts[0]) if len(header_parts) > 0 else 0
    print(f'Declared layers: {declared_layers}, Actual layers: {layer_count}')

    if layer_count != declared_layers:
        print(f'WARNING: Layer count mismatch!')

    return output_path


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f'Usage: {sys.argv[0]} <input.param> [output.param]')
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else None
    fix_param_file(input_file, output_file)
