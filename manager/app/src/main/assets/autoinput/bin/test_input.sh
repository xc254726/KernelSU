#!/system/bin/sh
# Auto Input - 手动测试（WebUI「测试输入」按钮调用）
# 按当前输入方式执行：script = 跑脚本并喂预输入，screen = 模拟屏幕输入
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

mode=$(get_conf input_mode.txt screen | tr -d '\r\n')
[ -z "$mode" ] && mode="screen"

if [ "$mode" != "screen" ]; then
    sp=$(get_script_path)
    if [ -z "$sp" ] || [ ! -f "$sp" ]; then
        log "manual test: 脚本模式下没有可用脚本，改为屏幕输入"
        mode="screen"
    fi
fi

if [ "$mode" = "screen" ]; then
    log "manual test: 模拟屏幕输入（当前前台需有可输入的输入框）"
    do_input
else
    log "manual test: 执行脚本并喂入预输入"
    run_script_feed
fi
log "manual test done"
