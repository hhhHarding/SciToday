import hashlib
import html as html_mod
import json
import logging
import os
import re
import socket
import sqlite3
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree as ET

import feedparser
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import push

logger = logging.getLogger(__name__)

TERMUX_BASE_DIR = Path("/storage/emulated/0/RssAiPush")
MASKED_SECRET = "********"


def _env_path(name, default):
    value = os.environ.get(name)
    return Path(value).expanduser() if value else Path(default)


def _env_path_list(name, defaults):
    raw = os.environ.get(name)
    if not raw:
        return [Path(p) for p in defaults]
    return [Path(p.strip()).expanduser() for p in raw.split(os.pathsep) if p.strip()]


BASE_DIR = _env_path("RSSAI_BASE_DIR", TERMUX_BASE_DIR)
INSTALL_DIR = Path(os.environ.get("RSSAI_INSTALL_DIR") or Path(__file__).resolve().parent)
CONFIG_PATH = _env_path("RSSAI_CONFIG_PATH", BASE_DIR / "config.json")
INBOX_DIR = _env_path("RSSAI_INBOX_DIR", BASE_DIR / "inbox")
INDEX_HTML = INBOX_DIR / "index.html"
RSS_DB = _env_path("RSSAI_RSS_DB", BASE_DIR / "rss_ai.db")
PENDING_DB = _env_path("RSSAI_PENDING_DB", BASE_DIR / "pending_papers.db")
PDF_DB = _env_path("RSSAI_PDF_DB", BASE_DIR / "pdf_seen.db")
DIGEST_DB = _env_path("RSSAI_DIGEST_DB", BASE_DIR / "digest_messages.db")
ADMIN_DB = _env_path("RSSAI_ADMIN_DB", BASE_DIR / "admin_state.db")
UPLOADED_PDF_DIR = _env_path("RSSAI_UPLOADED_PDF_DIR", BASE_DIR / "uploaded_pdfs")
QUICK_TUNNEL_STATE = _env_path("RSSAI_TUNNEL_STATE_PATH", BASE_DIR / "quick_tunnel.json")
TRAY_COMMAND_PATH = _env_path("RSSAI_TRAY_COMMAND_PATH", BASE_DIR / "tray_command.json")
TRAY_CONFIG_PATH = _env_path("RSSAI_TRAY_CONFIG_PATH", INSTALL_DIR / "tray_config.env")
DOWNLOAD_DIRS = _env_path_list("RSSAI_DOWNLOAD_DIRS", [
    Path("/storage/emulated/0/Download"),
    Path("/storage/emulated/0/Download/dlmanager"),
])
if str(UPLOADED_PDF_DIR) not in {str(p) for p in DOWNLOAD_DIRS}:
    DOWNLOAD_DIRS.append(UPLOADED_PDF_DIR)

RSS_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 16; Mobile) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0 Mobile Safari/537.36"
    ),
    "Accept": "application/rss+xml, application/xml, text/xml, text/html, */*",
    "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7",
    "Connection": "close",
}


# ── Config ──────────────────────────────────────────────

def load_config():
    if CONFIG_PATH.exists():
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))
    return {}


def save_config(config):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")


def public_config(config=None):
    """Return config safe for the mobile app to display."""
    cfg = json.loads(json.dumps(config if config is not None else load_config(), ensure_ascii=False))
    ai = cfg.get("ai")
    if isinstance(ai, dict) and ai.get("api_key"):
        ai["api_key"] = MASKED_SECRET
    server = cfg.get("server")
    if isinstance(server, dict) and server.get("auth_token"):
        server["auth_token"] = MASKED_SECRET
    return cfg


def get_auth_token():
    return (os.environ.get("RSSAI_AUTH_TOKEN") or _cfg("server.auth_token", "") or "").strip()


def runtime_info():
    return {
        "install_dir": str(INSTALL_DIR),
        "base_dir": str(BASE_DIR),
        "config_path": str(CONFIG_PATH),
        "inbox_dir": str(INBOX_DIR),
        "admin_db": str(ADMIN_DB),
        "uploaded_pdf_dir": str(UPLOADED_PDF_DIR),
        "quick_tunnel_state": str(QUICK_TUNNEL_STATE),
        "tray_command_path": str(TRAY_COMMAND_PATH),
        "tray_config_path": str(TRAY_CONFIG_PATH),
        "download_dirs": [str(p) for p in DOWNLOAD_DIRS],
        "auth_required": bool(get_auth_token()),
    }


def _read_tray_env(path=None):
    path = Path(path or TRAY_CONFIG_PATH)
    result = {}
    if not path.exists():
        return result
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        key, value = text.split("=", 1)
        result[key.strip()] = value.strip().strip('"')
    return result


def _write_tray_env(values, path=None):
    path = Path(path or TRAY_CONFIG_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = _read_tray_env(path)
    existing.update({k: str(v) for k, v in values.items() if v is not None})
    ordered = [
        "InstallDir", "DataDir", "HostAddress", "Port", "AuthToken",
        "DownloadDirs", "TunnelToken", "TunnelUrl", "TunnelMode",
    ]
    keys = ordered + sorted(k for k in existing if k not in ordered)
    content = "\n".join(f"{k}={existing.get(k, '')}" for k in keys if k in existing) + "\n"
    path.write_text(content, encoding="utf-8")
    return existing


def _startup_run_value():
    return f'"{INSTALL_DIR / "SciTodayTray.exe"}"'


def _read_startup_value():
    if os.name != "nt":
        return ""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
            for name in ("SciTodayBackend", "RssAiPushBackend"):
                try:
                    value, _ = winreg.QueryValueEx(key, name)
                    if value:
                        return value
                except FileNotFoundError:
                    continue
            return ""
    except Exception:
        return ""


def _set_startup_enabled(enabled):
    if os.name != "nt":
        return False
    import winreg
    run_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, run_path, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, "SciTodayBackend", 0, winreg.REG_SZ, _startup_run_value())
        else:
            for name in ("SciTodayBackend", "RssAiPushBackend"):
                try:
                    winreg.DeleteValue(key, name)
                except FileNotFoundError:
                    pass
    return True


def get_local_settings():
    env = _read_tray_env()
    startup_value = _read_startup_value()
    install_dir = env.get("InstallDir") or str(INSTALL_DIR)
    data_dir = env.get("DataDir") or str(BASE_DIR)
    download_dirs = [p for p in (env.get("DownloadDirs") or os.pathsep.join(str(p) for p in DOWNLOAD_DIRS)).split(";") if p]
    return {
        "install_dir": install_dir,
        "tray_config_path": str(TRAY_CONFIG_PATH),
        "tray_config_exists": TRAY_CONFIG_PATH.exists(),
        "tray_command_path": str(TRAY_COMMAND_PATH),
        "startup": {
            "enabled": bool(startup_value),
            "run_name": "SciTodayBackend",
            "value": startup_value,
            "expected_value": _startup_run_value(),
        },
        "tray": {
            "data_dir": data_dir,
            "host": env.get("HostAddress") or os.environ.get("RSSAI_SERVER_HOST") or "127.0.0.1",
            "port": int(env.get("Port") or os.environ.get("RSSAI_SERVER_PORT") or 5200),
            "download_dirs": download_dirs,
            "download_dirs_raw": env.get("DownloadDirs") or ";".join(download_dirs),
            "tunnel_mode": env.get("TunnelMode") or "Quick",
            "tunnel_url": env.get("TunnelUrl") or "",
            "auth_token_configured": bool(env.get("AuthToken")),
            "tunnel_token_configured": bool(env.get("TunnelToken")),
        },
    }


def save_local_settings(data):
    incoming = data or {}
    local = incoming.get("local") or incoming
    values = {
        "InstallDir": str(INSTALL_DIR),
        "DataDir": str(local.get("data_dir") or BASE_DIR),
        "HostAddress": str(local.get("host") or "127.0.0.1"),
        "Port": int(local.get("port") or 5200),
        "DownloadDirs": ";".join(local.get("download_dirs") or []),
        "TunnelMode": str(local.get("tunnel_mode") or "Quick"),
        "TunnelUrl": str(local.get("tunnel_url") or ""),
    }
    _write_tray_env(values)
    if "startup_enabled" in local:
        _set_startup_enabled(bool(local.get("startup_enabled")))
    record_event("settings", "本地后台设置已保存")
    return get_local_settings()


def request_tunnel_refresh():
    request_id = str(int(time.time() * 1000))
    command = {
        "command": "refresh_tunnel",
        "request_id": request_id,
        "requested_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "previous_url": get_quick_tunnel_state().get("url") or "",
    }
    TRAY_COMMAND_PATH.parent.mkdir(parents=True, exist_ok=True)
    TRAY_COMMAND_PATH.write_text(json.dumps(command, ensure_ascii=False, indent=2), encoding="utf-8")
    record_event("tunnel", "请求刷新 Quick Tunnel URL", details={"request_id": request_id})
    return {"ok": True, "command": command, "path": str(TRAY_COMMAND_PATH), "state": get_quick_tunnel_state()}


