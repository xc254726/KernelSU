#!/system/bin/sh
# Chen 自动输入 - 开机自启入口（KernelSU / Magisk service.d）
# 脚本常驻位置：/data/adb/auto_input/bin
CONF=/data/adb/auto_input
BIN="$CONF/bin"

[ -f "$BIN/common.sh" ] || exit 0

mkdir -p "$CONF" "$CONF/run" 2>/dev/null
echo "[$(date '+%m-%d %H:%M:%S')] boot hook invoked (service.d)" >> "$CONF/daemon.log"

# 清理上次运行残留的 pid 文件，避免单例保护误拦
rm -f "$CONF/run/"*.pid "$CONF/.fg_last" 2>/dev/null

# 后台等待开机完成后再拉起看门狗
(
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 180 ]; do
        sleep 2
        i=$((i + 1))
    done
    sleep 3

    . "$BIN/common.sh"

    chmod 755 "$BIN/"*.sh 2>/dev/null
    mkdir -p "$CONF/run" 2>/dev/null
    rm -f "$CONF/run/"*.pid 2>/dev/null

    start_supervisor "$CONF"
    log "boot: watchdog launched by service.d"
) </dev/null >/dev/null 2>&1 &

exit 0
