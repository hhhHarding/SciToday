#!/data/data/com.termux/files/usr/bin/sh
cd /storage/emulated/0/SciToday 2>/dev/null || cd /storage/emulated/0/RssAiPush || exit 1

if [ -f ai.env ]; then
    . ./ai.env
fi

# 用法：
#   start_server.sh            已在运行则跳过（保持原行为）
#   start_server.sh restart    先停掉旧进程再启动（改了 app.py / tasks.py 后用这个）
if [ "$1" = "restart" ]; then
    if pgrep -f "python app.py" >/dev/null 2>&1; then
        echo "停止旧进程..."
        pkill -f "python app.py"
        # 等待端口释放，避免新进程 bind 失败
        sleep 2
    fi
elif pgrep -f "python app.py" >/dev/null 2>&1; then
    echo "Server already running (用 'start_server.sh restart' 重启以加载新代码)"
    exit 0
fi

nohup python app.py >> server.log 2>&1 &
echo "Server started on port 5000"
