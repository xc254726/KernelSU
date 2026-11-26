#!/system/bin/sh
# Auto Input - 开机自启入口（KernelSU / Magisk service.d）
# 内容等同原模块 service.sh，仅把 MODDIR 固定到 /data/adb/auto_input（脚本常驻目录）
MODDIR=/data/adb/auto_input
BIN_DIR="$MODDIR/bin"

# 最早期打点，便于确认本脚本是否真的被执行
mkdir -p /data/adb/auto_input "$MODDIR/run" 2>/dev/null
echo "[$(date '+%m-%d %H:%M:%S')] service.sh invoked (MODDIR=$MODDIR)" >> /data/adb/auto_input/daemon.log

# 开机时清理上一次运行残留的 pid 文件，避免单例保护误拦
rm -f "$MODDIR/run/"*.pid 2>/dev/null

# 后台等待开机完成后拉起看门狗
(
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 180 ]; do
        sleep 2
        i=$((i + 1))
    done
    sleep 3

    . "$BIN_DIR/common.sh"

    chmod 755 "$BIN_DIR/"*.sh 2>/dev/null
    mkdir -p "$MODDIR/run" 2>/dev/null
    rm -f "$MODDIR/run/"*.pid 2>/dev/null

    start_supervisor "$MODDIR"
    log "boot: watchdog launched by service.sh"
) </dev/null >/dev/null 2>&1 &
