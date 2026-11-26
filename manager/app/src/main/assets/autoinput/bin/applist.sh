#!/system/bin/sh
# Auto Input - 应用列表 / 名称 / 图标
# 用法:
#   applist.sh list                 用户应用（第三方、桌面可启动）包名，每行一个
#   applist.sh detail pkg1 pkg2 ... 输出 pkg|应用名|图标base64
#   applist.sh debug                输出环境与各取列表方式的诊断信息

CONF=/data/adb/auto_input
CACHE="$CONF/cache"
mkdir -p "$CACHE" 2>/dev/null
TMPD=/data/local/tmp
mkdir -p "$TMPD" 2>/dev/null

# ---------- 工具定位 ----------

# unzip：优先系统，其次各版本 KernelSU/Magisk 自带 busybox
find_unzip() {
    if command -v unzip >/dev/null 2>&1; then
        echo "unzip"
        return 0
    fi
    local bb
    for bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox \
              /data/adb/ap/bin/busybox /system/xbin/busybox /system/bin/busybox; do
        if [ -x "$bb" ] && "$bb" unzip 2>&1 | grep -qi 'usage'; then
            echo "$bb unzip"
            return 0
        fi
    done
    if command -v busybox >/dev/null 2>&1 && busybox unzip 2>&1 | grep -qi 'usage'; then
        echo "busybox unzip"
        return 0
    fi
    echo ""
}

UZ=$(find_unzip)

B64="base64"
if ! command -v base64 >/dev/null 2>&1; then
    for bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /system/xbin/busybox; do
        [ -x "$bb" ] && B64="$bb base64" && break
    done
fi

# ---------- 应用名 ----------

# 取应用显示名（不同 ROM / Android 版本字段不一样，尽量都试）
get_label() {
    local pkg="$1" out v
    out=$(pm dump "$pkg" 2>/dev/null)
    [ -z "$out" ] && out=$(dumpsys package "$pkg" 2>/dev/null)

    for key in resolvedLabel nonLocalizedLabel; do
        v=$(printf '%s\n' "$out" | grep -m1 "$key=" 2>/dev/null \
            | sed -E "s/.*$key=//" \
            | sed "s/^'//; s/'$//" \
            | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
        case "$v" in
            ''|null|null,*|N/A|0x*|false|true) continue ;;
        esac
        printf '%s' "$v" | tr -d '|'
        return 0
    done
    return 0
}

# 探测本机是否能取到应用名（绝大多数 ROM 的 dumpsys 没有名字字段）
LABEL_PROBE="$CONF/label_probe"
label_supported() {
    if [ -s "$LABEL_PROBE" ]; then
        cat "$LABEL_PROBE"
        return 0
    fi
    local p l
    for p in $(pm list packages -3 2>/dev/null | head -4 | sed 's/^package://' | tr -d '\r'); do
        l=$(get_label "$p")
        if [ -n "$l" ]; then
            echo "1" > "$LABEL_PROBE" 2>/dev/null
            echo "1"
            return 0
        fi
    done
    echo "0" > "$LABEL_PROBE" 2>/dev/null
    echo "0"
}

# ---------- 图标 ----------

