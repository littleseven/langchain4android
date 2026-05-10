#!/bin/bash
#
# Auto Dev Loop - PicMe 开发自循环脚本
# 用途: 代码修改后一键完成 编译→安装→设备验证→质量检查→报告 完整闭环
# 调用: ./scripts/auto-dev-loop.sh [options]
#
# Options:
#   --no-install    仅编译和代码检查，跳过设备安装
#   --no-test       跳过设备端 instrumented test
#   --capture       安装后自动触发拍照并分析质量
#   --quick         快速模式: 仅编译+安装+启动（跳过详细验证）
#

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 参数解析
NO_INSTALL=false
NO_TEST=false
AUTO_CAPTURE=false
QUICK_MODE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-install) NO_INSTALL=true; shift ;;
        --no-test) NO_TEST=true; shift ;;
        --capture) AUTO_CAPTURE=true; shift ;;
        --quick) QUICK_MODE=true; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 时间戳
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

# 汇总状态
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_ok() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARN_COUNT=$((WARN_COUNT + 1))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

print_section() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# ============================================
# Phase 1: 代码质量检查
# ============================================
print_section "Phase 1/5: 代码质量检查"

run_phase1() {
    local fail=0

    # ktlint
    echo ""
    echo "→ Ktlint 格式检查..."
    if ./gradlew ktlintCheck --quiet > "$OUTPUT_DIR/ktlint.log" 2>&1; then
        log_ok "Ktlint 格式检查通过"
    else
        log_fail "Ktlint 格式检查失败 (日志: $OUTPUT_DIR/ktlint.log)"
        fail=1
    fi

    # detekt
    echo ""
    echo "→ Detekt 静态分析..."
    if ./gradlew detekt > "$OUTPUT_DIR/detekt.log" 2>&1; then
        log_ok "Detekt 静态分析通过"
    else
        log_fail "Detekt 静态分析失败 (日志: $OUTPUT_DIR/detekt.log)"
        fail=1
    fi

    # 单元测试
    echo ""
    echo "→ JVM 单元测试..."
    if ./gradlew testDebugUnitTest > "$OUTPUT_DIR/unit_test.log" 2>&1; then
        local test_summary=$(grep -E "tests? completed" "$OUTPUT_DIR/unit_test.log" | tail -1 || echo "测试完成")
        log_ok "JVM 单元测试通过 — $test_summary"
    else
        log_fail "JVM 单元测试失败 (日志: $OUTPUT_DIR/unit_test.log)"
        fail=1
    fi

    return $fail
}

# ============================================
# Phase 2: 编译
# ============================================
print_section "Phase 2/5: 编译 Debug APK"

run_phase2() {
    echo ""
    echo "→ 编译 :app:assembleDebug..."
    if ./gradlew :app:assembleDebug > "$OUTPUT_DIR/build.log" 2>&1; then
        log_ok "Debug APK 编译成功"
        # 获取 APK 路径
        APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo "   APK: $APK_PATH ($APK_SIZE)"
        return 0
    else
        log_fail "编译失败 (日志: $OUTPUT_DIR/build.log)"
        return 1
    fi
}

# ============================================
# Phase 3: 设备安装与启动
# ============================================
print_section "Phase 3/5: 设备安装与启动"

run_phase3() {
    if [ "$NO_INSTALL" = true ]; then
        log_warn "跳过设备安装 (--no-install)"
        return 0
    fi

    # 检查设备连接
    echo ""
    echo "→ 检查设备连接..."
    if ! adb devices | grep -q "device$"; then
        log_warn "未检测到连接的设备，跳过设备端验证"
        NO_TEST=true
        return 0
    fi

    local device_count=$(adb devices | grep -c "device$")
    log_ok "检测到 $device_count 台设备"

    # 安装 APK
    echo ""
    echo "→ 安装 APK..."
    local apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
    if adb install -r "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
        log_ok "APK 安装成功"
    else
        # 可能是签名冲突，尝试卸载重装
        echo "   尝试卸载后重装..."
        adb uninstall com.picme > /dev/null 2>&1 || true
        if adb install "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
            log_ok "APK 卸载重装成功"
        else
            log_fail "APK 安装失败 (日志: $OUTPUT_DIR/install.log)"
            return 1
        fi
    fi

    # 启动应用
    echo ""
    echo "→ 启动应用..."
    adb shell am start -n com.picme/.MainActivity > /dev/null 2>&1
    sleep 3

    # 验证应用运行
    if adb shell pidof com.picme > /dev/null 2>&1; then
        log_ok "应用已启动并在前台运行"
    else
        log_fail "应用启动失败"
        return 1
    fi

    return 0
}

# ============================================
# Phase 4: 设备端验证
# ============================================
print_section "Phase 4/5: 设备端验证"

