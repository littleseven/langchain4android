#!/bin/bash
#
# AI Gate - PicMe AI Coding 自动化验证脚本
# 用途：AI 提交代码前的一键完整验证
# 调用：./scripts/ai-gate.sh [options]
#

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0

run_check() {
    local name="$1"
    local cmd="$2"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🔍 $name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if eval "$cmd"; then
        echo -e "${GREEN}✅ PASS${NC}: $name"
        ((PASS_COUNT++))
        return 0
    else
        echo -e "${RED}❌ FAIL${NC}: $name"
        ((FAIL_COUNT++))
        return 1
    fi
}

echo "🤖 PicMe AI Gate - 自动化验证开始"
echo "===================================="
echo "项目路径: $PROJECT_ROOT"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# 1. 代码格式检查 (ktlint)
run_check "Ktlint Format Check" "./gradlew ktlintCheck --quiet"

# 2. 静态代码分析 (detekt)
run_check "Detekt Static Analysis" "./gradlew detekt"

# 3. JVM 单元测试
run_check "JVM Unit Tests" "./gradlew testDebugUnitTest"

# 4. 编译验证
run_check "Debug Build" "./gradlew assembleDebug"

# 5. 文档一致性检查
if [ -f "scripts/check_doc_sync.py" ]; then
    run_check "Document Sync Check" "python3 scripts/check_doc_sync.py"
else
    echo -e "${YELLOW}⚠️ SKIP${NC}: Document Sync Check (scripts/check_doc_sync.py 不存在)"
fi

# 6. 检查 TODO/FIXME 数量（警告级别）
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 代码健康度指标"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
TODO_COUNT=$(grep -rn "TODO\|FIXME\|XXX" --include="*.kt" app/src/main beauty-engine/src/main 2>/dev/null | wc -l | tr -d ' ')
echo "   TODO/FIXME 数量: $TODO_COUNT"

# 汇总
echo ""
echo "===================================="
echo "📋 验证结果汇总"
echo "===================================="
echo -e "   ${GREEN}通过: $PASS_COUNT${NC}"
echo -e "   ${RED}失败: $FAIL_COUNT${NC}"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}🎉 AI Gate 全部通过！代码可以安全提交。${NC}"
    exit 0
else
    echo -e "${RED}⛔ AI Gate 存在 $FAIL_COUNT 项失败，请修复后重试。${NC}"
    exit 1
fi
