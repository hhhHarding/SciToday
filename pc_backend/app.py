import logging
import os
import time
import threading
from pathlib import Path

from flask import Flask, jsonify, make_response, request, send_file, send_from_directory

import tasks

tasks.BASE_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(tasks.BASE_DIR / "server.log", encoding="utf-8"),
        logging.StreamHandler(),
    ],
)
logger = logging.getLogger(__name__)

app = Flask(__name__)
APP_ROOT = Path(__file__).resolve().parent
ADMIN_STATIC_DIR = APP_ROOT / "admin_web"

_scheduler = None
_scheduler_thread = None
_running = {"rss": False, "pdf": False, "rss_discovery": False, "rss_publish": False}
_progress = {
    "rss": {"active": False, "current": 0, "total": 0, "message": ""},
    "pdf": {"active": False, "current": 0, "total": 0, "message": ""},
    "rss_discovery": {"active": False, "current": 0, "total": 0, "message": ""},
    "rss_publish": {"active": False, "current": 0, "total": 0, "message": ""},
}


def _provided_token():
    auth = request.headers.get("Authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return (
        request.cookies.get("rssai_admin_token")
        or request.args.get("token")
        or ""
    ).strip()


@app.before_request
def _require_auth():
    token = tasks.get_auth_token()
    if not token:
        return None
    path = request.path or ""
    protected = path.startswith("/api/") or path == "/inbox" or path.startswith("/inbox/")
    if path == "/api/admin/session":
        return None
    if protected and _provided_token() != token:
        return jsonify({"error": "unauthorized"}), 401
    return None


@app.route("/healthz")
def healthz():
    return jsonify({"ok": True, **tasks.runtime_info()})


@app.route("/")
def index():
    return jsonify({
        "ok": True,
        "service": "SciToday backend",
        "health": "/healthz",
        "inbox": "/inbox/",
        "api_status": "/api/status",
        "auth_required": bool(tasks.get_auth_token()),
    })


@app.route("/admin/")
@app.route("/admin/<path:filename>")
def admin_web(filename="index.html"):
    if not filename or filename == "/":
        filename = "index.html"
    return send_from_directory(ADMIN_STATIC_DIR, filename)


@app.route("/api/admin/session", methods=["POST"])
def admin_session():
    data = request.get_json(silent=True) or {}
    supplied = str(data.get("token", "")).strip()
    token = tasks.get_auth_token()
    if token and supplied != token:
        return jsonify({"error": "unauthorized"}), 401
    resp = make_response(jsonify({"ok": True}))
    if token:
        resp.set_cookie(
            "rssai_admin_token",
            token,
            httponly=True,
            samesite="Lax",
            secure=False,
            max_age=7 * 86400,
        )
    return resp


# ── Config API ──────────────────────────────────────────

@app.route("/api/config", methods=["GET"])
def api_config():
    cfg = tasks.load_config()
    return jsonify(tasks.public_config(cfg))


@app.route("/api/config", methods=["POST"])
def api_save_config():
    data = request.get_json(silent=True) or {}
    cfg = tasks.load_config()
    for section in ("ai", "rss", "schedule", "server"):
        if section in data:
            incoming = dict(data[section] or {})
            if section in ("ai", "server"):
                secret_key = "api_key" if section == "ai" else "auth_token"
                secret = str(incoming.get(secret_key, "")).strip()
                if not secret or secret == tasks.MASKED_SECRET or set(secret) == {"*"}:
                    incoming.pop(secret_key, None)
            cfg.setdefault(section, {}).update(incoming)
    tasks.save_config(cfg)
    if "schedule" in data:
        _restart_scheduler()
    return jsonify({"ok": True})


# ── Feed API ────────────────────────────────────────────

@app.route("/api/feeds", methods=["GET"])
def get_feeds():
    cfg = tasks.load_config()
    opml = tasks.get_opml_path(cfg)
    feeds = tasks.parse_opml(opml)
    return jsonify(feeds)