def get_quick_tunnel_state():
    state = {
        "mode": "quick",
        "url": "",
        "localUrl": "",
        "status": "not_started",
        "message": "",
        "updatedAt": "",
        "path": str(QUICK_TUNNEL_STATE),
        "exists": QUICK_TUNNEL_STATE.exists(),
    }
    if QUICK_TUNNEL_STATE.exists():
        try:
            payload = json.loads(QUICK_TUNNEL_STATE.read_text(encoding="utf-8-sig"))
            if isinstance(payload, dict):
                state.update(payload)
        except Exception as e:
            state["status"] = "error"
            state["message"] = f"读取 Quick Tunnel 状态失败: {e}"
        try:
            state["age_seconds"] = max(0, int(time.time() - QUICK_TUNNEL_STATE.stat().st_mtime))
        except Exception:
            state["age_seconds"] = None
    else:
        state["age_seconds"] = None
    state["current_url"] = state.get("url") or ""
    return state


def _cfg(key, default=""):
    c = load_config()
    keys = key.split(".")
    v = c
    for k in keys:
        if isinstance(v, dict):
            v = v.get(k)
        else:
            return default
    return v if v is not None else default


# 摘要正文开头通常是结构化元信息（中文题目/关键词/来源等），这些已在卡片单独显示，
# preview 取正文时跳过它们，避免与中文题目重复。
_META_LINE_RE = re.compile(
    r"^\s*(中文题目|题目中文翻译|中文标题|英文题目|中文关键词|关键词|来源|来源/RSS|"
    r"卷期来源|发表时间|DOI|一作|第一作者|通讯作者|通讯|作者列表|文章类型|原文链接)\s*[：:]",
)


def _preview_from_digest(text, limit=150):
    """从摘要正文生成 preview：跳过开头的结构化元信息行，取真正的正文摘要。"""
    if not text:
        return ""
    lines = text.splitlines()
    body = [ln for ln in lines if ln.strip() and not _META_LINE_RE.match(ln)]
    joined = " ".join(body) if body else text
    return re.sub(r"\s+", " ", joined).strip()[:limit]


def get_opml_path(config=None):
    cfg = config if config is not None else load_config()
    configured = cfg.get("rss", {}).get("opml_path", str(BASE_DIR / "feedly.opml"))
    path = Path(configured).expanduser()
    if path.exists():
        return str(path)
    fallback = BASE_DIR / "feedly.opml"
    if fallback.exists():
        return str(fallback)
    return str(path)


# ── HTTP ────────────────────────────────────────────────

def _make_session():
    s = requests.Session()
    retry = Retry(total=2, connect=2, read=2, status=2, backoff_factor=1.2,
                  status_forcelist=(429, 500, 502, 503, 504),
                  allowed_methods=frozenset(["GET", "POST", "HEAD"]))
    adapter = HTTPAdapter(max_retries=retry, pool_connections=8, pool_maxsize=8)
    s.mount("https://", adapter)
    s.mount("http://", adapter)
    return s


SESSION = _make_session()


def http_get(url, timeout=35, max_attempts=3):
    url = _normalize_feed_url(url)
    last = None
    for attempt in range(1, max_attempts + 1):
        try:
            r = SESSION.get(url, headers=RSS_HEADERS, timeout=timeout, allow_redirects=True)
            r.raise_for_status()
            return r
        except Exception as e:
            last = e
            logger.warning(f"抓取失败 ({attempt}/{max_attempts}): {url} | {e}")
            time.sleep(2 * attempt)
    raise last


# ── Text utilities ──────────────────────────────────────

def _clean(text, n=500):
    text = text or ""
    text = re.sub(r"<[^>]+>", " ", text)
    text = html_mod.unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:n]


def _clean_full(text):
    text = text or ""
    text = re.sub(r"<[^>]+>", " ", text)
    text = html_mod.unescape(text)
    return re.sub(r"\s+", " ", text).strip()


def _normalize_feed_url(url):
    url = (url or "").strip()
    if url.startswith("http://rss.sciencedirect.com/"):
        url = url.replace("http://rss.sciencedirect.com/", "https://rss.sciencedirect.com/", 1)
    return url


def _find_doi(*texts):
    text = " ".join(t or "" for t in texts)
    m = re.search(r"10\.\d{4,9}/[-._;()/:A-Z0-9]+", text, flags=re.I)
    return m.group(0).rstrip(".,;:)>]}\"'").lower() if m else ""


def _has_cjk(text):
    return bool(re.search(r"[\u4e00-\u9fff]", text or ""))


def _extract_line_value(text, labels):
    text = text or ""
    for label in labels:
        m = re.search(rf"^{re.escape(label)}\s*[:：]\s*(.+)$", text, flags=re.M)
        if m:
            v = m.group(1).strip()
            v = re.sub(r"^[【\[]|[】\]]$", "", v).strip()
            if v and v not in ("未提供", "无", "无。"):
                return v
    return ""


def _sanitize_filename(name):
    name = name or "untitled"
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    name = name.replace(' ', '_')
    name = re.sub(r'_{2,}', '_', name).strip('_.')
    return (name[:80] or "untitled")


def save_uploaded_pdf(file_storage):
    original = getattr(file_storage, "filename", "") or "uploaded.pdf"
    if not original.lower().endswith(".pdf"):
        raise ValueError("只支持 PDF 文件")
    safe_stem = _sanitize_filename(Path(original).stem)
    filename = f"{safe_stem}.pdf"
    UPLOADED_PDF_DIR.mkdir(parents=True, exist_ok=True)
    temp = UPLOADED_PDF_DIR / f".{safe_stem}_{time.time_ns()}.uploading"
    try:
        file_storage.save(temp)
        if temp.stat().st_size < 20_000:
            raise ValueError("PDF 文件过小或上传不完整")

        uploaded_hash = _file_hash(temp)
        for existing in UPLOADED_PDF_DIR.glob("*.pdf"):
            try:
                if _file_hash(existing) == uploaded_hash:
                    temp.unlink(missing_ok=True)
                    return str(existing)
            except Exception:
                continue

        dest = UPLOADED_PDF_DIR / filename
        if dest.exists():
            dest = UPLOADED_PDF_DIR / f"{safe_stem}_{int(time.time())}.pdf"
        temp.replace(dest)
        return str(dest)
    except Exception:
        try:
            temp.unlink(missing_ok=True)
        except Exception:
            pass
        raise


# ── OPML ────────────────────────────────────────────────

def parse_opml(path):
    root = ET.parse(path).getroot()
    feeds, seen = [], set()
    for outline in root.iter("outline"):
        url = outline.attrib.get("xmlUrl") or outline.attrib.get("xmlurl")
        if not url:
            continue
        url = _normalize_feed_url(url)
        if url in seen:
            continue
        title = outline.attrib.get("title") or outline.attrib.get("text") or url
        feeds.append({"title": title, "url": url})
        seen.add(url)
    return feeds


def add_feed_to_opml(path, title, url):
    if not os.path.exists(path):
        root = ET.Element("opml", version="1.0")
        head = ET.SubElement(root, "head")
        ET.SubElement(head, "title").text = "SciToday"
        body = ET.SubElement(root, "body")
        ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)

    tree = ET.parse(path)
    body = tree.find("body")
    outline = ET.SubElement(body, "outline", type="rss", text=title, title=title, xmlUrl=url, htmlUrl="")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def remove_feed_from_opml(path, url):
    tree = ET.parse(path)
    root = tree.getroot()
    for outline in list(root.iter("outline")):
        feed_url = outline.attrib.get("xmlUrl") or outline.attrib.get("xmlurl") or ""
        if _normalize_feed_url(feed_url) == _normalize_feed_url(url):
            parent_map = {c: p for p in root.iter() for c in p}
            parent = parent_map.get(outline)
            if parent is not None:
                parent.remove(outline)
                break
    tree.write(path, encoding="utf-8", xml_declaration=True)


# ── Database ────────────────────────────────────────────

def _db_open(path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    con = sqlite3.connect(path)
    con.execute("""CREATE TABLE IF NOT EXISTS seen(
        id TEXT PRIMARY KEY, title TEXT, link TEXT, feed TEXT, ts INTEGER)""")
    con.commit()
    return con


def _uid(feed_url, entry):
    key = (getattr(entry, "id", "") or getattr(entry, "guid", "") or
           getattr(entry, "link", "") or getattr(entry, "title", ""))
    return hashlib.sha1((feed_url + "|" + str(key)).encode("utf-8")).hexdigest()


def _pending_db():
    PENDING_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(PENDING_DB))
    con.execute("""CREATE TABLE IF NOT EXISTS pending_papers(
        id TEXT PRIMARY KEY, title TEXT, doi TEXT, link TEXT, feed TEXT,
        first_author TEXT, created_ts INTEGER, processed INTEGER DEFAULT 0)""")
    con.commit()
    return con


def _pdf_db():
    PDF_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(PDF_DB))
    con.execute("""CREATE TABLE IF NOT EXISTS pdf_seen(
        file_hash TEXT PRIMARY KEY, path TEXT, matched_paper_id TEXT, status TEXT, ts INTEGER)""")
    con.commit()
    return con


def _digest_db():
    DIGEST_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(DIGEST_DB))
    con.execute("""CREATE TABLE IF NOT EXISTS digests(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        filename TEXT UNIQUE NOT NULL,
        timestamp TEXT,
        title TEXT,
        cn_title TEXT,
        keywords TEXT,
        journal TEXT,
        source TEXT DEFAULT 'rss',
        preview TEXT,
        created_ts INTEGER NOT NULL
    )""")
    con.commit()
    return con


