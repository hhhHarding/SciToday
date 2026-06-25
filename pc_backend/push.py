import subprocess
import logging
from urllib.parse import quote

logger = logging.getLogger(__name__)


def send_notification(title, message, url=None):
    try:
        cmd = ["termux-notification", "--title", title, "--content", message[:500]]
        if url:
            cmd.extend(["--action", f"am start -a android.intent.action.VIEW -d '{url}'"])
        subprocess.run(cmd, check=False, timeout=10)
        logger.info(f"系统通知已发送: {title}")
    except FileNotFoundError:
        logger.warning("termux-notification 不可用，跳过系统通知")
    except Exception as e:
        logger.error(f"系统通知发送失败: {e}")


def send_digest_notification(cn_title, keywords, filename):
    msg = f"中文题目: {cn_title}\n关键词: {keywords}"
    # 通过 App 的 deep link 打开，由 App 用本机 Flask（/inbox）加载摘要。
    # 此前用 file:// 直接指向本地 html，会被浏览器/系统拦截，点击后显示 NOT FOUND。
    # 文件名含中文等字符，必须 URL 编码后才能放进 URI。
    url = f"rssaipush://digest/{quote(filename, safe='')}"
    send_notification("新论文总结", msg, url)


def send_pdf_notification(cn_title, keywords, filename):
    msg = f"中文题目: {cn_title}\n关键词: {keywords}"
    url = f"rssaipush://reading/{quote(filename, safe='')}"
    send_notification("PDF全文总结", msg, url)