@app.route("/api/feeds", methods=["POST"])
def add_feed():
    data = request.get_json(silent=True) or request.form
    title = data.get("title", "").strip()
    url = data.get("url", "").strip()
    if not title or not url:
        return jsonify({"error": "缺少 title 或 url"}), 400

    cfg = tasks.load_config()
    opml = tasks.get_opml_path(cfg)
    tasks.add_feed_to_opml(opml, title, url)
    return jsonify({"ok": True})


@app.route("/api/feeds/<path:url>", methods=["DELETE"])
def delete_feed(url):
    cfg = tasks.load_config()
    opml = tasks.get_opml_path(cfg)
    tasks.remove_feed_from_opml(opml, url)
    return jsonify({"ok": True})


@app.route("/api/feeds/import", methods=["POST"])
def import_opml():
    if "file" not in request.files:
        return jsonify({"error": "没有上传文件"}), 400
    f = request.files["file"]
    cfg = tasks.load_config()
    opml = tasks.get_opml_path(cfg)
    f.save(opml)
    return jsonify({"ok": True, "count": len(tasks.parse_opml(opml))})


# ── Manual trigger ──────────────────────────────────────

def _run_task(task_type, fn):
    """统一运行 RSS/PDF 任务：管理 _running 与 _progress，供手动路由与调度器共用。
    若任务已在运行则跳过。调度器触发的任务也会更新 _progress，便于前端轮询跟踪。"""
    if _running[task_type]:
        return
    _running[task_type] = True
    _progress[task_type] = {"active": True, "current": 0, "total": 0, "message": "准备中..."}
    tasks.record_event("task", f"{task_type} 任务开始")
    try:
        result = fn(progress_callback=_make_progress_callback(task_type))
        tasks.record_event("task", f"{task_type} 任务完成", details={"result": result})
    except Exception as e:
        logger.error(f"{task_type} 任务失败: {e}")
        tasks.record_event("task", f"{task_type} 任务失败", level="error", details={"error": str(e)})
    finally:
        _running[task_type] = False
        _progress[task_type]["active"] = False


@app.route("/api/run/rss", methods=["POST"])
def run_rss():
    if _running["rss"] or _running["rss_discovery"] or _running["rss_publish"]:
        return jsonify({"error": "RSS 任务正在运行中"}), 409
    threading.Thread(target=_run_task, args=("rss", tasks.run_rss_cycle), daemon=True).start()
    return jsonify({"ok": True, "message": "RSS 任务已启动"})


@app.route("/api/run/pdf", methods=["POST"])
def run_pdf():
    if _running["pdf"]:
        return jsonify({"error": "PDF 任务正在运行中"}), 409
    threading.Thread(target=_run_task, args=("pdf", tasks.run_pdf_watch), daemon=True).start()
    return jsonify({"ok": True, "message": "PDF 监控已启动"})


@app.route("/api/admin/run/rss-discovery", methods=["POST"])
def run_rss_discovery():
    if _running["rss"] or _running["rss_discovery"]:
        return jsonify({"error": "RSS discovery 正在运行中"}), 409
    threading.Thread(target=_run_task, args=("rss_discovery", tasks.run_rss_discovery), daemon=True).start()
    return jsonify({"ok": True, "message": "RSS discovery 已启动"})


@app.route("/api/admin/run/rss-publish", methods=["POST"])
def run_rss_publish():
    if _running["rss"] or _running["rss_publish"]:
        return jsonify({"error": "RSS publish 正在运行中"}), 409
    threading.Thread(target=_run_task, args=("rss_publish", tasks.run_rss_publish), daemon=True).start()
    return jsonify({"ok": True, "message": "RSS publish 已启动"})


@app.route("/api/pdf/upload", methods=["POST"])
def upload_pdf():
    files = request.files.getlist("files")
    if not files and "file" in request.files:
        files = [request.files["file"]]
    if not files:
        return jsonify({"error": "没有上传 PDF 文件"}), 400

    saved = []
    errors = []
    for f in files:
        try:
            saved.append(tasks.save_uploaded_pdf(f))
        except Exception as e:
            errors.append({"filename": getattr(f, "filename", ""), "error": str(e)})
    return jsonify({
        "ok": bool(saved),
        "uploaded": len(saved),
        "paths": saved,
        "errors": errors,
    })


