#!/bin/bash
#
# Agent Test - PicMe AI Agent 自动化测试入口
# 用途: 从 AI Agent 侧触发设备端测试，接收结果并生成报告
# 调用: ./scripts/agent-test.sh [command] [options]
#
# Commands:
#   suite <name>       运行测试套件 (camera/beauty/p0)
#   case <id>          运行单个测试用例 (如 TC-CAMERA-01)
#   status             获取当前测试状态
#   report             导出测试报告
#   interactive        交互模式（自动检测并执行 P0 回归）
#
# Options:
#   --wait <seconds>   等待测试完成的超时时间 (默认 300)
#   --output <dir>     报告输出目录 (默认 scripts/auto_test_output/agent_<timestamp>)
#   --json             输出 JSON 格式报告
#   --markdown         输出 Markdown 格式报告

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

# 参数
COMMAND=""
ARG=""
WAIT_TIMEOUT=300
OUTPUT_DIR=""
FORMAT="json"

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        suite|case|status|report|interactive)
            COMMAND="$1"
            shift
            if [[ $# -gt 0 && ! "$1" =~ ^-- ]]; then
                ARG="$1"
                shift
            fi
            ;;
        --wait)
            WAIT_TIMEOUT="$2"
            shift 2
            ;;
        --output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --json)
            FORMAT="json"
            shift
            ;;
        --markdown)
            FORMAT="markdown"
            shift
            ;;
        *)
            echo "未知参数: $1"
            exit 1
            ;;
    esac
done

# 默认输出目录
if [ -z "$OUTPUT_DIR" ]; then
    TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
    OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/agent_$TIMESTAMP"
fi
mkdir -p "$OUTPUT_DIR"

# ============================================
# 工具函数
# ============================================

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok() { echo -e "${GREEN}[PASS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; }

# 检查设备连接
check_device() {
    if ! adb devices | grep -q "device$"; then
        log_fail "未检测到连接的设备"
        exit 1
    fi
    local device=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
    log_info "设备: $device"
}

# 发送广播命令并等待响应
send_command() {
    local action="$1"
    local extra_key="$2"
    local extra_value="$3"

    # 清除旧日志
    adb logcat -c > /dev/null 2>&1 || true

    # 发送广播
    if [ -n "$extra_key" ] && [ -n "$extra_value" ]; then
        adb shell am broadcast -a com.picme.AGENT_TEST --es "$extra_key" "$extra_value" > /dev/null 2>&1
    else
        adb shell am broadcast -a com.picme.AGENT_TEST --es action "$action" > /dev/null 2>&1
    fi

    echo "命令已发送，等待响应..."
}

# 等待并解析响应
wait_for_response() {
    local timeout="${1:-$WAIT_TIMEOUT}"
    local elapsed=0
    local response_file="$OUTPUT_DIR/response.json"

    while [ $elapsed -lt $timeout ]; do
        # 从日志中提取响应
        local response=$(adb logcat -d | grep -oP 'AgentTestReceiver: Response sent: \K.*' | tail -1)

        if [ -n "$response" ]; then
            echo "$response" > "$response_file"
            echo "$response"
            return 0
        fi

        sleep 2
        elapsed=$((elapsed + 2))
        echo -n "."
    done

    echo ""
    log_warn "等待响应超时 (${timeout}s)"
    return 1
}

# 编译并安装（如果需要）
ensure_app_installed() {
    if ! adb shell pm list packages | grep -q "com.picme"; then
        log_info "应用未安装，开始编译..."
        ./gradlew :app:assembleDebug > "$OUTPUT_DIR/build.log" 2>&1
        local apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
        adb install -r "$apk" > "$OUTPUT_DIR/install.log" 2>&1
        log_ok "应用安装完成"
    fi
}

# ============================================
# 命令实现
# ============================================

cmd_suite() {
    local suite="${1:-p0}"
    log_info "运行测试套件: $suite"

    send_command "" "suite" "$suite"
    local response=$(wait_for_response)

    if [ -n "$response" ]; then
        echo "$response" | python3 -m json.tool > "$OUTPUT_DIR/${suite}_report.json" 2>/dev/null || echo "$response" > "$OUTPUT_DIR/${suite}_report.json"
        log_ok "报告已保存: $OUTPUT_DIR/${suite}_report.json"

        # 解析结果
        local passed=$(echo "$response" | grep -o '"passedCount":[0-9]*' | cut -d: -f2)
        local failed=$(echo "$response" | grep -o '"failedCount":[0-9]*' | cut -d: -f2)

        echo ""
        echo "结果汇总:"
        echo -e "  ${GREEN}通过: ${passed:-0}${NC}"
        echo -e "  ${RED}失败: ${failed:-0}${NC}"
    fi
}

cmd_case() {
    local case_id="${1:-TC-CAMERA-01}"
    log_info "运行测试用例: $case_id"

    send_command "" "case" "$case_id"
    local response=$(wait_for_response)

    if [ -n "$response" ]; then
        echo "$response" | python3 -m json.tool > "$OUTPUT_DIR/${case_id}_result.json" 2>/dev/null || echo "$response" > "$OUTPUT_DIR/${case_id}_result.json"

        local status=$(echo "$response" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
        if [ "$status" = "passed" ]; then
            log_ok "$case_id 通过"
        else
            log_fail "$case_id 失败"
            echo "$response" | grep -o '"reason":"[^"]*"' | cut -d'"' -f4
        fi
    fi
}

cmd_status() {
    log_info "获取测试状态"
    send_command "status"
    local response=$(wait_for_response 10)

    if [ -n "$response" ]; then
        echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
    fi
}

cmd_report() {
    log_info "导出测试报告"
    send_command "export_report"
    local response=$(wait_for_response)

    if [ -n "$response" ]; then
        # 保存 Markdown 报告
        echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('markdown',''))" > "$OUTPUT_DIR/report.md" 2>/dev/null || true

        # 保存 JSON 报告
        echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d, indent=2, ensure_ascii=False))" > "$OUTPUT_DIR/report.json" 2>/dev/null || echo "$response" > "$OUTPUT_DIR/report.json"

        log_ok "报告已导出到 $OUTPUT_DIR"
    fi
}