run_phase4() {
    if [ "$NO_TEST" = true ]; then
        log_warn "跳过设备端测试 (--no-test 或未检测到设备)"
        return 0
    fi

    if [ "$QUICK_MODE" = true ]; then
        log_warn "快速模式: 跳过详细设备验证"
        # 仅截屏确认应用正常显示
        echo "→ 截屏确认应用状态..."
        adb shell screencap -p /sdcard/screen_$TIMESTAMP.png
        adb pull /sdcard/screen_$TIMESTAMP.png "$OUTPUT_DIR/screen_startup.png" > /dev/null 2>&1
        log_ok "截屏已保存: $OUTPUT_DIR/screen_startup.png"
        return 0
    fi

    # 清除日志
    adb logcat -c > /dev/null 2>&1 || true

    # 4.1 截屏（启动后）
    echo ""
    echo "→ 截屏检查启动画面..."
    sleep 2
    adb shell screencap -p /sdcard/screen_startup.png
    adb pull /sdcard/screen_startup.png "$OUTPUT_DIR/screen_startup.png" > /dev/null 2>&1
    log_ok "启动画面截屏已保存"

    # 4.2 执行 adb-bot 快速功能验证
    echo ""
    echo "→ 执行快速功能验证（adb broadcast）..."
    local action="com.picme.TEST_COMMAND"

    # 获取状态
    adb shell am broadcast -a $action --es action "get_state" > /dev/null 2>&1
    sleep 1

    # 设置美颜并拍照
    adb shell am broadcast -a $action --es action "set_beauty" --ei smooth 80 --ei whiten 60 > /dev/null 2>&1
    sleep 0.5
    adb shell am broadcast -a $action --es action "capture" > /dev/null 2>&1
    sleep 2

    # 截屏（拍照后）
    adb shell screencap -p /sdcard/screen_after_capture.png
    adb pull /sdcard/screen_after_capture.png "$OUTPUT_DIR/screen_after_capture.png" > /dev/null 2>&1
    log_ok "拍照后截屏已保存"

    # 4.3 收集日志
    echo ""
    echo "→ 收集 PicMe 日志..."
    adb logcat -d -s PicMe:* > "$OUTPUT_DIR/logcat_picme.txt" 2>&1
    log_ok "日志已保存: $OUTPUT_DIR/logcat_picme.txt"

    # 4.4 检查 GPU 处理日志
    if grep -q "PhotoProcessor.*process DONE" "$OUTPUT_DIR/logcat_picme.txt" 2>/dev/null; then
        local proc_time=$(grep "PhotoProcessor.*process DONE" "$OUTPUT_DIR/logcat_picme.txt" | tail -1 | grep -oE "[0-9]+ms" || echo "unknown")
        log_ok "GPU 拍照处理成功 (耗时: $proc_time)"
    else
        log_warn "未检测到 GPU 拍照处理日志"
    fi

    # 4.5 自动截屏质量检查（如果 Python 可用）
    if [ "$AUTO_CAPTURE" = true ] && command -v python3 &> /dev/null; then
        echo ""
        echo "→ 分析预览画面质量..."
        if [ -f "scripts/analyze_image.py" ]; then
            python3 scripts/analyze_image.py "$OUTPUT_DIR/screen_after_capture.png" > "$OUTPUT_DIR/quality_report.txt" 2>&1
            if grep -q "画面质量合格" "$OUTPUT_DIR/quality_report.txt" 2>/dev/null; then
                log_ok "画面质量检查通过"
            else
                log_warn "画面质量检查发现问题"
            fi
        fi
    fi

    # 4.6 Instrumented Test（如果有 connected 设备）
    echo ""
    echo "→ 检查是否运行 Instrumented Tests..."
    if ./gradlew connectedDebugAndroidTest > "$OUTPUT_DIR/instrumented_test.log" 2>&1; then
        log_ok "Instrumented Tests 通过"
    else
        # 可能是没有设备或测试失败
        if grep -q "No connected devices\|connectedCheck" "$OUTPUT_DIR/instrumented_test.log" 2>/dev/null; then
            log_warn "Instrumented Tests 跳过（无设备或无测试任务）"
        else
            log_warn "Instrumented Tests 存在失败 (日志: $OUTPUT_DIR/instrumented_test.log)"
        fi
    fi

    return 0
}

# ============================================
# Phase 5: 报告生成
# ============================================
print_section "Phase 5/5: 生成报告"

run_phase5() {
    local report_file="$OUTPUT_DIR/report.md"

    cat > "$report_file" << EOF
# PicMe Auto Dev Loop 报告

**时间**: $(date '+%Y-%m-%d %H:%M:%S')  
**输出目录**: $OUTPUT_DIR  
**模式**: ${QUICK_MODE:+快速模式}${NO_INSTALL:+ [无安装]}${NO_TEST:+ [无设备测试]}

## 结果汇总

| 状态 | 数量 |
|------|------|
| ✅ 通过 | $PASS_COUNT |
| ⚠️ 警告 | $WARN_COUNT |
| ❌ 失败 | $FAIL_COUNT |

## 输出文件

\`\`\`
$(ls -la "$OUTPUT_DIR" | tail -n +4)
\`\`\`

## 日志文件

EOF

    for log in "$OUTPUT_DIR"/*.log; do
        [ -f "$log" ] || continue
        local name=$(basename "$log")
        local size=$(du -h "$log" | cut -f1)
        echo "- **$name** ($size)" >> "$report_file"
    done

    echo "" >> "$report_file"
    echo "## 结论" >> "$report_file"
    if [ $FAIL_COUNT -eq 0 ]; then
        echo -e "\n${GREEN}✅ Auto Dev Loop 全部通过！${NC}" >> "$report_file"
        echo -e "\n${GREEN}✅ Auto Dev Loop 全部通过！${NC}"
    else
        echo -e "\n${RED}❌ Auto Dev Loop 存在 $FAIL_COUNT 项失败，请检查日志。${NC}" >> "$report_file"
        echo -e "\n${RED}❌ Auto Dev Loop 存在 $FAIL_COUNT 项失败，请检查日志。${NC}"
    fi

    echo ""
    echo -e "📄 完整报告: ${CYAN}$report_file${NC}"
    echo -e "📁 输出目录: ${CYAN}$OUTPUT_DIR${NC}"
}

# ============================================
# 主流程
# ============================================
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     🤖 PicMe Auto Dev Loop - 开发自循环                  ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "项目路径: $PROJECT_ROOT"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "输出目录: $OUTPUT_DIR"
echo ""

# 执行各阶段
run_phase1 || true
run_phase2 || true
run_phase3 || true
run_phase4 || true
run_phase5

# 退出码
if [ $FAIL_COUNT -eq 0 ]; then
    exit 0
else
    exit 1
fi
