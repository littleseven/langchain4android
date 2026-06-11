#!/bin/bash
#
# PicMe APK 打包脚本
# 支持 debug / release / release-no-proguard 三种模式
#
# 用法:
#   ./scripts/build.sh debug          # 打 debug 包
#   ./scripts/build.sh release        # 打 release 包（使用 app/keystore/picme-release.jks）
#   ./scripts/build.sh release-plain  # 打 release 包但不混淆（用于调试 release 问题）
#
# Release 签名配置从环境变量读取：
#   export PICME_RELEASE_STORE_PASSWORD=your_password
#   export PICME_RELEASE_KEY_ALIAS=your_alias
#   export PICME_RELEASE_KEY_PASSWORD=your_password

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_OUTPUT_DIR="$PROJECT_ROOT/app/build/outputs/apk"
KEYSTORE_PATH="$PROJECT_ROOT/app/keystore/picme-release.jks"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查环境
check_env() {
    if ! command -v ./gradlew &> /dev/null; then
        log_error "gradlew 未找到，请在项目根目录运行"
        exit 1
    fi
}

# 查找最新生成的 APK
find_apk() {
    local dir="$1"
    if [ -d "$dir" ]; then
        find "$dir" -name "*.apk" -type f -print0 | xargs -0 ls -t 2>/dev/null | head -1
    fi
}

# 打印 APK 信息
print_apk_info() {
    local apk="$1"
    if [ -f "$apk" ]; then
        local size
        size=$(du -h "$apk" | cut -f1)
        log_info "APK 路径: $apk"
        log_info "APK 大小: $size"
        # 尝试提取签名信息
        if command -v apksigner &> /dev/null; then
            apksigner verify -v "$apk" 2>/dev/null | head -5 || true
        fi
    fi
}

# 构建 Debug 包
build_debug() {
    log_info "开始构建 Debug 包..."
    ./gradlew :app:assembleDebug

    local apk
    apk=$(find_apk "$APK_OUTPUT_DIR/debug")
    if [ -n "$apk" ]; then
        print_apk_info "$apk"
        log_success "Debug 包构建完成"
    else
        log_error "Debug 包未找到"
        exit 1
    fi
}

# 构建 Release 包
build_release() {
    local plain_mode="${1:-false}"

    # 检查 keystore 存在
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Release keystore 未找到: $KEYSTORE_PATH"
        exit 1
    fi

    # 检查环境变量
    if [ -z "$PICME_RELEASE_STORE_PASSWORD" ]; then
        log_warn "环境变量 PICME_RELEASE_STORE_PASSWORD 未设置"
        read -rsp "请输入 keystore 密码: " PICME_RELEASE_STORE_PASSWORD
        echo
        export PICME_RELEASE_STORE_PASSWORD
    fi

    if [ -z "$PICME_RELEASE_KEY_ALIAS" ]; then
        log_warn "环境变量 PICME_RELEASE_KEY_ALIAS 未设置"
        read -rp "请输入 key alias: " PICME_RELEASE_KEY_ALIAS
        export PICME_RELEASE_KEY_ALIAS
    fi

    if [ -z "$PICME_RELEASE_KEY_PASSWORD" ]; then
        log_warn "环境变量 PICME_RELEASE_KEY_PASSWORD 未设置"
        read -rsp "请输入 key 密码: " PICME_RELEASE_KEY_PASSWORD
        echo
        export PICME_RELEASE_KEY_PASSWORD
    fi

    # 设置 keystore 路径环境变量
    export PICME_RELEASE_STORE_FILE="$KEYSTORE_PATH"

    # 传递构建类型标记给 Gradle
    local plain_flag=""
    if [ "$plain_mode" = "true" ]; then
        plain_flag="-Ppicme.release.plain=true"
        log_info "开始构建 Release 包（不混淆）..."
    else
        log_info "开始构建 Release 包..."
    fi

    ./gradlew :app:assembleRelease \
        -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
        -Pandroid.injected.signing.store.password="$PICME_RELEASE_STORE_PASSWORD" \
        -Pandroid.injected.signing.key.alias="$PICME_RELEASE_KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$PICME_RELEASE_KEY_PASSWORD" \
        $plain_flag \
        --no-configuration-cache

    local apk
    apk=$(find_apk "$APK_OUTPUT_DIR/release")
    if [ -n "$apk" ]; then
        print_apk_info "$apk"
        if [ "$plain_mode" = "true" ]; then
            log_success "Release 包（不混淆）构建完成"
        else
            log_success "Release 包构建完成"
        fi
    else
        log_error "Release 包未找到"
        exit 1
    fi
}

# 主入口
main() {
    cd "$PROJECT_ROOT"
    check_env

    local mode="${1:-debug}"

    case "$mode" in
        debug)
            build_debug
            ;;
        release)
            build_release false
            ;;
        release-plain|release-plain|plain)
            build_release true
            ;;
        *)
            echo "用法: $0 {debug|release|release-plain}"
            echo ""
            echo "  debug          - 构建 debug 包"
            echo "  release        - 构建 release 包（使用 app/keystore/picme-release.jks）"
            echo "  release-plain  - 构建 release 包但不启用混淆/R8"
            echo ""
            echo "Release 签名需要环境变量："
            echo "  PICME_RELEASE_STORE_PASSWORD  - keystore 密码"
            echo "  PICME_RELEASE_KEY_ALIAS       - key alias"
            echo "  PICME_RELEASE_KEY_PASSWORD    - key 密码"
            exit 1
            ;;
    esac
}

main "$@"
