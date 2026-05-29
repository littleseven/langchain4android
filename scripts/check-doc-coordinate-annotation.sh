#!/bin/bash
# 检测文档中未标注坐标系的左右描述
# 用法: ./scripts/check-doc-coordinate-annotation.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "🔍 检查文档中的坐标系标注..."
echo ""

# 搜索 Markdown 文件中的模糊描述
RESULTS=$(grep -rn "左眼\|右眼\|左眉\|右眉" "$PROJECT_ROOT/docs/" --include="*.md" 2>/dev/null | \
    grep -v "\[图像坐标系\]" | \
    grep -v "\[人脸坐标系\]" | \
    grep -v "图像左侧" | \
    grep -v "图像右侧" | \
    grep -v "被拍摄者" || true)

if [ ! -z "$RESULTS" ]; then
    echo -e "\033[31m❌ 发现未标注坐标系的描述:\033[0m"
    echo "$RESULTS"
    echo ""
    echo -e "\033[31m❌ 请参考 docs/07-STANDARDS/COORDINATE_SYSTEM.md 修复\033[0m"
    exit 1
else
    echo -e "\033[32m✅ 文档坐标系标注检查通过\033[0m"
    exit 0
fi