def _admin_db():
    ADMIN_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(ADMIN_DB))
    con.execute("""CREATE TABLE IF NOT EXISTS events(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT NOT NULL,
        level TEXT NOT NULL,
        message TEXT NOT NULL,
        details TEXT,
        ts INTEGER NOT NULL
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS app_heartbeat(
        id INTEGER PRIMARY KEY CHECK (id = 1),
        payload TEXT NOT NULL,
        ts INTEGER NOT NULL
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS feed_health(
        feed_url TEXT PRIMARY KEY,
        title TEXT,
        status TEXT,
        last_ok_ts INTEGER,
        last_error_ts INTEGER,
        error TEXT,
        last_count INTEGER DEFAULT 0,
        duration_ms INTEGER DEFAULT 0
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS rss_queue(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_key TEXT UNIQUE NOT NULL,
        item_json TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        created_ts INTEGER NOT NULL,
        published_ts INTEGER,
        error TEXT
    )""")
    con.commit()
    return con


def _json_dumps(value):
    return json.dumps(value or {}, ensure_ascii=False, separators=(",", ":"))


def record_event(event_type, message, level="info", details=None):
    try:
        con = _admin_db()
        con.execute(
            "INSERT INTO events(type, level, message, details, ts) VALUES(?,?,?,?,?)",
            (event_type, level, message, _json_dumps(details), int(time.time())),
        )
        con.execute(
            "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 1000)"
        )
        con.commit()
        con.close()
    except Exception as e:
        logger.debug(f"事件记录失败: {e}")


def get_events(limit=100):
    limit = max(1, min(int(limit or 100), 500))
    con = _admin_db()
    rows = con.execute("""SELECT id, type, level, message, details, ts
        FROM events ORDER BY id DESC LIMIT ?""", (limit,)).fetchall()
    con.close()
    result = []
    for r in rows:
        try:
            details = json.loads(r[4] or "{}")
        except Exception:
            details = {}
        result.append({
            "id": r[0],
            "type": r[1],
            "level": r[2],
            "message": r[3],
            "details": details,
            "ts": r[5],
            "time": datetime.fromtimestamp(r[5]).strftime("%Y-%m-%d %H:%M:%S"),
        })
    return result


def record_app_heartbeat(payload):
    clean = dict(payload or {})
    clean["server_seen_ts"] = int(time.time())
    con = _admin_db()
    con.execute("""INSERT INTO app_heartbeat(id, payload, ts) VALUES(1, ?, ?)
        ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, ts=excluded.ts""",
                (_json_dumps(clean), clean["server_seen_ts"]))
    con.commit()
    con.close()
    record_event("app_heartbeat", "App 心跳已更新", details={
        "backend_mode": clean.get("backendMode"),
        "base_url": clean.get("baseUrl"),
        "last_error": clean.get("lastError"),
    })
    return clean


def get_app_heartbeat():
    con = _admin_db()
    row = con.execute("SELECT payload, ts FROM app_heartbeat WHERE id=1").fetchone()
    con.close()
    if not row:
        return {"online": False, "stale": True, "payload": None, "last_seen_ts": 0, "last_seen": ""}
    try:
        payload = json.loads(row[0] or "{}")
    except Exception:
        payload = {}
    age = max(0, int(time.time()) - int(row[1] or 0))
    return {
        "online": age <= 120,
        "stale": age > 120,
        "age_seconds": age,
        "payload": payload,
        "last_seen_ts": row[1],
        "last_seen": datetime.fromtimestamp(row[1]).strftime("%Y-%m-%d %H:%M:%S"),
    }


def record_feed_health(feed, ok, count=0, error="", duration_ms=0):
    try:
        now = int(time.time())
        con = _admin_db()
        con.execute("""INSERT INTO feed_health
            (feed_url, title, status, last_ok_ts, last_error_ts, error, last_count, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(feed_url) DO UPDATE SET
                title=excluded.title,
                status=excluded.status,
                last_ok_ts=CASE WHEN excluded.status='ok' THEN excluded.last_ok_ts ELSE feed_health.last_ok_ts END,
                last_error_ts=CASE WHEN excluded.status='error' THEN excluded.last_error_ts ELSE feed_health.last_error_ts END,
                error=excluded.error,
                last_count=excluded.last_count,
                duration_ms=excluded.duration_ms""",
                    (
                        feed.get("url", ""),
                        feed.get("title", ""),
                        "ok" if ok else "error",
                        now if ok else None,
                        now if not ok else None,
                        error or "",
                        int(count or 0),
                        int(duration_ms or 0),
                    ))
        con.commit()
        con.close()
    except Exception as e:
        logger.debug(f"feed health 记录失败: {e}")


def get_feed_health():
    con = _admin_db()
    rows = con.execute("""SELECT title, feed_url, status, last_ok_ts, last_error_ts,
        error, last_count, duration_ms FROM feed_health ORDER BY title COLLATE NOCASE""").fetchall()
    con.close()
    result = []
    for r in rows:
        last_ok = r[3] or 0
        last_error = r[4] or 0
        result.append({
            "title": r[0] or "",
            "url": r[1] or "",
            "status": r[2] or "unknown",
            "last_ok_ts": last_ok,
            "last_ok": datetime.fromtimestamp(last_ok).strftime("%Y-%m-%d %H:%M:%S") if last_ok else "",
            "last_error_ts": last_error,
            "last_error": datetime.fromtimestamp(last_error).strftime("%Y-%m-%d %H:%M:%S") if last_error else "",
            "error": r[5] or "",
            "last_count": r[6] or 0,
            "duration_ms": r[7] or 0,
        })
    return result


def _timestamp_epoch(timestamp, fallback=None):
    try:
        return int(datetime.strptime(timestamp, "%Y%m%d_%H%M%S").timestamp())
    except Exception:
        return int(fallback if fallback is not None else time.time())


def _digest_from_file(path):
    name = path.stem
    parts = name.split("_", 2)
    if len(parts) >= 3:
        ts = f"{parts[0]}_{parts[1]}"
        title = parts[2].replace("_", " ")
    else:
        ts = ""
        title = name

    cn_title = ""
    keywords = ""
    journal = ""
    src = "rss"
    preview = ""
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
        src_match = re.search(r'<meta name="digest-source" content="([^"]*)">', content)
        pdf_file_match = re.search(r'<meta name="pdf-file" content="([^"]+)">', content)
        if src_match and src_match.group(1).strip():
            src = src_match.group(1).strip()
        elif pdf_file_match:
            src = "pdf"
        cn_match = re.search(r"中文题目[：:]\s*(.+?)(?:\n|<br|$)", content)
        if cn_match:
            cn_title = cn_match.group(1).strip()[:120]
        kw_match = re.search(r"中文关键词[：:]\s*(.+?)(?:\n|<br|$)", content)
        if kw_match:
            keywords = kw_match.group(1).strip()[:80]
        j_match = re.search(r"来源[：:]\s*(.+?)(?:\n|<br|$)", content)
        if j_match:
            raw_j = j_match.group(1).strip()
            raw_j = re.sub(r"^ScienceDirect Publication:\s*", "", raw_j)
            raw_j = re.sub(r"^Wiley:\s*", "", raw_j)
            raw_j = re.sub(r":\s*Table of Contents$", "", raw_j)
            raw_j = re.sub(r"\s+", " ", raw_j).strip()
            journal = raw_j[:60]
        c_match = re.search(r'<div class="content">(.*?)</div>', content, re.S)
        if c_match:
            preview = _preview_from_digest(html_mod.unescape(c_match.group(1)))
    except Exception:
        pass

    try:
        mtime = path.stat().st_mtime
    except Exception:
        mtime = time.time()
    return {
        "filename": path.name,
        "timestamp": ts,
        "title": title,
        "cn_title": cn_title,
        "keywords": keywords,
        "journal": journal,
        "source": src,
        "preview": preview,
        "created_ts": _timestamp_epoch(ts, fallback=mtime),
    }


def _upsert_digest(con, digest):
    con.execute("""INSERT INTO digests
        (filename, timestamp, title, cn_title, keywords, journal, source, preview, created_ts)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(filename) DO UPDATE SET
            timestamp=excluded.timestamp,
            title=excluded.title,
            cn_title=excluded.cn_title,
            keywords=excluded.keywords,
            journal=excluded.journal,
            source=excluded.source,
            preview=excluded.preview,
            created_ts=excluded.created_ts""",
                (digest["filename"], digest["timestamp"], digest["title"],
                 digest.get("cn_title", ""), digest.get("keywords", ""),
                 digest.get("journal", ""), digest.get("source", "rss"),
                 digest.get("preview", ""), digest["created_ts"]))


def _sync_digest_index():
    con = _digest_db()
    actual = set()
    if INBOX_DIR.exists():
        for f in INBOX_DIR.glob("*.html"):
            if f.name == "index.html":
                continue
            actual.add(f.name)
            _upsert_digest(con, _digest_from_file(f))

    rows = con.execute("SELECT filename FROM digests").fetchall()
    for (filename,) in rows:
        if filename not in actual:
            con.execute("DELETE FROM digests WHERE filename=?", (filename,))
    con.commit()
    con.close()


def record_digest(filename, timestamp, title, content, source="rss", cn_title="", keywords=""):
    try:
        path = INBOX_DIR / filename
        digest = _digest_from_file(path)
        digest.update({
            "timestamp": timestamp or digest["timestamp"],
            "title": title or digest["title"],
            "cn_title": cn_title or digest.get("cn_title", ""),
            "keywords": keywords or digest.get("keywords", ""),
            "source": source or digest.get("source", "rss"),
            "preview": _preview_from_digest(content),
            "created_ts": _timestamp_epoch(timestamp or digest["timestamp"], fallback=time.time()),
        })
        con = _digest_db()
        _upsert_digest(con, digest)
        con.commit()
        con.close()
    except Exception as e:
        logger.warning(f"摘要索引写入失败: {filename} | {e}")


