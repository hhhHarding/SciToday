#!/usr/bin/env python3
import os
import re
import time
import json
import sqlite3
import hashlib
import html
from datetime import datetime
from pathlib import Path

import requests
from pypdf import PdfReader


def env_path(name, default):
    value = os.environ.get(name)
    return Path(value).expanduser() if value else Path(default)


def env_path_list(name, defaults):
    raw = os.environ.get(name)
    if not raw:
        return [Path(p) for p in defaults]
    return [Path(p.strip()).expanduser() for p in raw.split(os.pathsep) if p.strip()]


BASE_DIR = env_path("RSSAI_BASE_DIR", Path.home() / "rss-agent")
DOWNLOAD_DIRS = env_path_list("RSSAI_DOWNLOAD_DIRS", [
    Path("/storage/emulated/0/Download"),
    Path("/storage/emulated/0/Download/dlmanager"),
])
PENDING_DB = env_path("RSSAI_PENDING_DB", BASE_DIR / "pending_papers.db")
PDF_DB = env_path("RSSAI_PDF_DB", BASE_DIR / "pdf_seen.db")

NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "yhdian-feedly-ai-2026")
NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh")

INBOX_DIR = env_path("RSSAI_INBOX_DIR", BASE_DIR / "inbox")
INDEX_HTML = INBOX_DIR / "index.html"


