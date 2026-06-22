#!/bin/bash
#
# publish-mamba-agent.sh
# langchain4android → JitPack 自动发布脚本
#
# 用法:
#   ./scripts/publish-mamba-agent.sh 1.0.0          # 构建 + 打 tag + 推送到 GitHub 触发 JitPack
#   ./scripts/publish-mamba-agent.sh 1.0.0 --dry    # 试运行，不执行推送
#   ./scripts/publish-mamba-agent.sh 1.0.0 --build-only  # 仅本地编译验证
#
# 依赖: git, gh (GitHub CLI, 可选用于查 JitPack 状态), gradle wrapper
#
# JitPack 对应关系:
#   tag: 1.0.0 → https://jitpack.io/#littleseven/langchain4android/agent-core/1.0.0
#   使用: implementation 'com.github.littleseven.langchain4android:agent-core:1.0.0'

set -euo pipefail

# ─── 颜色 ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ─── 配置 ───
MODULE="agent-core"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JITPACK_BASE="https://jitpack.io/#littleseven/langchain4android"
JITPACK_API_BASE="https://jitpack.io/api/build"

# ─── 辅助函数 ───
usage() {
    echo -e "${CYAN}用法:${NC}"
    echo "  $(basename "$0") <tag> [--dry|--build-only]"
    echo ""
    echo -e "${CYAN}示例:${NC}"
    echo "  $(basename "$0") 1.0.0              # 正式发布"
    echo "  $(basename "$0") 1.0.0-beta1 --dry  # 试运行"
    echo "  $(basename "$0") 1.0.0 --build-only # 仅本地编译"
    echo ""
    echo -e "${CYAN}发布后使用:${NC}"
    echo "  implementation('com.github.littleseven.langchain4android:${MODULE}:${1:-<tag>}')"
    exit 1
}

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ─── 参数解析 ───
TAG="${1:-}"
MODE="${2:-release}"  # release | dry | build-only

if [ -z "$TAG" ]; then
    usage
fi