# ── RSS helpers ─────────────────────────────────────────

def _get_authors(entry):
    authors = []
    if getattr(entry, "authors", None):
        for a in entry.authors:
            name = a.get("name", "") if isinstance(a, dict) else str(a)
            if name:
                authors.append(_clean(name, 120))
    if not authors and getattr(entry, "author", None):
        authors = [x.strip() for x in re.split(r",\s*", _clean(entry.author, 600)) if x.strip()]
    if not authors:
        summary = getattr(entry, "summary", "") or getattr(entry, "description", "")
        m = re.search(r"Author\(s\):\s*(.+)$", _clean_full(summary), flags=re.I)
        if m:
            authors = [x.strip() for x in re.split(r",\s*", m.group(1).strip()) if x.strip()]
    return authors


def _extract_pub_info(summary_raw):
    text = _clean_full(summary_raw)
    pub_date = source = ""
    m = re.search(r"Publication date:\s*(.*?)(?:Source:|Author\(s\):|$)", text, flags=re.I)
    if m:
        pub_date = _clean(m.group(1), 120)
    m = re.search(r"Source:\s*(.*?)(?:Author\(s\):|$)", text, flags=re.I)
    if m:
        source = _clean(m.group(1), 180)
    return pub_date, source


def _classify_type(title, summary="", feed=""):
    text = f"{title} {summary} {feed}".lower()
    for pattern, label in [
        (r"\breply\b|\bauthor reply\b|\breply to\b|\bresponse to\b", "Reply/Response"),
        (r"\bcomment on\b|\bcomment\b", "Comment"),
        (r"\bcommentary\b|\bperspective\b|\bviewpoint\b", "Commentary/Perspective"),
        (r"\bcorrespondence\b|\bletter\b", "Correspondence/Letter"),
        (r"\berratum\b|\bcorrigendum\b|\bcorrection\b", "Correction/Erratum"),
        (r"\beditorial\b", "Editorial"),
        (r"\breview\b", "Review"),
    ]:
        if re.search(pattern, text):
            return label
    return "Research Article"


# ── AI ──────────────────────────────────────────────────