def _make_progress_callback(task_type):
    def callback(current, total, message=""):
        _progress[task_type] = {
            "active": True,
            "current": current,
            "total": total,
            "message": message,
        }
    return callback


@app.route("/api/progress")
def api_progress():
    return jsonify(_progress)


@app.route("/api/status")
def api_status():
    return jsonify(tasks.get_status())


@app.route("/api/app/heartbeat", methods=["POST"])
def api_app_heartbeat():
    payload = request.get_json(silent=True) or {}
    return jsonify({"ok": True, "heartbeat": tasks.record_app_heartbeat(payload)})


@app.route("/api/admin/overview")
def api_admin_overview():
    return jsonify(tasks.get_admin_overview(progress=_progress, running=_running))


@app.route("/api/admin/events")
def api_admin_events():
    n = request.args.get("limit", 100, type=int)
    return jsonify(tasks.get_events(n))


@app.route("/api/admin/feed-health")
def api_admin_feed_health():
    return jsonify(tasks.get_feed_health())


@app.route("/api/admin/rss-queue")
def api_admin_rss_queue():
    status = request.args.get("status") or None
    n = request.args.get("limit", 100, type=int)
    return jsonify(tasks.get_rss_queue(status=status, limit=n))


@app.route("/api/admin/pdf-queue")
def api_admin_pdf_queue():
    n = request.args.get("limit", 100, type=int)
    return jsonify(tasks.get_pdf_queue(limit=n))


@app.route("/api/admin/local-settings", methods=["GET"])
def api_admin_local_settings():
    return jsonify(tasks.get_local_settings())


@app.route("/api/admin/local-settings", methods=["POST"])
def api_admin_save_local_settings():
    data = request.get_json(silent=True) or {}
    return jsonify({"ok": True, "settings": tasks.save_local_settings(data)})


@app.route("/api/admin/tunnel/refresh", methods=["POST"])
def api_admin_tunnel_refresh():
    return jsonify(tasks.request_tunnel_refresh())


@app.route("/api/admin/settings", methods=["GET"])
def api_admin_settings():
    return jsonify(tasks.get_admin_settings())


@app.route("/api/admin/settings", methods=["POST"])
def api_admin_save_settings():
    data = request.get_json(silent=True) or {}
    settings = tasks.save_admin_settings(data)
    if "schedule" in data:
        _restart_scheduler()
    return jsonify({"ok": True, "settings": settings})


@app.route("/api/digests")
def api_digests():
    n = request.args.get("limit", 20, type=int)
    source = request.args.get("source") or None
    return jsonify(tasks.get_recent_digests(n, source=source))


@app.route("/api/digests/updates")
def api_digest_updates():
    after = request.args.get("after", 0, type=int)
    n = request.args.get("limit", 50, type=int)
    source = request.args.get("source") or None
    return jsonify(tasks.get_digest_updates(after=after, limit=n, source=source))


@app.route("/api/digests/<filename>", methods=["DELETE"])
def api_delete_digest(filename):
    try:
        tasks.delete_digest(filename)
        return jsonify({"ok": True})
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except FileNotFoundError:
        return jsonify({"error": "摘要不存在"}), 404


@app.route("/api/digests", methods=["DELETE"])
def api_clear_digests():
    source = request.args.get("source") or None
    count = tasks.clear_digests(source)
    return jsonify({"ok": True, "count": count})


@app.route("/api/reset", methods=["POST"])
def api_reset():
    # 删除所有摘要
    count = tasks.clear_digests()
    # 重置收录到最近一周
    tasks.reset_seen_to_recent_week()
    return jsonify({"ok": True, "count": count})


