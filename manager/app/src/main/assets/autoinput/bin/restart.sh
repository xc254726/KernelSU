#!/system/bin/sh
# Auto Input - 确保守护进程在运行（WebUI 保存后 / 手动修复时调用）
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

MODDIR="$(cd "$BIN_DIR/.." && pwd)"
mkdir -p "$MODDIR/run" 2>/dev/null

chmod 755 "$BIN_DIR/"*.sh 2>/dev/null

# 干掉旧 daemon，让看门狗用新配置立刻重新拉起
kill_daemon "$MODDIR"

# 旧 daemon 被强杀后，可能还有"保持运行"的脚本进程挂着，一并收掉
_hp=$(cat "$CONF/.script_hold_pid" 2>/dev/null | tr -d '\r\n')
[ -n "$_hp" ] && proc_alive "$_hp" && kill -TERM "$_hp" 2>/dev/null
_cp=$(cat "$CONF/.script_child" 2>/dev/null | tr -d '\r\n')
[ -n "$_cp" ] && proc_alive "$_cp" && kill -TERM "$_cp" 2>/dev/null
rm -f "$CONF/.script_release" "$CONF/.script_child" "$CONF/.script_hold_pid" 2>/dev/null

if supervisor_alive "$MODDIR"; then
    log "restart.sh: daemon killed, watchdog will respawn"
else
    start_supervisor "$MODDIR"
    log "restart.sh: watchdog was down, started"
fi

# keeper 记在开机侧：即使 chen 被划掉，它还会把看护补回来
start_keeper "$MODDIR"

sleep 2
sh "$BIN_DIR/status.sh"