def _ai_call(prompt, system_prompt=None, temperature=0.1, timeout=120):
    api_key = _cfg("ai.api_key")
    base_url = _cfg("ai.base_url", "https://api.deepseek.com").rstrip("/")
    model = _cfg("ai.model", "deepseek-chat")

    if not api_key:
        raise RuntimeError("未配置 AI API Key")

    if not base_url.endswith("/v1"):
        base_url += "/v1"

    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})

    payload = {"model": model, "messages": messages, "temperature": temperature}
    r = SESSION.post(
        f"{base_url}/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        timeout=timeout,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


def _format_raw(item):
    lines = ["【论文】", f"英文题目：{item.get('title', '')}"]
    lines.append(f"来源：{item.get('feed', '')}")
    lines.append(f"文章类型：{item.get('article_type', 'Research Article')}")
    lines.append(f"一作：{item.get('first_author', '未提供')}")
    if item.get("doi"):
        lines.append(f"DOI：{item['doi']}")
    lines.append(f"\n【RSS 信息】\n{item.get('summary', '无')}")
    lines.append(f"\n【原文链接】\n{item.get('link', '')}")
    return "\n".join(lines)


def ai_digest_one(item):
    api_key = _cfg("ai.api_key")
    if not api_key:
        logger.warning("未设置 AI_API_KEY，使用原始推送")
        return _format_raw(item)

    rss_prompt = _cfg("ai.rss_prompt")
    system_prompt = _cfg("ai.system_prompt")
    authors = item.get("authors") or []
    authors_text = ", ".join(authors[:12]) if authors else "未提供"

    prompt = f"""{rss_prompt}

【论文信息】
文章类型：{item.get('article_type', 'Research Article')}
英文题目：{item.get('title', '')}
来源/RSS：{item.get('feed', '')}
卷期来源：{item.get('source_info', '') or '未提供'}
发表时间：{item.get('publication_date', '') or '未提供'}
DOI：{item.get('doi', '') or '未提供'}
一作：{item.get('first_author', '未提供')}
通讯作者：{item.get('corresponding_author', '未提供')}
作者列表：{authors_text}

【RSS 信息】
{item.get('summary', '') or '无'}

【原文链接】
{item.get('link', '')}"""

    try:
        return _ai_call(prompt, system_prompt, timeout=120)
    except Exception as e:
        logger.error(f"AI 整理失败: {e}")
        return _format_raw(item)


def ai_short_meta(title, digest=""):
    api_key = _cfg("ai.api_key")
    if not api_key:
        return "", ""

    prompt = f"""请根据下面论文信息生成手机推送用的中文题目和中文关键词。

要求：
1. 必须把英文题目翻译成中文，不要照抄英文。
2. 中文关键词给 3-6 个，用顿号分隔。
3. 只返回 JSON，不要解释，不要 Markdown。
4. JSON 格式必须是：
{{"中文题目":"...","中文关键词":"..."}}

英文题目：
{title}

已有摘要：
{(digest or "")[:1200]}"""

    try:
        text = _ai_call(prompt, "你只输出严格 JSON。", timeout=60)
        text = re.sub(r"^```json\s*|\s*```$", "", text, flags=re.I | re.S).strip()
        data = json.loads(text)
        return str(data.get("中文题目", "")).strip(), str(data.get("中文关键词", "")).strip()
    except Exception as e:
        logger.error(f"短推送元数据生成失败: {e}")
        return "", ""


def ai_summarize_pdf(paper, filename, text):
    pdf_prompt = _cfg("ai.pdf_prompt")
    system_prompt = _cfg("ai.system_prompt")
    content = text[:45000]

    prompt = f"""{pdf_prompt}

已匹配的 RSS 论文信息：
题目：{paper.get("title", "")}
期刊/来源：{paper.get("feed", "")}
DOI：{paper.get("doi", "") or "未提供"}
一作：{paper.get("first_author", "") or "未提供"}
原文链接：{paper.get("link", "")}

PDF 文件名：{filename}

PDF 提取文本：
{content}"""

    try:
        return _ai_call(prompt, system_prompt, timeout=180)
    except Exception as e:
        logger.error(f"PDF AI 总结失败: {e}")
        return f"AI 总结失败: {e}\n文件：{filename}"


# ── HTML inbox ──────────────────────────────────────────

def save_html(title, content, source="rss", pdf_path=None):
    INBOX_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_title = _sanitize_filename(title)
    filename = f"{timestamp}_{safe_title}.html"
    filepath = INBOX_DIR / filename

    escaped_title = html_mod.escape(title or "无标题")
    escaped_content = html_mod.escape(content or "")
    created = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    original_link = ""
    m = re.search(r"https?://\S+", content or "")
    if m:
        original_link = m.group(0).rstrip("。,.，)）]】")
    escaped_link = html_mod.escape(original_link)
    link_html = f'<a class="btn secondary" href="{escaped_link}">打开原文链接</a>' if original_link else ""

    if pdf_path:
        pdf_meta = f'<meta name="pdf-file" content="{html_mod.escape(str(pdf_path))}">'
    else:
        pdf_meta = ""

    tpl = _inbox_template()
    page = (tpl
            .replace("__TITLE__", escaped_title)
            .replace("__SOURCE__", html_mod.escape(source or "rss"))
            .replace("__PDF_META__", pdf_meta)
            .replace("__CREATED__", html_mod.escape(created))
            .replace("__LINK_HTML__", link_html)
            .replace("__CONTENT__", escaped_content))

    filepath.write_text(page, encoding="utf-8")
    return filename, timestamp


def update_index(filename, display_text):
    INBOX_DIR.mkdir(parents=True, exist_ok=True)
    entry = f'<article class="item">\n  <a href="{html_mod.escape(filename)}">{display_text}</a>\n</article>\n'

    if not INDEX_HTML.exists() or "<!-- ITEMS -->" not in INDEX_HTML.read_text(encoding="utf-8", errors="replace"):
        INDEX_HTML.write_text(_index_template(), encoding="utf-8")

    text = INDEX_HTML.read_text(encoding="utf-8", errors="replace")
    text = text.replace("<!-- ITEMS -->", "<!-- ITEMS -->\n" + entry, 1)
    INDEX_HTML.write_text(text, encoding="utf-8")


def make_short_push(title, digest, filename):
    """从摘要文本里提取中文题目和关键词，缺失或不合规时用 AI 补齐。
    返回 (cn_title, keywords)。"""
    cn_title = _extract_line_value(digest, ["中文题目", "题目中文翻译", "中文标题"])
    keywords = _extract_line_value(digest, ["中文关键词", "关键词"])

    need_ai = False
    if not cn_title or cn_title.strip() == (title or "").strip() or not _has_cjk(cn_title):
        need_ai = True
    if not keywords or keywords in ("未提取", "未提供", "无"):
        need_ai = True

    if need_ai:
        ai_cn, ai_kw = ai_short_meta(title, digest)
        if ai_cn and _has_cjk(ai_cn):
            cn_title = ai_cn
        if ai_kw:
            keywords = ai_kw

    return cn_title or title or "未提取", keywords or "未提取"


# ── Core tasks ──────────────────────────────────────────

_db_lock = threading.Lock()


def _fetch_single_feed(feed, per_feed_limit):
    """抓取单个RSS源，返回 (feed, entries, error)"""
    start = time.time()
    try:
        r = http_get(feed["url"], timeout=35, max_attempts=3)
        parsed = feedparser.parse(r.content)
        entries = getattr(parsed, "entries", [])[:per_feed_limit]
        return feed, entries, None, int((time.time() - start) * 1000)
    except Exception as e:
        return feed, [], str(e), int((time.time() - start) * 1000)


def collect_new(opml_path, db_path, per_feed_limit=3, progress_callback=None):
    feeds = parse_opml(opml_path)
    logger.info(f"RSS 源数量: {len(feeds)}")
    con = _db_open(db_path)
    new_items = []
    total = len(feeds)
    done = 0

    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = {
            executor.submit(_fetch_single_feed, feed, per_feed_limit): feed
            for feed in feeds
        }

        for future in as_completed(futures):
            done += 1
            feed, entries, error, duration_ms = future.result()
            record_feed_health(feed, ok=not error, count=len(entries), error=error or "", duration_ms=duration_ms)

            if progress_callback:
                progress_callback(done, total, f"[{done}/{total}] {feed['title']}")
            logger.info(f"[{done}/{total}] {feed['title']}")

            if error:
                logger.error(f"抓取失败: {feed['title']} - {error}")
                record_event("feed_fetch", f"RSS 抓取失败: {feed['title']}", level="warning", details={
                    "url": feed.get("url", ""),
                    "error": error,
                })
                continue

            for entry in entries:
                item_id = _uid(feed["url"], entry)
                with _db_lock:
                    if con.execute("SELECT 1 FROM seen WHERE id=?", (item_id,)).fetchone():
                        continue

                title = _clean(getattr(entry, "title", "无标题"), 240)
                link = getattr(entry, "link", "") or ""
                summary_raw = (getattr(entry, "summary", "") or
                               getattr(entry, "description", "") or "")
                summary = _clean(summary_raw, 1200)
                authors = _get_authors(entry)
                pub_date, source_info = _extract_pub_info(summary_raw)
                doi = _find_doi(title, summary_raw, link)

                with _db_lock:
                    con.execute("INSERT OR IGNORE INTO seen VALUES(?,?,?,?,?)",
                                (item_id, title, link, feed["title"], int(time.time())))
                    con.commit()

                new_items.append({
                    "feed": feed["title"], "title": title, "summary": summary,
                    "link": link, "doi": doi, "authors": authors,
                    "first_author": authors[0] if authors else "未提供",
                    "corresponding_author": "未提供",
                    "publication_date": pub_date, "source_info": source_info,
                    "article_type": _classify_type(title, summary_raw, feed["title"]),
                })

    con.close()
    return new_items


def register_pending(item):
    raw = (item.get("doi") or item.get("link") or item.get("title") or "").strip()
    if not raw:
        return
    pid = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    con = _pending_db()
    con.execute("""INSERT OR IGNORE INTO pending_papers
        (id, title, doi, link, feed, first_author, created_ts, processed)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)""",
                (pid, item.get("title", ""), item.get("doi", ""), item.get("link", ""),
                 item.get("feed", ""), item.get("first_author", "未提供"), int(time.time())))
    con.commit()
    con.close()


def _rss_item_key(item):
    raw = (item.get("doi") or item.get("link") or item.get("title") or "").strip()
    return hashlib.sha1(raw.encode("utf-8")).hexdigest() if raw else ""


def enqueue_rss_items(items):
    con = _admin_db()
    added = 0
    for item in items:
        key = _rss_item_key(item)
        if not key:
            continue
        cur = con.execute("""INSERT OR IGNORE INTO rss_queue
            (item_key, item_json, status, created_ts) VALUES (?, ?, 'pending', ?)""",
                          (key, _json_dumps(item), int(time.time())))
        if cur.rowcount:
            added += 1
    con.commit()
    con.close()
    if added:
        record_event("rss_discovery", f"RSS 发现入队 {added} 篇", details={"added": added})
    return added


def get_rss_queue_stats():
    con = _admin_db()
    rows = con.execute("SELECT status, COUNT(*) FROM rss_queue GROUP BY status").fetchall()
    con.close()
    stats = {"pending": 0, "published": 0, "error": 0, "total": 0}
    for status, count in rows:
        stats[status or "unknown"] = count
        stats["total"] += count
    return stats


def _fmt_ts(ts):
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S") if ts else ""


def get_rss_queue(status=None, limit=100):
    limit = max(1, min(int(limit or 100), 500))
    status = (status or "").strip().lower()
    params = []
    where = ""
    if status in {"pending", "published", "error"}:
        where = "WHERE status=?"
        params.append(status)
    params.append(limit)

    con = _admin_db()
    rows = con.execute(f"""SELECT id, item_json, status, created_ts, published_ts, error
        FROM rss_queue
        {where}
        ORDER BY created_ts DESC, id DESC
        LIMIT ?""", params).fetchall()
    con.close()

    items = []
    for row_id, raw, row_status, created_ts, published_ts, error in rows:
        try:
            item = json.loads(raw or "{}")
        except Exception:
            item = {}
        items.append({
            "id": row_id,
            "status": row_status or "unknown",
            "created_ts": created_ts or 0,
            "created": _fmt_ts(created_ts),
            "published_ts": published_ts or 0,
            "published": _fmt_ts(published_ts),
            "error": error or "",
            "title": item.get("title", ""),
            "feed": item.get("feed", ""),
            "link": item.get("link", ""),
            "doi": item.get("doi", ""),
            "first_author": item.get("first_author", ""),
            "publication_date": item.get("publication_date", ""),
            "source_info": item.get("source_info", ""),
            "article_type": item.get("article_type", ""),
        })

    return {
        "stats": get_rss_queue_stats(),
        "status": status if status in {"pending", "published", "error"} else "",
        "limit": limit,
        "items": items,
    }


def _next_rss_queue_items(limit):
    con = _admin_db()
    rows = con.execute("""SELECT id, item_json FROM rss_queue
        WHERE status='pending' ORDER BY created_ts ASC, id ASC LIMIT ?""",
                       (max(1, int(limit or 1)),)).fetchall()
    con.close()
    items = []
    for row_id, raw in rows:
        try:
            item = json.loads(raw)
            items.append((row_id, item))
        except Exception:
            _mark_rss_queue_error(row_id, "队列 JSON 无法解析")
    return items


def _mark_rss_queue_published(row_id):
    con = _admin_db()
    con.execute("UPDATE rss_queue SET status='published', published_ts=?, error='' WHERE id=?",
                (int(time.time()), row_id))
    con.commit()
    con.close()


def _mark_rss_queue_error(row_id, error):
    con = _admin_db()
    con.execute("UPDATE rss_queue SET status='error', error=? WHERE id=?", (str(error)[:500], row_id))
    con.commit()
    con.close()


def _publish_rss_item(item, idx=1, total=1, progress_callback=None):
    if progress_callback:
        progress_callback(idx, total, f"整理 [{idx}/{total}]: {item['title'][:30]}...")
    logger.info(f"整理: {item['title']}")
    register_pending(item)
    digest = ai_digest_one(item)

    filename, ts = save_html(item["title"], digest)
    update_index(filename, f"{ts} {html_mod.escape(item['title'])}")

    cn_title, keywords = make_short_push(item["title"], digest, filename)
    record_digest(filename, ts, item["title"], digest, source="rss", cn_title=cn_title, keywords=keywords)
    push.send_digest_notification(cn_title, keywords, filename)
    return filename


def run_rss_discovery(progress_callback=None):
    cfg = load_config()
    opml = get_opml_path(cfg)
    per_feed = cfg.get("rss", {}).get("per_feed_limit", 3)
    if progress_callback:
        progress_callback(0, 0, "正在抓取 RSS 并入队...")
    record_event("rss_discovery", "RSS discovery 开始")
    items = collect_new(opml, str(RSS_DB), per_feed_limit=per_feed, progress_callback=progress_callback)
    added = enqueue_rss_items(items)
    msg = f"RSS discovery 完成: 新发现 {len(items)} 篇，入队 {added} 篇"
    logger.info(msg)
    record_event("rss_discovery", msg, details={"found": len(items), "added": added})
    if progress_callback:
        progress_callback(len(items), len(items), msg)
    return added


def run_rss_publish(progress_callback=None):
    cfg = load_config()
    max_items = cfg.get("rss", {}).get("max_push_items", 20)
    queued = _next_rss_queue_items(max_items)
    if not queued:
        msg = "RSS publish 完成: 队列为空"
        logger.info(msg)
        record_event("rss_publish", msg)
        if progress_callback:
            progress_callback(0, 0, msg)
        return 0

    count = 0
    total = len(queued)
    record_event("rss_publish", f"RSS publish 开始: {total} 篇")
    for idx, (row_id, item) in enumerate(queued, 1):
        try:
            _publish_rss_item(item, idx, total, progress_callback)
            _mark_rss_queue_published(row_id)
            count += 1
            time.sleep(1.5)
        except Exception as e:
            logger.error(f"RSS 队列发布失败: {item.get('title', '')} | {e}")
            _mark_rss_queue_error(row_id, str(e))
            record_event("rss_publish", "RSS 队列发布失败", level="error", details={
                "title": item.get("title", ""),
                "error": str(e),
            })
    msg = f"RSS publish 完成: 新增 {count} 篇"
    logger.info(msg)
    record_event("rss_publish", msg, details={"published": count, "total": total})
    return count


def run_rss_cycle(progress_callback=None):
    if progress_callback:
        progress_callback(0, 0, "RSS 刷新开始：发现并入队...")
    queued = run_rss_discovery(progress_callback=progress_callback)
    if progress_callback:
        progress_callback(0, 0, "RSS 刷新继续：发布队列...")
    published = run_rss_publish(progress_callback=progress_callback)
    result = {"queued": queued, "published": published}
    logger.info(f"RSS 完成: 入队 {queued} 篇，发布 {published} 篇")
    return result


def run_pdf_watch(progress_callback=None):
    con = _pending_db()
    cutoff = int(time.time()) - 21 * 86400
    rows = con.execute("""SELECT id, title, doi, link, feed, first_author
        FROM pending_papers WHERE processed=0 AND created_ts>=?
        ORDER BY created_ts DESC""", (cutoff,)).fetchall()
    con.close()

    if not rows:
        if progress_callback:
            progress_callback(0, 0, "没有待匹配论文")
        return 0

    pending = [{"id": r[0], "title": r[1], "doi": r[2], "link": r[3],
                "feed": r[4], "first_author": r[5]} for r in rows]

    pdfs = []
    for d in DOWNLOAD_DIRS:
        if d.exists():
            pdfs.extend(d.glob("*.pdf"))
    pdfs = sorted(pdfs, key=lambda p: p.stat().st_mtime, reverse=True)

    pdf_con = _pdf_db()
    count = 0
    skipped_old = 0
    skipped_seen = 0
    skipped_unstable = 0
    read_failed = 0
    too_little_text = 0
    unmatched = 0
    errors = 0
    total_pdfs = len(pdfs)

    if progress_callback:
        progress_callback(0, total_pdfs, f"扫描 {total_pdfs} 个 PDF...")

    for idx, pdf in enumerate(pdfs, 1):
        if progress_callback:
            progress_callback(idx, total_pdfs, f"检查: {pdf.name[:30]}...")
        if time.time() - pdf.stat().st_mtime > 3 * 86400:
            skipped_old += 1
            continue
        if not _is_stable(pdf):
            skipped_unstable += 1
            continue

        h = _file_hash(pdf)
        if pdf_con.execute("SELECT 1 FROM pdf_seen WHERE file_hash=?", (h,)).fetchone():
            skipped_seen += 1
            continue

        try:
            text = _extract_pdf_text(pdf)
        except Exception as e:
            logger.error(f"PDF 读取失败: {pdf.name} | {e}")
            read_failed += 1
            _mark_pdf(pdf_con, h, pdf, "", "read_failed")
            continue

        if len(text) < 1000:
            too_little_text += 1
            _mark_pdf(pdf_con, h, pdf, "", "too_little_text")
            continue

        paper, score, reason = _match_pdf(text, pending)
        if not paper:
            unmatched += 1
            _mark_pdf(pdf_con, h, pdf, "", "unmatched")
            continue

        logger.info(f"PDF 匹配: {pdf.name} -> {paper['title']}")
        try:
            msg = ai_summarize_pdf(paper, pdf.name, text)
            filename, ts = save_html(paper["title"], msg, source="pdf", pdf_path=str(pdf))
            update_index(filename, f"{ts} {html_mod.escape(paper['title'])}")

            cn_title, keywords = make_short_push(paper["title"], msg, filename)
            record_digest(filename, ts, paper["title"], msg, source="pdf", cn_title=cn_title, keywords=keywords)
            push.send_pdf_notification(cn_title, keywords, filename)

            _mark_pdf(pdf_con, h, pdf, paper["id"], "processed")
            con = _pending_db()
            con.execute("UPDATE pending_papers SET processed=1 WHERE id=?", (paper["id"],))
            con.commit()
            con.close()
            count += 1
        except Exception as e:
            logger.error(f"PDF 总结失败: {pdf.name} | {e}")
            errors += 1
            _mark_pdf(pdf_con, h, pdf, paper["id"], "error")

    pdf_con.close()
    summary = (
        f"PDF 监控完成: 新增 {count} 篇；过旧 {skipped_old}；已处理 {skipped_seen}；"
        f"未稳定 {skipped_unstable}；未匹配 {unmatched}；文本过少 {too_little_text}；"
        f"读取失败 {read_failed}；总结失败 {errors}"
    )
    if progress_callback:
        progress_callback(total_pdfs, total_pdfs, summary)
    logger.info(summary)
    return count


def _is_stable(path, wait=3):
    try:
        s1 = path.stat().st_size
        time.sleep(wait)
        s2 = path.stat().st_size
        return s1 == s2 and s2 > 20_000
    except Exception:
        return False


def _file_hash(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _extract_pdf_text(path, max_pages=25):
    from pypdf import PdfReader
    reader = PdfReader(str(path))
    pages = []
    for i, page in enumerate(reader.pages[:max_pages]):
        try:
            txt = page.extract_text() or ""
        except Exception:
            txt = ""
        txt = _clean_full(txt)
        if txt:
            pages.append(f"[Page {i+1}]\n{txt}")
    return "\n\n".join(pages)


def _match_pdf(pdf_text, pending):
    text_n = pdf_text.lower()
    best, best_score, best_reason = None, 0.0, ""

    for paper in pending:
        doi = (paper.get("doi") or "").lower().strip()
        if doi and doi in text_n:
            return paper, 1.0, "DOI matched"

        title = (paper.get("title") or "").lower()
        title_n = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", " ", title).strip()
        words = [w for w in title_n.split() if len(w) >= 4]
        if words:
            hit = sum(1 for w in words if w in text_n)
            score = hit / len(words)
            if score > best_score:
                best, best_score, best_reason = paper, score, f"keywords={score:.2f}"

    if best and best_score >= 0.65:
        return best, best_score, best_reason
    return None, best_score, best_reason


def _mark_pdf(con, h, path, paper_id, status):
    con.execute("INSERT OR REPLACE INTO pdf_seen VALUES(?,?,?,?,?)",
                (h, str(path), paper_id or "", status, int(time.time())))
    con.commit()


def get_pdf_queue(limit=100):
    limit = max(1, min(int(limit or 100), 500))
    cutoff = int(time.time()) - 21 * 86400

    con = _pending_db()
    pending_total = con.execute("SELECT COUNT(*) FROM pending_papers WHERE processed=0").fetchone()[0]
    pending_recent = con.execute("""SELECT id, title, doi, link, feed, first_author, created_ts
        FROM pending_papers
        WHERE processed=0 AND created_ts>=?
        ORDER BY created_ts DESC
        LIMIT ?""", (cutoff, limit)).fetchall()
    con.close()

    pending = []
    pending_titles = {}
    for r in pending_recent:
        pending_titles[r[0]] = r[1] or ""
        pending.append({
            "id": r[0],
            "title": r[1] or "",
            "doi": r[2] or "",
            "link": r[3] or "",
            "feed": r[4] or "",
            "first_author": r[5] or "",
            "created_ts": r[6] or 0,
            "created": _fmt_ts(r[6]),
        })

    pdf_con = _pdf_db()
    status_rows = pdf_con.execute("SELECT status, COUNT(*) FROM pdf_seen GROUP BY status").fetchall()
    recent_rows = pdf_con.execute("""SELECT file_hash, path, matched_paper_id, status, ts
        FROM pdf_seen ORDER BY ts DESC LIMIT ?""", (limit,)).fetchall()
    pdf_con.close()

    pdf_status = {"total": 0}
    for status, count in status_rows:
        pdf_status[status or "unknown"] = count
        pdf_status["total"] += count

    matched_ids = {r[2] for r in recent_rows if r[2]}
    if matched_ids:
        placeholders = ",".join("?" for _ in matched_ids)
        con = _pending_db()
        rows = con.execute(
            f"SELECT id, title FROM pending_papers WHERE id IN ({placeholders})",
            tuple(matched_ids),
        ).fetchall()
        con.close()
        pending_titles.update({r[0]: r[1] or "" for r in rows})

    recent = []
    for file_hash, path, matched_paper_id, status, ts in recent_rows:
        p = Path(path or "")
        recent.append({
            "file_hash": file_hash or "",
            "path": path or "",
            "filename": p.name if path else "",
            "matched_paper_id": matched_paper_id or "",
            "matched_title": pending_titles.get(matched_paper_id or "", ""),
            "status": status or "",
            "ts": ts or 0,
            "time": _fmt_ts(ts),
        })

    dirs = []
    pdf_files = 0
    for d in DOWNLOAD_DIRS:
        exists = d.exists()
        count = len(list(d.glob("*.pdf"))) if exists else 0
        pdf_files += count
        dirs.append({"path": str(d), "exists": exists, "pdf_count": count})

    return {
        "stats": {
            "pending_total": pending_total,
            "pending_recent_21_days": len(pending),
            "pdf_files": pdf_files,
            "pdf_seen": pdf_status,
        },
        "pending": pending,
        "recent": recent,
        "download_dirs": dirs,
        "limit": limit,
    }


# ── Status / Logs ───────────────────────────────────────

def get_status():
    cfg = load_config()
    schedule = cfg.get("schedule", {})
    rss_int = schedule.get("rss_interval_minutes", 30)
    rss_discovery_int = schedule.get("rss_discovery_interval_minutes", rss_int)
    pdf_int = schedule.get("pdf_interval_minutes", 5)
    enabled = schedule.get("enabled", True)

    con = _db_open(str(RSS_DB))
    total = con.execute("SELECT COUNT(*) FROM seen").fetchone()[0]
    last_ts = con.execute("SELECT MAX(ts) FROM seen").fetchone()[0]
    con.close()

    con = _pending_db()
    pending = con.execute("SELECT COUNT(*) FROM pending_papers WHERE processed=0").fetchone()[0]
    con.close()

    inbox_count = len(list(INBOX_DIR.glob("*.html"))) - 1  # exclude index.html

    # PDF原文：download目录中的PDF文件数
    pdf_count = 0
    for d in DOWNLOAD_DIRS:
        if d.exists():
            pdf_count += len(list(d.glob("*.pdf")))

    return {
        "enabled": enabled,
        "rss_interval": rss_int,
        "rss_discovery_interval": rss_discovery_int,
        "pdf_interval": pdf_int,
        "total_articles": total,
        "pending_papers": pending,
        "inbox_summaries": max(inbox_count, 0),
        "pdf_count": pdf_count,
        "api_balance": "N/A",
        "last_run": datetime.fromtimestamp(last_ts).strftime("%Y-%m-%d %H:%M:%S") if last_ts else "从未运行",
        "feeds_count": len(parse_opml(get_opml_path(cfg))),
        "rss_queue": get_rss_queue_stats(),
    }


def _path_status(path, kind="file"):
    p = Path(path)
    exists = p.exists()
    target = p if kind == "dir" else p.parent
    writable = False
    try:
        target.mkdir(parents=True, exist_ok=True)
        probe = target / ".rssaipush_write_test"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink(missing_ok=True)
        writable = True
    except Exception:
        writable = False
    return {"path": str(p), "exists": exists, "writable": writable}


def _port_open(host, port, timeout=0.5):
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except Exception:
        return False


def _tasklist_contains(name):
    if os.name != "nt":
        return False
    try:
        r = subprocess.run(
            ["tasklist", "/FI", f"IMAGENAME eq {name}"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        return name.lower() in (r.stdout or "").lower()
    except Exception:
        return False


def get_admin_settings():
    cfg = public_config(load_config())
    rss = cfg.setdefault("rss", {})
    rss.setdefault("opml_path", str(BASE_DIR / "feedly.opml"))
    rss.setdefault("per_feed_limit", 3)
    rss.setdefault("max_push_items", 20)
    schedule = cfg.setdefault("schedule", {})
    schedule.setdefault("rss_discovery_interval_minutes", schedule.get("rss_interval_minutes", 30))
    server = cfg.setdefault("server", {})
    effective_host = os.environ.get("RSSAI_SERVER_HOST", server.get("host", "0.0.0.0"))
    effective_port = int(os.environ.get("RSSAI_SERVER_PORT", server.get("port", 5000)))
    server["auth_token"] = get_auth_token()
    server["effective_host"] = effective_host
    server["effective_port"] = effective_port
    server["effective_local_url"] = f"http://{'127.0.0.1' if effective_host in ('0.0.0.0', '::') else effective_host}:{effective_port}"
    pc = cfg.setdefault("pc", {})
    quick_tunnel = get_quick_tunnel_state()
    pc.setdefault("cloudflare_tunnel_url", os.environ.get("RSSAI_TUNNEL_URL", ""))
    pc["current_tunnel_url"] = quick_tunnel.get("url") or pc.get("cloudflare_tunnel_url", "")
    pc["quick_tunnel"] = quick_tunnel
    pc["quick_tunnel_state_path"] = str(QUICK_TUNNEL_STATE)
    pc.setdefault("install_dir", os.environ.get("RSSAI_INSTALL_DIR", ""))
    pc.setdefault("data_dir", str(BASE_DIR))
    pc["config_path"] = str(CONFIG_PATH)
    pc["inbox_dir"] = str(INBOX_DIR)
    pc["uploaded_pdf_dir"] = str(UPLOADED_PDF_DIR)
    pc["download_dirs"] = [str(p) for p in DOWNLOAD_DIRS]
    pc["rss_db"] = str(RSS_DB)
    pc["pending_db"] = str(PENDING_DB)
    pc["pdf_db"] = str(PDF_DB)
    pc["digest_db"] = str(DIGEST_DB)
    pc["admin_db"] = str(ADMIN_DB)
    if pc.get("cloudflare_tunnel_token") or os.environ.get("RSSAI_TUNNEL_TOKEN"):
        pc["cloudflare_tunnel_token"] = MASKED_SECRET
    else:
        pc.setdefault("cloudflare_tunnel_token", "")
    return cfg


def save_admin_settings(data):
    cfg = load_config()
    incoming = data or {}
    for section in ("ai", "rss", "schedule", "server", "pc"):
        if section not in incoming:
            continue
        patch = dict(incoming.get(section) or {})
        if section in ("ai", "server", "pc"):
            for key in ("api_key", "auth_token", "cloudflare_tunnel_token"):
                if key in patch:
                    secret = str(patch.get(key, "")).strip()
                    if not secret or secret == MASKED_SECRET or set(secret) == {"*"}:
                        patch.pop(key, None)
        cfg.setdefault(section, {}).update(patch)
    save_config(cfg)
    record_event("settings", "Web 设置已保存")
    return get_admin_settings()


def get_admin_overview(progress=None, running=None):
    cfg = load_config()
    server = cfg.get("server", {})
    host = os.environ.get("RSSAI_SERVER_HOST", server.get("host", "0.0.0.0"))
    port = int(os.environ.get("RSSAI_SERVER_PORT", server.get("port", 5000)))
    local_host = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    paths = {
        "base_dir": _path_status(BASE_DIR, "dir"),
        "config": _path_status(CONFIG_PATH),
        "rss_db": _path_status(RSS_DB),
        "pending_db": _path_status(PENDING_DB),
        "pdf_db": _path_status(PDF_DB),
        "digest_db": _path_status(DIGEST_DB),
        "admin_db": _path_status(ADMIN_DB),
        "inbox": _path_status(INBOX_DIR, "dir"),
        "uploaded_pdfs": _path_status(UPLOADED_PDF_DIR, "dir"),
    }
    configured_tunnel_url = (cfg.get("pc", {}) or {}).get("cloudflare_tunnel_url") or os.environ.get("RSSAI_TUNNEL_URL", "")
    quick_tunnel = get_quick_tunnel_state()
    current_tunnel_url = quick_tunnel.get("url") or configured_tunnel_url
    quick_age = quick_tunnel.get("age_seconds")
    quick_process_running = (
        quick_tunnel.get("status") in ("starting", "connected")
        and (quick_age is None or quick_age < 120)
    )
    cloudflared_process_present = _tasklist_contains("cloudflared.exe")
    return {
        "ok": True,
        "status": get_status(),
        "progress": progress or {},
        "running": running or {},
        "runtime": runtime_info(),
        "paths": paths,
        "app": get_app_heartbeat(),
        "rss_queue": get_rss_queue_stats(),
        "recent_events": get_events(20),
        "tunnel": {
            "mode": quick_tunnel.get("mode") or ("named" if configured_tunnel_url else "quick"),
            "configured_url": configured_tunnel_url,
            "current_url": current_tunnel_url,
            "quick": quick_tunnel,
            "process_running": quick_process_running,
            "cloudflared_process_present": cloudflared_process_present,
            "token_configured": bool(os.environ.get("RSSAI_TUNNEL_TOKEN") or (cfg.get("pc", {}) or {}).get("cloudflare_tunnel_token")),
        },
        "server": {
            "host": host,
            "port": port,
            "local_url": f"http://{local_host}:{port}",
            "listening": _port_open(local_host, port),
            "auth_required": bool(get_auth_token()),
            "auth_token": get_auth_token(),
        },
    }


def reset_seen_to_recent_week():
    """重置seen表，只保留最近一周的记录"""
    con = _db_open(str(RSS_DB))
    cutoff = int(time.time()) - 7 * 86400
    con.execute("DELETE FROM seen WHERE ts < ?", (cutoff,))
    con.commit()
    con.close()


def get_recent_digests(limit=20, source=None):
    limit = max(1, min(int(limit or 20), 500))
    try:
        _sync_digest_index()
        con = _digest_db()
        params = []
        where = ""
        if source:
            where = "WHERE source=?"
            params.append(source)
        params.append(limit)
        rows = con.execute(f"""SELECT filename, timestamp, title, cn_title, keywords,
            journal, source, preview FROM digests
            {where}
            ORDER BY created_ts DESC, id DESC
            LIMIT ?""", params).fetchall()
        con.close()
        return [{
            "filename": r[0],
            "timestamp": r[1] or "",
            "title": r[2] or "",
            "cn_title": r[3] or "",
            "keywords": r[4] or "",
            "journal": r[5] or "",
            "source": r[6] or "rss",
            "preview": r[7] or "",
        } for r in rows]
    except Exception as e:
        logger.warning(f"摘要索引读取失败，回退扫描文件: {e}")
        files = sorted(INBOX_DIR.glob("*.html"), key=lambda p: p.stat().st_mtime, reverse=True)
        files = [f for f in files if f.name != "index.html"]
        digests = []
        for f in files[:limit * 5] if source else files[:limit]:
            digest = _digest_from_file(f)
            if source and digest["source"] != source:
                continue
            digest.pop("created_ts", None)
            digests.append(digest)
            if len(digests) >= limit:
                break
        return digests


def get_digest_updates(after=0, limit=50, source=None):
    limit = max(1, min(int(limit or 50), 200))
    after = max(0, int(after or 0))
    _sync_digest_index()
    con = _digest_db()
    params = [after]
    where = "WHERE id>?"
    if source:
        where += " AND source=?"
        params.append(source)
    params.append(limit)
    rows = con.execute(f"""SELECT id, filename, timestamp, title, cn_title, keywords,
        journal, source, preview FROM digests
        {where}
        ORDER BY id ASC
        LIMIT ?""", params).fetchall()
    con.close()
    cursor = after
    items = []
    for r in rows:
        cursor = max(cursor, r[0])
        items.append({
            "cursor": r[0],
            "filename": r[1],
            "timestamp": r[2] or "",
            "title": r[3] or "",
            "cn_title": r[4] or "",
            "keywords": r[5] or "",
            "journal": r[6] or "",
            "source": r[7] or "rss",
            "preview": r[8] or "",
        })
    return {"cursor": cursor, "items": items}


def delete_digest(filename):
    if not filename or "/" in filename or "\\" in filename or ".." in filename:
        raise ValueError("非法文件名")
    path = INBOX_DIR / filename
    if not path.exists() or not path.is_file():
        raise FileNotFoundError("摘要不存在")
    path.unlink()
    try:
        con = _digest_db()
        con.execute("DELETE FROM digests WHERE filename=?", (filename,))
        con.commit()
        con.close()
    except Exception:
        pass
    return True


def clear_digests(source=None):
    files = sorted(INBOX_DIR.glob("*.html"), key=lambda p: p.stat().st_mtime, reverse=True)
    count = 0
    for f in files:
        if f.name == "index.html":
            continue
        if source:
            try:
                if _digest_from_file(f)["source"] != source:
                    continue
            except Exception:
                continue
        f.unlink()
        try:
            con = _digest_db()
            con.execute("DELETE FROM digests WHERE filename=?", (f.name,))
            con.commit()
            con.close()
        except Exception:
            pass
        count += 1
    return count


def get_digest_text(filename):
    """读取某篇摘要 HTML 的纯文本（标题 + 正文），供对话上下文使用。"""
    if not filename or "/" in filename or "\\" in filename or ".." in filename:
        raise ValueError("非法文件名")
    path = INBOX_DIR / filename
    if not path.exists() or not path.is_file():
        raise FileNotFoundError("摘要不存在")
    content = path.read_text(encoding="utf-8", errors="replace")
    title = ""
    t_match = re.search(r"<title>(.*?)</title>", content, re.S)
    if t_match:
        title = html_mod.unescape(t_match.group(1)).strip()
    body = ""
    c_match = re.search(r'<div class="content">(.*?)</div>', content, re.S)
    if c_match:
        body = html_mod.unescape(c_match.group(1)).strip()
    parts = []
    if title:
        parts.append(f"标题：{title}")
    if body:
        parts.append(f"内容：\n{body}")
    return "\n\n".join(parts) if parts else ""


def _norm_title(s):
    return re.sub(r"[^a-z0-9一-鿿]+", "", (s or "").lower())


def _under_download_dir(path):
    try:
        p = Path(path).resolve()
    except Exception:
        return None
    for d in DOWNLOAD_DIRS:
        try:
            dd = d.resolve()
        except Exception:
            continue
        if str(p) == str(dd) or str(p).startswith(str(dd) + os.sep):
            return p if p.exists() else None
    return None


def _lookup_pdf_by_title(title):
    """按论文标题在 pdf_seen + pending_papers 里查源 PDF 路径，找不到返回 None。"""
    norm = _norm_title(title)
    if not norm:
        return None
    try:
        pdf_con = _pdf_db()
        rows = pdf_con.execute(
            "SELECT path, matched_paper_id, ts FROM pdf_seen WHERE status='processed' ORDER BY ts DESC"
        ).fetchall()
        pdf_con.close()
    except Exception:
        return None
    if not rows:
        return None
    try:
        pend_con = _pending_db()
        title_by_id = {
            r[0]: r[1]
            for r in pend_con.execute("SELECT id, title FROM pending_papers").fetchall()
        }
        pend_con.close()
    except Exception:
        title_by_id = {}
    for p_path, paper_id, _ts in rows:
        t = title_by_id.get(paper_id, "")
        nt = _norm_title(t)
        # 标题可能被 _sanitize_filename 截断，故用前缀匹配（双向，最小长度避免误判）
        if nt and len(norm) >= 12 and (nt == norm or nt.startswith(norm) or norm.startswith(nt)):
            ok = _under_download_dir(p_path)
            if ok:
                return str(ok)
    return None


def resolve_pdf_path(filename):
    """解析某篇摘要对应的源 PDF 绝对路径，找不到返回 None。"""
    if not filename or "/" in filename or "\\" in filename or ".." in filename:
        return None
    path = INBOX_DIR / filename
    if not path.exists():
        return None

    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except Exception:
        content = ""

    # 1) 优先用摘要里记录的 pdf-file meta
    m = re.search(r'<meta name="pdf-file" content="([^"]*)">', content)
    if m:
        candidate = html_mod.unescape(m.group(1)).strip()
        if candidate:
            ok = _under_download_dir(candidate)
            if ok:
                return str(ok)

    # 2) 回退：按标题匹配
    stem = Path(filename).stem
    parts = stem.split("_", 2)
    digest_title = parts[2].replace("_", " ") if len(parts) >= 3 else stem
    return _lookup_pdf_by_title(digest_title)


def ai_chat(filename, message, history=None):
    """基于某篇摘要内容的多轮对话。history: [{role, content}, ...]"""
    api_key = _cfg("ai.api_key")
    if not api_key:
        return "未配置 AI API Key，无法追问。"
    try:
        context = get_digest_text(filename)
    except Exception as e:
        return f"无法读取该文章内容：{e}"

    system_prompt = _cfg("ai.system_prompt") or "你是一位地学论文助手。"
    system_prompt = (
        system_prompt
        + "\n\n用户正在阅读下面这篇论文的 AI 总结，请基于该内容回答用户的追问。"
        "若内容不足以回答，请如实说明。回答用中文。"
    )
    messages = [{"role": "system", "content": system_prompt + "\n\n【文章内容】\n" + context}]
    for h in (history or []):
        role = h.get("role", "user")
        if role not in ("user", "assistant"):
            continue
        c = (h.get("content") or "").strip()
        if c:
            messages.append({"role": role, "content": c})
    messages.append({"role": "user", "content": message or ""})

    base_url = _cfg("ai.base_url", "https://api.deepseek.com").rstrip("/")
    model = _cfg("ai.model", "deepseek-chat")
    if not base_url.endswith("/v1"):
        base_url += "/v1"
    payload = {"model": model, "messages": messages, "temperature": 0.3}
    try:
        r = SESSION.post(
            f"{base_url}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            timeout=120,
        )
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"].strip()
    except Exception as e:
        return f"AI 请求失败：{e}"


def get_logs(lines=200):
    log_path = BASE_DIR / "server.log"
    if not log_path.exists():
        return []
    all_lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    return all_lines[-lines:]


# ── HTML Templates ──────────────────────────────────────

def _inbox_template():
    return '''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="digest-source" content="__SOURCE__">
__PDF_META__
<title>__TITLE__</title>
<style>
:root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#15171a;--muted:#6b7280;--border:#e5e7eb;--accent:#2563eb}
@media(prefers-color-scheme:dark){:root{--bg:#0f172a;--card:#1e293b;--text:#f1f5f9;--muted:#94a3b8;--border:#334155;--accent:#60a5fa}}
*{box-sizing:border-box}
body{margin:0;padding:14px;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans CJK SC",sans-serif;font-size:17px;line-height:1.78}
.container{max-width:820px;margin:0 auto}
.card{background:var(--card);border:1px solid var(--border);border-radius:18px;padding:18px;box-shadow:0 4px 18px rgba(0,0,0,.06)}
h1{font-size:22px;line-height:1.35;margin:0 0 10px}
.meta{color:var(--muted);font-size:14px;margin-bottom:14px}
.content{white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere}
.actions{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 14px}
.btn{display:inline-block;padding:9px 12px;border-radius:999px;background:var(--accent);color:#fff;text-decoration:none;font-size:14px}
.btn.secondary{background:transparent;color:var(--accent);border:1px solid var(--accent)}
</style>
</head>
<body>
<div class="container">
<article class="card">
<h1>__TITLE__</h1>
<div class="meta">保存时间：__CREATED__</div>
<div class="actions"><a class="btn" href="index.html">返回列表</a> __LINK_HTML__</div>
<div class="content">__CONTENT__</div>
</article>
</div>
</body>
</html>'''


def _index_template():
    return '''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>SciToday Inbox</title>
<style>
:root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#15171a;--muted:#6b7280;--border:#e5e7eb;--accent:#2563eb}
@media(prefers-color-scheme:dark){:root{--bg:#0f172a;--card:#1e293b;--text:#f1f5f9;--muted:#94a3b8;--border:#334155;--accent:#60a5fa}}
*{box-sizing:border-box}
body{margin:0;padding:14px;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans CJK SC",sans-serif;font-size:17px;line-height:1.65}
.container{max-width:820px;margin:0 auto}
h1{font-size:24px;margin:4px 0 12px}
.sub{color:var(--muted);font-size:14px;margin-bottom:14px}
.item{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:14px 16px;margin:10px 0;box-shadow:0 3px 12px rgba(0,0,0,.05)}
.item a{color:var(--text);text-decoration:none;font-weight:650;word-break:break-word}
.item a:visited{color:var(--muted)}
</style>
</head>
<body>
<div class="container">
<h1>SciToday Inbox</h1>
<div class="sub">最新论文总结在最上方。点击标题查看手机阅读版全文。</div>
<!-- ITEMS -->
</div>
</body>
</html>'''
