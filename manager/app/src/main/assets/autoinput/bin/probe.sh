#!/system/bin/sh
# Auto Input - 现场探针：给页面用的只读状态（前台应用 / 输入框焦点 / 读取失败原因）
# 只做查询，不参与任何触发逻辑，daemon.sh 的行为完全不受这里影响。
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

FG=$(get_foreground_pkg)
echo "FG=$FG"

if dumpsys input_method 2>/dev/null | grep -m1 'mInputShown=' | grep -q 'mInputShown=true'; then
    echo "FOCUS=1"
else
    echo "FOCUS=0"
fi

# 读不到前台时，把原始输出也吐出来，方便判断是权限被拒还是格式不认识
if [ -z "$FG" ]; then
    RAW=$(dumpsys activity activities 2>&1 \
        | grep -m1 -E 'topResumedActivity|mResumedActivity' \
        | tr -d '\r' | cut -c1-150)
    [ -z "$RAW" ] && RAW=$(dumpsys activity activities 2>&1 \
        | grep -m1 -i -E 'permission|denial|exception' \
        | tr -d '\r' | cut -c1-150)
    [ -z "$RAW" ] && RAW="(dumpsys 没有任何输出)"
    echo "RAW=$RAW"
fi