if [[ ! "$TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    log_error "Tag 格式必须为 X.Y.Z（如 1.0.0），当前: $TAG"
    exit 1
fi

cd "$PROJECT_ROOT"

echo ""
echo "════════════════════════════════════════════════"
echo "  langchain4android JitPack 发布"
echo "  模块: ${MODULE}"
echo "  版本: ${TAG}"
echo "  模式: ${MODE}"
echo "════════════════════════════════════════════════"
echo ""

# ─── Step 0: 检查 git 状态 ───
log_info "检查 git 状态..."
if [ "$MODE" != "build-only" ]; then
    if ! git diff --quiet HEAD; then
        log_warn "工作区有未提交的变更:"
        git status --short
        echo ""
        read -p "是否继续？(y/N) " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_error "已取消发布"
            exit 1
        fi
    fi

    # 检查 tag 是否已存在
    if git tag | grep -q "^${TAG}$"; then
        log_error "Tag ${TAG} 已存在！请使用新版本号。"
        git tag | grep "^${TAG}$"
        exit 1
    fi
fi

# ─── Step 1: 本地编译验证 ───
echo ""
log_info "Step 1/4: 本地编译验证（:${MODULE}:assembleRelease）..."

./gradlew ":${MODULE}:assembleRelease" -x lint -x ktlintCheck -x detekt -x test --no-daemon --console=plain

if [ $? -ne 0 ]; then
    log_error "编译失败，请修复后重试。"
    exit 1
fi

# 检查 AAR 文件是否存在
AAR_FILE="${PROJECT_ROOT}/${MODULE}/build/outputs/aar/${MODULE}-release.aar"
if [ ! -f "$AAR_FILE" ]; then
    log_warn "未找到预期 AAR 文件，尝试搜索..."
    FOUND_AAR=$(find "${PROJECT_ROOT}/${MODULE}/build/outputs" -name "*.aar" 2>/dev/null | head -1)
    if [ -n "$FOUND_AAR" ]; then
        AAR_FILE="$FOUND_AAR"
        log_info "找到 AAR: ${AAR_FILE#$PROJECT_ROOT/}"
    else
        log_error "未找到 AAR 文件，编译可能未成功。"
        exit 1
    fi
else
    log_info "AAR 已生成: ${AAR_FILE#$PROJECT_ROOT/}"
fi

AAR_SIZE=$(du -h "$AAR_FILE" | cut -f1)
log_info "AAR 大小: ${AAR_SIZE}"

# ─── Step 2: 转储依赖树（便于排查 POM） ───
echo ""
log_info "Step 2/4: 生成依赖树..."
./gradlew ":${MODULE}:dependencies" --configuration releaseRuntimeClasspath --no-daemon --console=plain 2>/dev/null | tail -n +2 || true

# ─── 仅构建模式，到此结束 ───
if [ "$MODE" = "build-only" ]; then
    echo ""
    log_info "编译验证通过！"
    echo ""
    echo "AAR:       ${AAR_FILE#$PROJECT_ROOT/}"
    echo "大小:      ${AAR_SIZE}"
    echo ""
    echo "发布到 JitPack 还需执行:"
    echo "  git tag ${TAG}"
    echo "  git push origin ${TAG}"
    echo ""
    echo "JitPack 构建地址: ${JITPACK_BASE}/${TAG}"
    exit 0
fi

# ─── Step 3: 创建并推送 Tag ───
echo ""
log_info "Step 3/4: 创建并推送 Tag..."

# Dry-run 模式
if [ "$MODE" = "dry" ]; then
    log_info "[DRY-RUN] 将执行:"
    echo "  git tag ${TAG}"
    echo "  git push origin ${TAG}"
    echo ""
    log_info "[DRY-RUN] JitPack URL: ${JITPACK_BASE}/${TAG}"
    exit 0
fi

git tag -a "$TAG" -m "agent-core: release ${TAG}"
log_info "Tag ${TAG} 已创建"

git push origin "$TAG"
log_info "Tag ${TAG} 已推送到 origin"

# ─── Step 4: 检查 JitPack 构建状态 ───
echo ""
log_info "Step 4/4: 检查 JitPack 构建状态..."

JITPACK_URL="${JITPACK_BASE}/${TAG}"
JITPACK_API_URL="${JITPACK_API_BASE}/littleseven/langchain4android/${TAG}"

log_info "JitPack 构建页面: ${JITPACK_URL}"
log_info "JitPack API:       ${JITPACK_API_URL}"

# 等待并轮询 JitPack 构建状态
echo ""
log_info "等待 JitPack 开始构建（最长 60s）..."
for i in $(seq 1 12); do
    sleep 5
    BUILD_STATUS=$(curl -s "${JITPACK_API_URL}/status" 2>/dev/null || echo "")
    if echo "$BUILD_STATUS" | grep -q '"status":"DONE"'; then
        echo -e "  ${GREEN}✓${NC} 构建完成！"
        break
    elif echo "$BUILD_STATUS" | grep -q '"status":"ERROR"'; then
        echo -e "  ${RED}✗${NC} 构建失败，查看日志: ${JITPACK_API_URL}/log"
        break
    elif echo "$BUILD_STATUS" | grep -q '"status":"BUILDING"'; then
        echo -e "  ${YELLOW}⌛${NC} 构建中..."
    else
        echo -e "  ${CYAN}⏳${NC} 等待队列中..."
    fi
done

# ─── 完成 ───
echo ""
echo "════════════════════════════════════════════════"
echo "  发布完成！"
echo ""
echo "  JitPack 页面: ${JITPACK_URL}"
echo "  构建日志:    ${JITPACK_API_URL}/log"
echo ""
echo "  Gradle 引用（公开仓库）:"
echo "    implementation('com.github.littleseven.langchain4android:${MODULE}:${TAG}')"
echo ""
echo "  Gradle 引用（私有仓库，需认证）:"
echo "    // settings.gradle.kts:"
echo "    dependencyResolutionManagement {"
echo "      repositories {"
echo "        maven {"
echo "          url = uri(\"https://jitpack.io\")"
echo "          credentials { username = \"<github-username>\"; password = \"<github-token>\" }"
echo "        }"
echo "      }"
echo "    }"
echo "    // build.gradle.kts:"
echo "    implementation('com.github.littleseven.langchain4android:${MODULE}:${TAG}')"
echo ""
echo "  或使用最新版本:"
echo "    implementation('com.github.littleseven.langchain4android:${MODULE}:main-SNAPSHOT')"
echo "════════════════════════════════════════════════"