@app.route("/api/chat", methods=["POST"])
def api_chat():
    data = request.get_json(silent=True) or {}
    filename = data.get("filename", "")
    message = data.get("message", "")
    history = data.get("history", []) or []
    if not filename or not message:
        return jsonify({"error": "缺少 filename 或 message"}), 400
    reply = tasks.ai_chat(filename, message, history)
    return jsonify({"reply": reply})


@app.route("/api/pdf")
def api_pdf():
    filename = request.args.get("filename", "")
    if not filename or "/" in filename or "\\" in filename or ".." in filename:
        return jsonify({"error": "非法文件名"}), 400
    pdf_path = tasks.resolve_pdf_path(filename)
    if not pdf_path:
        return jsonify({"error": "未找到对应的 PDF 源文件"}), 404
    return send_file(pdf_path, mimetype="application/pdf", as_attachment=False)


@app.route("/api/logs")
def api_logs():
    n = request.args.get("lines", 200, type=int)
    return jsonify(tasks.get_logs(n))


# ── Inbox 静态文件 ──────────────────────────────────────
# 摘要 HTML 由 tasks 写入 INBOX_DIR。早期版本依赖一个独立的静态服务器
# （http.server，端口 8765）来提供这些页面；现已并入 Flask，统一在 5000 端口
# 通过 /inbox/<filename> 访问，App 端也只需配置一个 host:port。
# send_from_directory 自带路径穿越防护，会拒绝 .. 等越界文件名。
@app.route("/inbox/")
@app.route("/inbox/<path:filename>")
def serve_inbox(filename="index.html"):
    return send_from_directory(tasks.INBOX_DIR, filename)


# ── Scheduler ───────────────────────────────────────────

def _start_scheduler():
    global _scheduler, _scheduler_thread
    try:
        from apscheduler.schedulers.background import BackgroundScheduler
        from apscheduler.triggers.interval import IntervalTrigger
    except ImportError:
        logger.warning("apscheduler 未安装，定时任务不可用。请运行: pip install apscheduler")
        return

    cfg = tasks.load_config()
    schedule = cfg.get("schedule", {})
    if not schedule.get("enabled", True):
        logger.info("定时任务已禁用")
        return

    _scheduler = BackgroundScheduler()
    rss_publish_min = schedule.get("rss_interval_minutes", 30)
    rss_discovery_min = schedule.get("rss_discovery_interval_minutes", rss_publish_min)
    pdf_min = schedule.get("pdf_interval_minutes", 5)

    _scheduler.add_job(
        _safe_rss_discovery, IntervalTrigger(minutes=rss_discovery_min),
        id="rss_discovery", replace_existing=True, max_instances=1
    )
    _scheduler.add_job(
        _safe_rss_publish, IntervalTrigger(minutes=rss_publish_min),
        id="rss_publish", replace_existing=True, max_instances=1
    )
    _scheduler.add_job(
        _safe_pdf, IntervalTrigger(minutes=pdf_min),
        id="pdf_watch", replace_existing=True, max_instances=1
    )
    _scheduler.start()
    logger.info(
        f"定时任务启动: RSS discovery 每 {rss_discovery_min} 分钟, "
        f"RSS publish 每 {rss_publish_min} 分钟, PDF 每 {pdf_min} 分钟"
    )


def _restart_scheduler():
    global _scheduler
    if _scheduler:
        _scheduler.shutdown(wait=False)
        _scheduler = None
    _start_scheduler()


def _safe_rss_discovery():
    _run_task("rss_discovery", tasks.run_rss_discovery)


def _safe_rss_publish():
    _run_task("rss_publish", tasks.run_rss_publish)


def _safe_pdf():
    _run_task("pdf", tasks.run_pdf_watch)


# ── Main ────────────────────────────────────────────────

if __name__ == "__main__":
    tasks.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    _start_scheduler()

    cfg = tasks.load_config()
    server = cfg.get("server", {})
    host = os.environ.get("RSSAI_SERVER_HOST", server.get("host", "0.0.0.0"))
    port = int(os.environ.get("RSSAI_SERVER_PORT", server.get("port", 5000)))

    logger.info(f"启动服务器: {host}:{port}")
    app.run(host=host, port=port, debug=False)