# 读 PNG 宽高（IHDR 位于偏移 16，大端 4 字节 x2）
png_wh() {
    local hex
    hex=$(od -An -tx1 -j16 -N8 "$1" 2>/dev/null | tr -d ' \n')
    [ ${#hex} -eq 16 ] || return 1
    echo "$((0x$(printf '%s' "$hex" | cut -c1-8))) $((0x$(printf '%s' "$hex" | cut -c9-16)))"
}

# 从 APK 解出一张图标并输出 base64
get_icon() {
    [ -z "$UZ" ] && return 0
    local pkg="$1" apk lst cands name tmp b64 w h
    apk=$(pm path "$pkg" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')
    [ -n "$apk" ] && [ -f "$apk" ] || return 0

    lst=$($UZ -l "$apk" 2>/dev/null | grep -oE 'res/[A-Za-z0-9_./$-]+\.(png|webp)')
    [ -z "$lst" ] && return 0

    tmp="$TMPD/ai_icon_$$.bin"

    # 1) 常规命名：ic_launcher / app_icon / ic_app
    name=$(printf '%s\n' "$lst" | grep -iE 'ic_launcher|app_icon|ic_app' | grep -iv 'round' | head -1)
    [ -z "$name" ] && name=$(printf '%s\n' "$lst" | grep -iE 'ic_launcher|app_icon|ic_app' | head -1)

    # 2) 资源被混淆（res/aB.png 之类）：找方形 PNG
    if [ -z "$name" ]; then
        cands=$(printf '%s\n' "$lst" | grep -v '\.webp$' | head -24)
        for name in $cands; do
            $UZ -p "$apk" "$name" > "$tmp" 2>/dev/null || continue
            [ -s "$tmp" ] || continue
            set -- $(png_wh "$tmp")
            w=$1; h=$2
            if [ -n "$w" ] && [ "$w" = "$h" ] && [ "$w" -ge 36 ] && [ "$w" -le 512 ]; then
                name="$name"
                break
            fi
            name=""
        done
    fi

    if [ -z "$name" ]; then
        rm -f "$tmp" 2>/dev/null
        return 0
    fi

    $UZ -p "$apk" "$name" > "$tmp" 2>/dev/null
    if [ -s "$tmp" ]; then
        b64=$($B64 < "$tmp" 2>/dev/null | tr -d '\n\r')
        rm -f "$tmp" 2>/dev/null
        printf '%s' "$b64"
    else
        rm -f "$tmp" 2>/dev/null
    fi
    return 0
}

# ---------- 主逻辑 ----------

# 从 query-activities 输出里提取包名（兼容 --brief 与完整格式）
pkgs_from_query() {
    sed -n -E 's#^([A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+)/(.*)$#\1#p; s#^[[:space:]]*packageName=([A-Za-z0-9_.]+).*#\1#p' 2>/dev/null \
        | tr -d '\r' | sort -u
}

case "$1" in
list)
    TMP_L="$TMPD/ai_launch.txt"
    TMP_T="$TMPD/ai_third.txt"

    cmd package query-activities --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER 2>/dev/null | pkgs_from_query > "$TMP_L"

    if [ ! -s "$TMP_L" ]; then
        cmd package query-activities \
            -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER 2>/dev/null | pkgs_from_query > "$TMP_L"
    fi

    pm list packages -3 2>/dev/null | sed 's/^package://' | tr -d '\r' | sort -u > "$TMP_T"

    if [ -s "$TMP_L" ] && [ -s "$TMP_T" ]; then
        grep -Fxf "$TMP_T" "$TMP_L"
    elif [ -s "$TMP_L" ]; then
        cat "$TMP_L"
    else
        cat "$TMP_T" 2>/dev/null
    fi

    rm -f "$TMP_L" "$TMP_T" 2>/dev/null
    ;;

detail)
    shift
    LBL=$(label_supported)
    for pkg in "$@"; do
        [ -z "$pkg" ] && continue
        cf="$CACHE/$pkg.txt"
        if [ -s "$cf" ]; then
            LABEL=$(sed -n '1p' "$cf" 2>/dev/null)
            ICON=$(sed -n '2p' "$cf" 2>/dev/null)
            printf '%s|%s|%s\n' "$pkg" "$LABEL" "$ICON"
            continue
        fi
        LABEL=""
        [ "$LBL" = "1" ] && LABEL=$(get_label "$pkg")
        ICON=$(get_icon "$pkg")
        printf '%s|%s|%s\n' "$pkg" "$LABEL" "$ICON"
        # 只有拿到内容才缓存，避免偶发失败被永久记住
        if [ -n "$LABEL" ] || [ -n "$ICON" ]; then
            printf '%s\n%s\n' "$LABEL" "$ICON" > "$cf" 2>/dev/null
        fi
    done
    ;;

debug)
    rm -f "$LABEL_PROBE" 2>/dev/null
    echo "== 身份 =="
    echo "uid: $(id -u 2>/dev/null)"
    echo "PATH: ${PATH:-空}"
    echo "unzip: ${UZ:-未找到}"
    echo "base64: $B64"
    echo "名称探测: $(label_supported)  (1=能取到应用名, 0=本机 dumpsys 无名字字段)"
    echo
    echo "== 关键命令 =="
    for b in pm cmd dumpsys od grep sed input am unzip busybox; do
        p=$(command -v "$b" 2>/dev/null)
        echo "$b: ${p:-缺失}"
    done
    echo
    echo "== 方式1: query-activities --brief =="
    cmd package query-activities --brief -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER 2>&1 | head -3
    echo "数量: $(cmd package query-activities --brief -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER 2>/dev/null | pkgs_from_query | wc -l | tr -d ' ')"
    echo
    echo "== 方式2: query-activities（完整格式） =="
    cmd package query-activities -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER 2>&1 | head -3
    echo
    echo "== 方式3: pm list packages -3 =="
    echo "数量: $(pm list packages -3 2>/dev/null | wc -l | tr -d ' ')"
    pm list packages -3 2>&1 | head -3
    echo
    echo "== 最终 list 前 6 条 =="
    sh "$0" list 2>/dev/null | head -6
    echo
    echo "== 名称/图标样本 =="
    t=$(sh "$0" list 2>/dev/null | head -1)
    echo "样本包: ${t:-（空）}"
    if [ -n "$t" ]; then
        pm dump "$t" 2>/dev/null | grep -m3 -iE 'label' | head -3
        echo "解析名称: [$(get_label "$t")]"
        echo "apk: $(pm path "$t" 2>/dev/null | head -1)"
        echo "图标 base64 长度: $(get_icon "$t" | wc -c | tr -d ' ')"
    fi
    ;;

esac
exit 0