def clean_text(text):
    text = text or ""
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def norm(text):
    text = text or ""
    text = text.lower()
    text = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def file_hash(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def init_pdf_db():
    PDF_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(PDF_DB)
    con.execute("""
    CREATE TABLE IF NOT EXISTS pdf_seen(
        file_hash TEXT PRIMARY KEY,
        path TEXT,
        matched_paper_id TEXT,
        status TEXT,
        ts INTEGER
    )
    """)
    con.commit()
    return con


def init_pending_db():
    PENDING_DB.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(PENDING_DB)
    con.execute("""
    CREATE TABLE IF NOT EXISTS pending_papers(
        id TEXT PRIMARY KEY,
        title TEXT,
        doi TEXT,
        link TEXT,
        feed TEXT,
        first_author TEXT,
        created_ts INTEGER,
        processed INTEGER DEFAULT 0
    )
    """)
    con.commit()
    return con


def load_pending(days=21):
    con = init_pending_db()
    cutoff = int(time.time()) - days * 86400
    rows = con.execute("""
    SELECT id, title, doi, link, feed, first_author, created_ts
    FROM pending_papers
    WHERE processed = 0 AND created_ts >= ?
    ORDER BY created_ts DESC
    """, (cutoff,)).fetchall()
    con.close()

    papers = []
    for r in rows:
        papers.append({
            "id": r[0],
            "title": r[1] or "",
            "doi": r[2] or "",
            "link": r[3] or "",
            "feed": r[4] or "",
            "first_author": r[5] or "未提供",
            "created_ts": r[6],
        })
    return papers


def mark_pending_processed(paper_id):
    con = init_pending_db()
    con.execute("UPDATE pending_papers SET processed = 1 WHERE id = ?", (paper_id,))
    con.commit()
    con.close()


def pdf_already_done(file_hash_value):
    con = init_pdf_db()
    row = con.execute(
        "SELECT status FROM pdf_seen WHERE file_hash = ?",
        (file_hash_value,)
    ).fetchone()
    con.close()
    return row is not None


def mark_pdf(file_hash_value, path, paper_id, status):
    con = init_pdf_db()
    con.execute("""
    INSERT OR REPLACE INTO pdf_seen
    (file_hash, path, matched_paper_id, status, ts)
    VALUES (?, ?, ?, ?, ?)
    """, (
        file_hash_value,
        str(path),
        paper_id or "",
        status,
        int(time.time()),
    ))
    con.commit()
    con.close()


def is_stable_file(path, wait=5):
    try:
        s1 = path.stat().st_size
        time.sleep(wait)
        s2 = path.stat().st_size
        return s1 == s2 and s2 > 20_000
    except Exception:
        return False


def extract_pdf_text(path, max_pages=25):
    reader = PdfReader(str(path))
    pages = []

    for i, page in enumerate(reader.pages[:max_pages]):
        try:
            txt = page.extract_text() or ""
        except Exception:
            txt = ""
        txt = clean_text(txt)
        if txt:
            pages.append(f"[Page {i+1}]\n{txt}")

    return "\n\n".join(pages)


def title_keyword_score(title, text):
    """
    严格题名匹配：
    题名中长度 >=4 的英文关键词，至少 65% 出现在 PDF 前几页文本中。
    """
    title_n = norm(title)
    text_n = norm(text)

    if not title_n or not text_n:
        return 0.0

    # 完整题名直接出现，最高可信
    if title_n in text_n:
        return 1.0

    words = [w for w in title_n.split() if len(w) >= 4]
    if not words:
        return 0.0

    hit = sum(1 for w in words if w in text_n)
    return hit / len(words)


def match_pdf_to_pending(pdf_text, pending_papers):
    text_n = norm(pdf_text)

    best = None
    best_score = 0.0
    best_reason = ""

    for paper in pending_papers:
        doi = (paper.get("doi") or "").lower().strip()
        title = paper.get("title") or ""

        if doi and doi in pdf_text.lower():
            return paper, 1.0, "DOI matched"

        score = title_keyword_score(title, pdf_text)
        if score > best_score:
            best = paper
            best_score = score
            best_reason = f"title keyword score={score:.2f}"

    if best and best_score >= 0.65:
        return best, best_score, best_reason

    return None, best_score, best_reason


def split_utf8_text(text, max_bytes=3500):
    parts = []
    current = ""

    for block in (text or "").split("\n\n"):
        block = block.strip()
        if not block:
            continue

        candidate = current + ("\n\n" if current else "") + block

        if len(candidate.encode("utf-8")) <= max_bytes:
            current = candidate
        else:
            if current:
                parts.append(current)
            current = block

            while len(current.encode("utf-8")) > max_bytes:
                cut = ""
                for ch in current:
                    if len((cut + ch).encode("utf-8")) > max_bytes:
                        break
                    cut += ch
                parts.append(cut)
                current = current[len(cut):]

    if current:
        parts.append(current)

    return parts or [""]


def send_ntfy(message, title="PDF Fulltext Digest", click=None):
    safe_title = title.encode("ascii", "ignore").decode("ascii") or "PDF Digest"
    parts = split_utf8_text(message, 3500)

    failed_dir = BASE_DIR / "failed_pushes_pdf"
    failed_dir.mkdir(parents=True, exist_ok=True)

    for i, part in enumerate(parts, 1):
        t = safe_title if len(parts) == 1 else f"{safe_title} {i}/{len(parts)}"

        ok = False
        last_error = None

        for attempt in range(1, 4):
            try:
                r = requests.post(
                    f"{NTFY_SERVER.rstrip('/')}/{NTFY_TOPIC}",
                    data=part.encode("utf-8"),
                    headers={
                        "Title": t,
                        "Tags": "books",
                        "Priority": "default",
                        **({"Click": click} if click else {}),
                    },
                    timeout=45,
                )
                r.raise_for_status()
                ok = True
                break
            except Exception as e:
                last_error = e
                print(f"PDF ntfy 推送失败，第 {attempt}/3 次：{e}")
                time.sleep(5 * attempt)

        if not ok:
            ts = int(time.time())
            fname = failed_dir / f"failed_pdf_{ts}_{i}_of_{len(parts)}.txt"
            with open(fname, "w", encoding="utf-8") as f:
                f.write(f"Title: {t}\n")
                f.write(f"Topic: {NTFY_TOPIC}\n")
                f.write(f"Error: {last_error}\n\n")
                f.write(part)

            print(f"PDF ntfy 推送最终失败，已保存到：{fname}")

        time.sleep(0.8)


def ai_summarize_pdf(paper, filename, text):
    api_key = os.environ.get("AI_API_KEY", "").strip()
    base_url = os.environ.get("AI_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    model = os.environ.get("AI_MODEL", "deepseek-chat")

    if not api_key:
        return f"未设置 AI_API_KEY，无法总结 PDF。\n文件：{filename}"

    content = text[:45000]

    prompt = f"""
你是严谨的科研论文阅读助理。下面是从 PDF 提取的论文正文片段，可能包含断行、页眉页脚和格式错误。

必须基于 PDF 文本总结，不能编造。

已匹配的 RSS 论文信息：
题目：{paper.get("title", "")}
期刊/来源：{paper.get("feed", "")}
DOI：{paper.get("doi", "") or "未提供"}
一作：{paper.get("first_author", "") or "未提供"}
原文链接：{paper.get("link", "")}

要求：
1. 判断是否提取到了可用正文。如果是乱码、目录、参考文献或页眉页脚为主，必须说明无法可靠总结。
2. 输出开头必须包含下面两行，供手机短推送提取：
   中文题目：请把英文题目准确翻译成中文
   中文关键词：3-6个中文关键词，用顿号分隔
3. 输出控制在约 1200-1800 个汉字，适合手机阅读。
4. 必须包含：
   - 论文主题
   - 研究背景和科学问题
   - 研究对象/样品/数据
   - 方法与技术路线
   - 主要结果
   - 作者结论
   - 创新点
   - 局限性或仍不清楚的问题
   - 是否值得精读
5. 只列一作和通讯作者。通讯作者如果 PDF 文本中不能明确识别，写“未识别”。
6. 如果是 Reply、Comment、Commentary、Correspondence 或 Letter，重点总结回应/评论对象、核心争议、作者立场和关键论据。

PDF 文件名：{filename}

PDF 提取文本：
{content}
""".strip()

    payload = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": "你是严谨的科研论文阅读助理。必须基于PDF文本总结，不能编造。"
            },
            {
                "role": "user",
                "content": prompt
            }
        ],
        "temperature": 0.1,
    }

    r = requests.post(
        f"{base_url}/chat/completions",
        headers={
            "Authorization": "Bearer " + api_key,
            "Content-Type": "application/json",
        },
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        timeout=120,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


# -----------------------------
# Inbox helpers (shared with rss_ai_push)
# -----------------------------

def sanitize_filename(name):
    name = name or "untitled"
    name = re.sub(r'[\\/:*?"<>|]', '_', name)
    name = name.replace(' ', '_')
    name = re.sub(r'_{2,}', '_', name)
    name = name.strip('_.')
    if len(name) > 80:
        name = name[:80]
    if not name:
        name = "untitled"
    return name


def save_html(title, content, pdf_path=None):
    INBOX_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_title = sanitize_filename(title)
    filename = f"{timestamp}_{safe_title}.html"
    filepath = INBOX_DIR / filename

    escaped_title = html.escape(title or "无标题")
    escaped_content = html.escape(content or "")
    created = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    original_link = ""
    m = re.search(r"https?://\S+", content or "")
    if m:
        original_link = m.group(0).rstrip("。,.，)）]】")
    escaped_link = html.escape(original_link)

    link_html = ""
    if original_link:
        link_html = '<a class="btn secondary" href="' + escaped_link + '">打开原文链接</a>'

    pdf_meta = ""
    if pdf_path:
        pdf_meta = '<meta name="pdf-file" content="' + html.escape(str(pdf_path)) + '">'

    html_body = '''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="digest-source" content="pdf">
__PDF_META__
<title>__TITLE__</title>
<style>
:root {
  color-scheme: light dark;
  --bg: #f6f7f9;
  --card: #ffffff;
  --text: #15171a;
  --muted: #6b7280;
  --border: #e5e7eb;
  --accent: #2563eb;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #111827;
    --card: #1f2937;
    --text: #f9fafb;
    --muted: #9ca3af;
    --border: #374151;
    --accent: #60a5fa;
  }
}
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  padding: 14px;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans CJK SC", "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 17px;
  line-height: 1.78;
}
.container {
  max-width: 820px;
  margin: 0 auto;
}
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 18px;
  box-shadow: 0 4px 18px rgba(0,0,0,.06);
}
h1 {
  font-size: 22px;
  line-height: 1.35;
  margin: 0 0 10px 0;
}
.meta {
  color: var(--muted);
  font-size: 14px;
  margin-bottom: 14px;
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 14px 0;
}
.btn {
  display: inline-block;
  padding: 9px 12px;
  border-radius: 999px;
  background: var(--accent);
  color: white;
  text-decoration: none;
  font-size: 14px;
}
.btn.secondary {
  background: transparent;
  color: var(--accent);
  border: 1px solid var(--accent);
}
</style>
</head>
<body>
<div class="container">
  <article class="card">
    <h1>__TITLE__</h1>
    <div class="meta">保存时间：__CREATED__</div>
    <div class="actions">
      <a class="btn" href="index.html">返回列表</a>
      __LINK_HTML__
    </div>
    <div class="content">__CONTENT__</div>
  </article>
</div>
</body>
</html>
'''
    html_body = (
        html_body
        .replace("__TITLE__", escaped_title)
        .replace("__PDF_META__", pdf_meta)
        .replace("__CREATED__", html.escape(created))
        .replace("__LINK_HTML__", link_html)
        .replace("__CONTENT__", escaped_content)
    )

    filepath.write_text(html_body, encoding="utf-8")
    return filename, timestamp


def update_index(filename, display_text):
    INBOX_DIR.mkdir(parents=True, exist_ok=True)

    entry = (
        '<article class="item">\\n'
        '  <a href="' + html.escape(filename) + '">' + display_text + '</a>\\n'
        '</article>\\n'
    )

    template = '''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>SciToday Inbox</title>
<style>
:root {
  color-scheme: light dark;
  --bg: #f6f7f9;
  --card: #ffffff;
  --text: #15171a;
  --muted: #6b7280;
  --border: #e5e7eb;
  --accent: #2563eb;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #111827;
    --card: #1f2937;
    --text: #f9fafb;
    --muted: #9ca3af;
    --border: #374151;
    --accent: #60a5fa;
  }
}
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  padding: 14px;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans CJK SC", "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 17px;
  line-height: 1.65;
}
.container {
  max-width: 820px;
  margin: 0 auto;
}
h1 {
  font-size: 24px;
  margin: 4px 0 12px;
}
.sub {
  color: var(--muted);
  font-size: 14px;
  margin-bottom: 14px;
}
.item {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 14px 16px;
  margin: 10px 0;
  box-shadow: 0 3px 12px rgba(0,0,0,.05);
}
.item a {
  color: var(--text);
  text-decoration: none;
  font-weight: 650;
  word-break: break-word;
}
.item a:visited {
  color: var(--muted);
}
</style>
</head>
<body>
<div class="container">
<h1>SciToday Inbox</h1>
<div class="sub">最新论文总结在最上方。点击标题查看手机阅读版全文。</div>
<!-- ITEMS -->
</div>
</body>
</html>
'''

    if not INDEX_HTML.exists() or "<!-- ITEMS -->" not in INDEX_HTML.read_text(encoding="utf-8", errors="replace"):
        INDEX_HTML.write_text(template, encoding="utf-8")

    text = INDEX_HTML.read_text(encoding="utf-8", errors="replace")
    text = text.replace("<!-- ITEMS -->", "<!-- ITEMS -->\\n" + entry, 1)
    INDEX_HTML.write_text(text, encoding="utf-8")


def extract_line_value(text, labels):
    text = text or ""
    for label in labels:
        m = re.search(rf"^{re.escape(label)}\\s*[:：]\\s*(.+)$", text, flags=re.M)
        if m:
            value = m.group(1).strip()
            value = re.sub(r"^[【\\[]|[】\\]]$", "", value).strip()
            if value and value not in ("未提供", "无", "无。"):
                return value
    return ""


def local_file_click_uri(filename):
    from urllib.parse import quote
    local_path = str(INBOX_DIR / filename)
    return "file://" + quote(local_path, safe="/:._-")


def has_cjk(text):
    return bool(re.search(r"[\u4e00-\u9fff]", text or ""))


def ai_short_meta(title, digest=""):
    """
    Generate Chinese title and Chinese keywords for short notification.
    Return (cn_title, keywords). Never raise.
    """
    api_key = os.environ.get("AI_API_KEY", "").strip()
    base_url = os.environ.get("AI_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    model = os.environ.get("AI_MODEL", "deepseek-chat")

    if not api_key:
        return "", ""

    if not base_url.endswith("/v1"):
        base_url = base_url + "/v1"

    prompt = f"""
请根据下面论文信息生成手机推送用的中文题目和中文关键词。

要求：
1. 必须把英文题目翻译成中文，不要照抄英文。
2. 中文关键词给 3-6 个，用顿号分隔。
3. 只返回 JSON，不要解释，不要 Markdown。
4. JSON 格式必须是：
{{"中文题目":"...","中文关键词":"..."}}

英文题目：
{title}

已有摘要：
{(digest or "")[:1200]}
""".strip()

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "你只输出严格 JSON。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.1,
    }

    try:
        r = requests.post(
            base_url + "/chat/completions",
            headers={
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json",
                "Connection": "close",
            },
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            timeout=60,
        )
        r.raise_for_status()
        text = r.json()["choices"][0]["message"]["content"].strip()
        text = re.sub(r"^```json\s*|\s*```$", "", text, flags=re.I | re.S).strip()
        data = json.loads(text)
        cn_title = str(data.get("中文题目", "")).strip()
        keywords = str(data.get("中文关键词", "")).strip()
        return cn_title, keywords
    except Exception as e:
        print("短推送中文题目/关键词生成失败：", e)
        return "", ""


def short_clip(text, n):
    text = clean_text(text) if "clean_text" in globals() else clean(text, n * 2)
    if len(text) > n:
        return text[:n].rstrip() + "..."
    return text


def local_file_click_uri(filename):
    """
    ntfy/Android often blocks file:// links.
    Use local HTTP server instead:
    http://127.0.0.1:8765/<html>
    """
    from urllib.parse import quote
    return "http://127.0.0.1:8765/" + quote(filename, safe="")


def make_short_push(title, digest, filename, prefix="新论文总结已保存"):
    cn_title = extract_line_value(digest, ["中文题目", "题目中文翻译", "中文标题"])
    keywords = extract_line_value(digest, ["中文关键词", "关键词"])

    need_ai = False
    if not cn_title or cn_title.strip() == (title or "").strip() or not has_cjk(cn_title):
        need_ai = True
    if not keywords or keywords in ("未提取", "未提供", "无"):
        need_ai = True

    if need_ai:
        ai_cn_title, ai_keywords = ai_short_meta(title, digest)
        if ai_cn_title and has_cjk(ai_cn_title):
            cn_title = ai_cn_title
        if ai_keywords:
            keywords = ai_keywords

    if not cn_title:
        cn_title = title or "未提取"
    if not keywords:
        keywords = "未提取"

    local_path = str(INBOX_DIR / filename)
    click_url = local_file_click_uri(filename)

    lines = [
        prefix,
        "中文题目：" + short_clip(cn_title, 120),
        "中文关键词：" + short_clip(keywords, 80),
        "英文题目：" + short_clip(title or "", 160),
        "HTML阅读：" + click_url,
        "本地路径：" + local_path,
    ]

    # 这里必须是真换行，不是反斜杠+n
    msg = "\n".join(lines)
    return msg, click_url


def retry_failed_pushes_pdf(max_files=10):
    """
    自动重发 PDF watcher 的失败推送。
    成功后移动到 failed_pushes_pdf/sent。
    失败则保留，下次继续尝试。
    """
    failed_dir = BASE_DIR / "failed_pushes_pdf"
    sent_dir = failed_dir / "sent"
    failed_dir.mkdir(parents=True, exist_ok=True)
    sent_dir.mkdir(parents=True, exist_ok=True)

    files = sorted(
        [f for f in failed_dir.glob("*.txt")],
        key=lambda x: x.stat().st_mtime
    )

    if not files:
        return

    print(f"发现 {len(files)} 个 PDF 失败推送文件，准备重发前 {min(len(files), max_files)} 个。")

    for path in files[:max_files]:
        try:
            raw = path.read_text(encoding="utf-8", errors="replace")

            title = "Retry PDF Push"
            lines = raw.splitlines()
            body_start = 0

            for idx, line in enumerate(lines):
                if line.lower().startswith("title:"):
                    title = line.split(":", 1)[1].strip() or title
                if line.strip() == "":
                    body_start = idx + 1
                    break

            body = "\n".join(lines[body_start:]).strip()
            if not body:
                body = raw

            safe_title = title.encode("ascii", "ignore").decode("ascii") or "Retry PDF Push"
            parts = split_utf8_text(body, 3500)

            for i, part in enumerate(parts, 1):
                t = safe_title if len(parts) == 1 else f"{safe_title} {i}/{len(parts)}"

                r = requests.post(
                    f"{NTFY_SERVER.rstrip('/')}/{NTFY_TOPIC}",
                    data=part.encode("utf-8"),
                    headers={
                        "Title": t,
                        "Tags": "repeat",
                        "Priority": "default",
                    },
                    timeout=45,
                )
                r.raise_for_status()
                time.sleep(0.8)

            path.replace(sent_dir / path.name)
            print(f"PDF 失败推送重发成功：{path.name}")

        except Exception as e:
            print(f"PDF 失败推送重发仍失败：{path.name} | {e}")
            continue


def filename_might_match(pdf_name, pending_papers):
    """检查文件名是否可能匹配待匹配论文"""
    name_lower = pdf_name.lower().replace('-', ' ').replace('_', ' ')
    for paper in pending_papers:
        title = (paper.get("title") or "").lower()
        # 提取标题中的关键词（长度>=4的单词）
        words = [w for w in title.split() if len(w) >= 4]
        # 如果文件名包含标题中的多个关键词，可能匹配
        matches = sum(1 for w in words if w in name_lower)
        if matches >= 2:
            return True
    return False


def scan_once():
    pending = load_pending(days=21)

    if not pending:
        print("没有待匹配论文，跳过 PDF 扫描。")
        return

    print(f"待匹配论文数量：{len(pending)}")

    pdfs = []
    for d in DOWNLOAD_DIRS:
        if d.exists():
            pdfs.extend(d.glob("*.pdf"))

    pdfs = sorted(
        pdfs,
        key=lambda p: p.stat().st_mtime,
        reverse=True
    )

    for pdf in pdfs:
        # 只看最近 3 天下载的 PDF
        if time.time() - pdf.stat().st_mtime > 3 * 86400:
            continue

        # 新增：文件名预匹配，跳过不相关的PDF
        if not filename_might_match(pdf.name, pending):
            continue

        if not is_stable_file(pdf):
            continue

        h = file_hash(pdf)

        if pdf_already_done(h):
            continue

        print(f"检查 PDF：{pdf.name}")

        try:
            text = extract_pdf_text(pdf, max_pages=25)
        except Exception as e:
            print(f"无法读取 PDF：{pdf.name} | {e}")
            mark_pdf(h, pdf, "", "read_failed")
            continue

        if len(text) < 1000:
            print(f"PDF 文本过少，忽略：{pdf.name}")
            mark_pdf(h, pdf, "", "too_little_text")
            continue

        paper, score, reason = match_pdf_to_pending(text, pending)

        if not paper:
            print(f"未匹配待处理论文，忽略并记录：{pdf.name} | best={score:.2f} {reason}")
            mark_pdf(h, pdf, "", "unmatched")
            continue

        print(f"匹配成功：{pdf.name} -> {paper.get('title')} | {reason}")

        try:
            msg = ai_summarize_pdf(paper, pdf.name, text)

            title = paper.get("title", "无标题")
            filename, timestamp = save_html(title, msg, pdf_path=str(pdf))
            display_text = f"{timestamp} {html.escape(title)}"
            update_index(filename, display_text)

            short_msg, html_click = make_short_push(title, msg, filename, prefix="PDF全文总结已保存")
            send_ntfy(short_msg, title="PDF Digest Saved", click=html_click)

            mark_pdf(h, pdf, paper["id"], "processed")
            mark_pending_processed(paper["id"])
            print(f"已总结并保存：{pdf.name}")

        except Exception as e:
            err = f"PDF 全文总结失败：{pdf.name}\n匹配论文：{paper.get('title')}\n错误：{e}"
            print(err)
            send_ntfy(err, title="PDF Digest Error")
            mark_pdf(h, pdf, paper["id"], "error")


def main():
    retry_failed_pushes_pdf()
    scan_once()


if __name__ == "__main__":
    main()
