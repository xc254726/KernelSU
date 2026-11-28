#!/system/bin/sh
# Chen 找回守护 —— 开机入口
#
# 装两份，按启动来源分两种场景（和 autoinput 一致）：
#   /data/adb/service.d/chen-guard.sh    普通开机（KSU/Magisk 都会执行 service.d）
#   /data/adb/late-load.d/chen-guard.sh  越狱模式（只执行 late-load.d）
#
# 这里只做一件事：把守护拉起来。守护自己是 init 侧进程，Chen 在不在、有没有被
# 卸载都不影响它 —— 这正是「卸载了还能自己装回来」的前提。
MODDIR=/data/adb/chen_guard
CONF="$MODDIR/conf"

[ -f "$MODDIR/guard.sh" ] || exit 0

# 用户关了开关就别起
enabled=$(sed -n 's/^ENABLED=//p' "$CONF" 2>/dev/null | head -n 1 | tr -d '\r')
[ "$enabled" = "0" ] && exit 0

sh "$MODDIR/guard.sh" start >/dev/null 2>&1
exit 0
