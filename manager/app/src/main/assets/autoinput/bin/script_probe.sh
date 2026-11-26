#!/system/bin/sh
# Auto Input - 脚本体检
# 只读分析用户配置的 sh 脚本：定位“解压失败 / gzip 报错”这类脚本自身问题
BIN_DIR="${0%/*}"
. "$BIN_DIR/common.sh"

SP=$(get_script_path)

echo "== 脚本体检 =="
if [ -z "$SP" ]; then
    echo "结果  : 还没有配置脚本路径"
    exit 0
fi
echo "路径  : $SP"
if [ ! -f "$SP" ]; then
    echo "结果  : 文件不存在（路径写错或脚本被删了）"
    exit 0
fi

SZ=$(wc -c < "$SP" 2>/dev/null | tr -d ' ')
LN=$(wc -l < "$SP" 2>/dev/null | tr -d ' ')
MD5=$(md5sum "$SP" 2>/dev/null | cut -d' ' -f1)
CR=$(awk '/\r$/{n++} END{print n+0}' "$SP" 2>/dev/null)
HB=$(head -c 8 "$SP" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d '\n')
TL=$(tail -c 8 "$SP" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d '\n')

echo "大小  : ${SZ} 字节 / ${LN} 行"
echo "MD5   : ${MD5:-?}"
echo "首行  : $(head -n 1 "$SP" 2>/dev/null | tr -d '\r')"
echo "首8字节: $(printf '%s' "$HB" | sed 's/^ *//')"
echo "尾8字节: $(printf '%s' "$TL" | sed 's/^ *//')"
if [ "$CR" -gt 0 ] 2>/dev/null; then
    echo "换行符: 有 ${CR} 行是 CRLF（Windows 换行）"
else
    echo "换行符: 纯 LF，正常"
fi

# 解释器是否齐全
command -v bash >/dev/null 2>&1 && echo "bash  : $(command -v bash)" || echo "bash  : 无（脚本里的 bash 语法会失败）"
command -v busybox >/dev/null 2>&1 && echo "busybox: $(command -v busybox)" || echo "busybox: 无"

echo
echo "== 解压 / 压缩相关代码 =="
HIT=$(grep -n -E 'gzip|gunzip|zcat|base64|tar |unzip|解压|PAYLOAD|payload|tail -c|dd if=' "$SP" 2>/dev/null | head -n 16)
if [ -n "$HIT" ]; then
    printf '%s\n' "$HIT" | cut -c1-160
else
    echo "（没找到压缩/解压相关命令）"
fi

echo
echo "== 报错语句上下文 =="
LN2=$(grep -n '解压失败' "$SP" 2>/dev/null | head -n 1 | cut -d: -f1)
if [ -n "$LN2" ]; then
    S=$((LN2 - 10))
    [ "$S" -lt 1 ] && S=1
    awk -v s="$S" -v e="$((LN2 + 2))" \
        'NR>=s && NR<=e {printf "%5d| %s\n", NR, substr($0,1,150)}' "$SP" 2>/dev/null
else
    echo "（脚本里没有“解压失败”这句）"
fi

echo
echo "== 脚本结尾 =="
tail -n 4 "$SP" 2>/dev/null | cut -c1-160

echo
echo "== 依赖的外部文件（若解压的是别的文件，看这里是否给的是绝对路径） =="
grep -n -E '/(data|sdcard|storage|system)/[^ "'"'"']+\.(gz|tgz|tar|zip|7z|bin|so)' "$SP" 2>/dev/null | head -n 8 | cut -c1-160
echo "== 完成 =="