cmd_interactive() {
    echo ""
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║     🤖 PicMe Agent 测试 - 交互模式                       ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""

    check_device
    ensure_app_installed

    # 启动应用
    log_info "启动 PicMe..."
    adb shell am start -n com.picme/.MainActivity > /dev/null 2>&1
    sleep 3

    # 执行 P0 回归
    cmd_suite "p0"

    # 拉取截屏
    log_info "拉取截屏..."
    adb shell ls /sdcard/PicMe_Agent_Test/ 2>/dev/null | while read -r file; do
        if [ -n "$file" ]; then
            adb pull "/sdcard/PicMe_Agent_Test/$file" "$OUTPUT_DIR/" > /dev/null 2>&1 || true
        fi
    done

    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  测试完成${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "输出目录: $OUTPUT_DIR"
}

# ============================================
# 主流程
# ============================================

case "$COMMAND" in
    suite)
        check_device
        ensure_app_installed
        cmd_suite "$ARG"
        ;;
    case)
        check_device
        ensure_app_installed
        cmd_case "$ARG"
        ;;
    status)
        check_device
        cmd_status
        ;;
    report)
        check_device
        cmd_report
        ;;
    interactive)
        cmd_interactive
        ;;
    *)
        echo "PicMe Agent 测试脚本"
        echo ""
        echo "用法: ./scripts/agent-test.sh <command> [options]"
        echo ""
        echo "Commands:"
        echo "  suite <name>     运行测试套件 (camera/beauty/p0)"
        echo "  case <id>        运行单个测试用例"
        echo "  status           获取当前测试状态"
        echo "  report           导出测试报告"
        echo "  interactive      交互模式（完整 P0 回归）"
        echo ""
        echo "Options:"
        echo "  --wait <seconds> 等待超时时间 (默认 300)"
        echo "  --output <dir>   报告输出目录"
        echo "  --json           JSON 格式输出"
        echo "  --markdown       Markdown 格式输出"
        echo ""
        echo "示例:"
        echo "  ./scripts/agent-test.sh suite camera"
        echo "  ./scripts/agent-test.sh case TC-CAMERA-01"
        echo "  ./scripts/agent-test.sh interactive --wait 600"
        exit 0
        ;;
esac
