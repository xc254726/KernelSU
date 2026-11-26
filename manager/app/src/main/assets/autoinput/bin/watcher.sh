#!/system/bin/sh
# Auto Input - 前台应用事件监听（logcat events）
# 作用：dumpsys 在部分 ROM / SELinux 下读不到前台包名时，用系统事件补上触发来源
# 输出：把最近一次 resume 的包名写进 /data/adb/auto_input/.fg_last（格式 时间戳|包名）
BIN_DIR="${0%/*}"
MODDIR="$(cd "$BIN_DIR/.." && pwd)"
. "$BIN_DIR/common.sh"

mkdir -p "$CONF" "$MODDIR/run" 2>/dev/null
PIDF="$MODDIR/run/watcher.pid"

# 单例
OLD=$(read_pid "$PIDF")
if [ -n "$OLD" ] && [ "$OLD" != "$$" ] && pid_matches "$OLD" watcher.sh; then
    exit 0
fi
echo "$$" > "$PIDF"
log "watcher up (pid=$$)"

TAGS="am_resume_activity am_focused_activity wm_on_resume_called"

while true; do
    # -T 1：从最近一行开始，避免重复处理缓冲区里的历史事件
    logcat -b events -v brief -T 1 -s $TAGS 2>>"$LOG" \
    | while IFS= read -r line; do
        pkg=$(printf '%s' "$line" \
            | sed -nE 's/.*[^A-Za-z0-9_.]([A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+)\/.*/\1/p' \
            | head -n 1)
        [ -z "$pkg" ] && continue
        is_valid_pkg "$pkg" || continue
        printf '%s|%s\n' "$(date +%s)" "$pkg" > "$CONF/.fg_last"
    done

    log "watcher: logcat 断开，1 秒后重连"
    sleep 1
done
