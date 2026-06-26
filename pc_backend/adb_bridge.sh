#!/data/data/com.termux/files/usr/bin/sh
# ADB Bridge for RssAiPush
# 在 Termux 中运行此脚本，之后可通过 ADB 发送命令
# 用法: 在 Termux 中运行: sh /storage/emulated/0/RssAiPush/adb_bridge.sh

CMD_DIR="/storage/emulated/0/RssAiPush/.adb_cmds"
OUT_DIR="/storage/emulated/0/RssAiPush/.adb_out"
mkdir -p "$CMD_DIR" "$OUT_DIR"

echo "ADB Bridge 已启动，等待命令..."
echo "命令目录: $CMD_DIR"
echo "输出目录: $OUT_DIR"

while true; do
    for cmd_file in "$CMD_DIR"/*.sh; do
        [ -f "$cmd_file" ] || continue
        cmd_id=$(basename "$cmd_file" .sh)
        out_file="$OUT_DIR/${cmd_id}.txt"

        echo "执行命令: $cmd_id"
        cd /storage/emulated/0/RssAiPush
        sh "$cmd_file" > "$out_file" 2>&1
        echo "EXIT_CODE=$?" >> "$out_file"
        rm -f "$cmd_file"
        echo "完成: $cmd_id"
    done
    sleep 1
done
