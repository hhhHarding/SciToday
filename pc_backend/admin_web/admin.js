const state = {
  view: "dashboard",
  token: localStorage.getItem("rssaiToken") || "",
  currentDigest: null,
};

const initialParams = new URLSearchParams(location.search);
if (initialParams.get("token")) {
  state.token = initialParams.get("token").trim();
  localStorage.setItem("rssaiToken", state.token);
  initialParams.delete("token");
  const clean = `${location.pathname}${initialParams.toString() ? "?" + initialParams.toString() : ""}${location.hash}`;
  history.replaceState(null, "", clean);
}

const titles = {
  dashboard: ["总览", "PC 后端、本地数据库、App 心跳和 Tunnel 状态"],
  messages: ["RSS 消息", "查看摘要、手动刷新、打开详情和提问"],
  reading: ["PDF 阅读", "查看 PDF 总结、PDF 原文和问答"],
  feeds: ["RSS 源", "管理 OPML 中的 RSS 源"],
  settings: ["设置", "后端、AI、RSS、调度和 Tunnel 配置"],
  monitor: ["监控", "检查 App -> Tunnel -> Flask -> DB -> RSS/AI/PDF/Inbox 链路"],
  logs: ["日志", "后端 server.log 尾部"],
};

function h(value) {
  return String(value ?? "").replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function api(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (!(options.body instanceof FormData)) headers["Content-Type"] = "application/json";
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  return fetch(path, Object.assign({}, options, {headers, credentials: "same-origin"})).then(async r => {
    if (r.status === 401) {
      document.getElementById("loginPanel").classList.remove("hidden");
      throw new Error("unauthorized");
    }
    if (!r.ok) throw new Error((await r.text()) || r.statusText);
    return r.json();
  });
}

function setView(view) {
  state.view = view;
  document.querySelectorAll(".nav").forEach(b => b.classList.toggle("active", b.dataset.view === view));
  document.querySelectorAll(".view").forEach(v => v.classList.toggle("hidden", v.id !== view));
  document.getElementById("pageTitle").textContent = titles[view][0];
  document.getElementById("subtitle").textContent = titles[view][1];
  refresh();
}

function card(title, body) {
  return `<div class="card"><h2>${h(title)}</h2>${body}</div>`;
}

function copyField(label, value, placeholder = "未生成") {
  const display = value || placeholder;
  return `<label class="copyField">${h(label)}
    <div class="row">
      <input readonly value="${h(display)}">
      <button data-copy="${h(value || "")}" ${value ? "" : "disabled"}>复制</button>
    </div>
  </label>`;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function splitDirs(value) {
  return String(value || "")
    .split(/[\n;]/)
    .map(v => v.trim())
    .filter(Boolean);
}

async function renderDashboard() {
  const o = await api("/api/admin/overview");
  const s = o.status || {};
  const app = o.app || {};
  const tunnel = o.tunnel || {};
  const server = o.server || {};
  const tunnelUrl = tunnel.current_url || (tunnel.quick || {}).url || tunnel.configured_url || "";
  const authToken = server.auth_token || "";
  document.getElementById("dashboard").innerHTML = `
    ${card("App 连接信息", `
      <div class="grid compact">
        ${copyField("当前 Quick Tunnel URL", tunnelUrl, "Quick Tunnel 启动中，稍后刷新")}
        ${copyField("后端访问 Token", authToken, "未启用 token")}
      </div>
      <div class="toolbar inlineToolbar">
        <button id="refreshTunnelBtn" class="primary">刷新 Quick Tunnel URL</button>
        <span id="tunnelRefreshStatus" class="label">${h((tunnel.quick || {}).status || "")}</span>
      </div>
      <div class="label">手机 App 的 PC 后端地址填当前 URL，Token 填上面的后端访问 Token。</div>
    `)}
    <div class="grid">
      ${card("RSS 源", `<div class="stat">${s.feeds_count || 0}</div><div class="label">discovery ${s.rss_discovery_interval || "-"} 分钟 / publish ${s.rss_interval || "-"} 分钟</div>`)}
      ${card("摘要", `<div class="stat">${s.inbox_summaries || 0}</div><div class="label">RSS + PDF</div>`)}
      ${card("RSS 队列", `<div class="stat">${(s.rss_queue || {}).pending || 0}</div><div class="label">待发布</div>`)}
      ${card("App", `<div class="stat ${app.online ? "ok" : "warn"}">${app.online ? "在线" : "离线"}</div><div class="label">${h(app.last_seen || "无心跳")}</div>`)}
      ${card("Tunnel", `<div class="stat ${tunnel.process_running ? "ok" : "warn"}">${tunnel.process_running ? "运行" : "未运行"}</div><div class="label">${h(tunnelUrl || "未生成 URL")}</div>`)}
      ${card("Flask", `<div class="stat ${server.listening ? "ok" : "bad"}">${server.listening ? "监听中" : "未监听"}</div><div class="label">${h(server.local_url || "")}</div>`)}
    </div>
    ${card("任务进度", `<pre>${h(JSON.stringify(o.progress || {}, null, 2))}</pre>`)}
    ${card("最近事件", renderEvents(o.recent_events || []))}
  `;
}

function renderEvents(events) {
  if (!events.length) return `<p>暂无事件</p>`;
  return `<div class="list">${events.map(e => `<div class="item">
    <span class="badge ${e.level === "error" ? "bad" : e.level === "warning" ? "warn" : "ok"}">${h(e.level)}</span>
    <span class="meta">${h(e.time)} · ${h(e.type)}</span>
    <div class="itemTitle">${h(e.message)}</div>
  </div>`).join("")}</div>`;
}

function statusClass(status) {
  if (status === "published" || status === "processed" || status === "ok") return "ok";
  if (status === "pending" || status === "unmatched" || status === "too_little_text") return "warn";
  return status ? "bad" : "";
}

function renderProgress(progress, keys) {
  const cards = keys.map(k => {
    const p = (progress || {})[k] || {};
    const total = Number(p.total || 0);
    const current = Number(p.current || 0);
    const count = total ? `${current}/${total}` : (p.active ? "运行中" : "空闲");
    return card(k, `<div class="stat ${p.active ? "warn" : "ok"}">${h(count)}</div><div class="label">${h(p.message || (p.active ? "运行中" : "空闲"))}</div>`);
  }).join("");
  return `<div class="grid">${cards}</div>`;
}

function renderRssQueue(queue) {
  const stats = queue.stats || {};
  const rows = (queue.items || []).map(item => `<tr>
    <td><div class="itemTitle">${h(item.title || "无标题")}</div><div class="meta">${h(item.feed)} ${item.doi ? " · DOI " + h(item.doi) : ""}</div></td>
    <td><span class="badge ${statusClass(item.status)}">${h(item.status)}</span></td>
    <td>${h(item.created)}</td>
    <td>${h(item.published)}</td>
    <td>${item.link ? `<a href="${h(item.link)}" target="_blank">打开</a>` : ""}</td>
    <td>${h(item.error)}</td>
  </tr>`).join("");
  return `
    <div class="grid">
      ${card("待发布", `<div class="stat">${stats.pending || 0}</div><div class="label">pending</div>`)}
      ${card("已发布", `<div class="stat">${stats.published || 0}</div><div class="label">published</div>`)}
      ${card("失败", `<div class="stat ${stats.error ? "bad" : ""}">${stats.error || 0}</div><div class="label">error</div>`)}
    </div>
    ${card("RSS 队列明细", `<div class="tableWrap"><table><thead><tr><th>论文</th><th>状态</th><th>入队</th><th>发布</th><th>链接</th><th>错误</th></tr></thead><tbody>${rows || `<tr><td colspan="6">暂无队列记录</td></tr>`}</tbody></table></div>`)}
  `;
}

function renderPdfQueue(queue) {
  const stats = queue.stats || {};
  const seen = stats.pdf_seen || {};
  const pendingRows = (queue.pending || []).map(p => `<tr>
    <td><div class="itemTitle">${h(p.title || "无标题")}</div><div class="meta">${h(p.feed)} ${p.doi ? " · DOI " + h(p.doi) : ""}</div></td>
    <td>${h(p.first_author)}</td>
    <td>${h(p.created)}</td>
    <td>${p.link ? `<a href="${h(p.link)}" target="_blank">打开</a>` : ""}</td>
  </tr>`).join("");
  const recentRows = (queue.recent || []).map(p => `<tr>
    <td><div class="itemTitle">${h(p.filename || p.path)}</div><div class="meta">${h(p.matched_title)}</div></td>
    <td><span class="badge ${statusClass(p.status)}">${h(p.status)}</span></td>
    <td>${h(p.time)}</td>
    <td>${h(p.path)}</td>
  </tr>`).join("");
  const dirRows = (queue.download_dirs || []).map(d => `<tr>
    <td>${h(d.path)}</td>
    <td class="${d.exists ? "ok" : "warn"}">${d.exists ? "存在" : "不存在"}</td>
    <td>${h(d.pdf_count)}</td>
  </tr>`).join("");
  return `
    <div class="grid">
      ${card("待匹配论文", `<div class="stat">${stats.pending_total || 0}</div><div class="label">pending_papers</div>`)}
      ${card("扫描 PDF", `<div class="stat">${stats.pdf_files || 0}</div><div class="label">下载目录 + 上传目录</div>`)}
      ${card("PDF 记录", `<div class="stat">${seen.total || 0}</div><div class="label">processed ${seen.processed || 0} / error ${seen.error || 0}</div>`)}
    </div>
    ${card("待匹配论文", `<div class="tableWrap"><table><thead><tr><th>论文</th><th>一作</th><th>入库</th><th>链接</th></tr></thead><tbody>${pendingRows || `<tr><td colspan="4">暂无待匹配论文</td></tr>`}</tbody></table></div>`)}
    ${card("最近 PDF 处理", `<div class="tableWrap"><table><thead><tr><th>PDF</th><th>状态</th><th>时间</th><th>路径</th></tr></thead><tbody>${recentRows || `<tr><td colspan="4">暂无 PDF 处理记录</td></tr>`}</tbody></table></div>`)}
    ${card("PDF 扫描目录", `<div class="tableWrap"><table><thead><tr><th>路径</th><th>状态</th><th>PDF 数</th></tr></thead><tbody>${dirRows || `<tr><td colspan="3">暂无目录</td></tr>`}</tbody></table></div>`)}
  `;
}

async function renderDigests(target, source) {
  const list = await api(`/api/digests?limit=100&source=${encodeURIComponent(source)}`);
  const [overview, queue, events] = await Promise.all([
    api("/api/admin/overview"),
    source === "rss" ? api("/api/admin/rss-queue?limit=100") : api("/api/admin/pdf-queue?limit=100"),
    source === "rss" ? api("/api/admin/events?limit=30") : Promise.resolve([]),
  ]);
  const progressKeys = source === "rss" ? ["rss", "rss_discovery", "rss_publish"] : ["pdf"];
  const queueHtml = source === "rss" ? renderRssQueue(queue) : renderPdfQueue(queue);
  document.getElementById(target).innerHTML = `
    <div class="toolbar">
      <button class="primary" data-action="${source === "rss" ? "runRss" : "runPdf"}">${source === "rss" ? "立即RSS刷新" : "立即PDF刷新"}</button>
      ${source === "rss" ? `<button data-action="discovery">只发现入队</button><button data-action="publish">只发布队列</button>` : ""}
    </div>
    ${renderProgress(overview.progress || {}, progressKeys)}
    ${queueHtml}
    <div class="list">${list.map(d => `<div class="item">
      <div class="itemTitle">${h(d.cn_title || d.title)}</div>
      <div class="meta">${h(d.timestamp)} · ${h(d.journal)} · ${h(d.keywords)}</div>
      <p>${h(d.preview)}</p>
      <button data-open="${h(d.filename)}" data-title="${h(d.title)}">打开</button>
      ${source === "pdf" ? `<a class="badge" href="/api/pdf?filename=${encodeURIComponent(d.filename)}" target="_blank">PDF原文</a>` : ""}
    </div>`).join("") || `<div class="panel">暂无消息</div>`}</div>`;
  if (source === "rss") {
    document.getElementById(target).innerHTML += card("最近 RSS 事件", renderEvents(events));
  }
}

async function renderFeeds() {
  const [feeds, cfg] = await Promise.all([api("/api/feeds"), api("/api/admin/settings")]);
  const opmlPath = ((cfg.rss || {}).opml_path) || "";
  document.getElementById("feeds").innerHTML = `
    ${card("RSS 源同步", `
      <div class="grid">
        <label>当前 OPML<input readonly value="${h(opmlPath)}"></label>
        <label>源数量<input readonly value="${h(feeds.length)}"></label>
      </div>
      <div class="row importRow">
        <input id="opmlImportFile" type="file" accept=".opml,.xml,text/xml">
        <button id="importOpmlBtn">导入 OPML</button>
      </div>
    `)}
    <div class="panel">
      <h2>添加 RSS 源</h2>
      <div class="row"><input id="feedTitle" placeholder="名称"><input id="feedUrl" placeholder="RSS URL"><button id="addFeedBtn">添加</button></div>
    </div>
    <div class="tableWrap"><table><thead><tr><th>名称</th><th>URL</th><th></th></tr></thead><tbody>
      ${feeds.map(f => `<tr><td>${h(f.title)}</td><td>${h(f.url)}</td><td><button class="danger" data-delete-feed="${encodeURIComponent(f.url)}">删除</button></td></tr>`).join("") || `<tr><td colspan="3">暂无 RSS 源</td></tr>`}
    </tbody></table></div>`;
}

async function renderSettings() {
  const [cfg, local] = await Promise.all([
    api("/api/admin/settings"),
    api("/api/admin/local-settings"),
  ]);
  const pc = cfg.pc || {};
  const server = cfg.server || {};
  const rss = cfg.rss || {};
  const ai = cfg.ai || {};
  const tray = (local || {}).tray || {};
  const startup = (local || {}).startup || {};
  const quickTunnel = pc.quick_tunnel || {};
  const currentUrl = pc.current_tunnel_url || quickTunnel.url || "";
  const authToken = server.auth_token || "";
  const localHost = tray.host || server.effective_host || server.host || "127.0.0.1";
  const localPort = tray.port || server.effective_port || server.port || 5200;
  const localDataDir = tray.data_dir || pc.data_dir || "";
  const localDownloadDirs = (tray.download_dirs || pc.download_dirs || []).join("\n");
  document.getElementById("settings").innerHTML = `
    <div class="panel">
      <h2>调度</h2>
      <div class="grid">
        <label>RSS discovery 间隔(分)<input id="rssDiscoveryInterval" type="number" value="${h((cfg.schedule || {}).rss_discovery_interval_minutes || 30)}"></label>
        <label>RSS publish 间隔(分)<input id="rssInterval" type="number" value="${h((cfg.schedule || {}).rss_interval_minutes || 30)}"></label>
        <label>PDF 间隔(分)<input id="pdfInterval" type="number" value="${h((cfg.schedule || {}).pdf_interval_minutes || 5)}"></label>
        <label>启用<select id="scheduleEnabled"><option value="true">true</option><option value="false">false</option></select></label>
      </div>
    </div>
    <div class="panel">
      <h2>RSS</h2>
      <div class="grid">
        <label>OPML 路径<input id="rssOpmlPath" value="${h(rss.opml_path || "")}"></label>
        <label>每源抓取上限<input id="perFeedLimit" type="number" min="1" value="${h(rss.per_feed_limit || 3)}"></label>
        <label>每轮发布上限<input id="maxPushItems" type="number" min="1" value="${h(rss.max_push_items || 20)}"></label>
      </div>
    </div>
    <div class="panel">
      <h2>AI</h2>
      <div class="grid">
        <label>Base URL<input id="aiBaseUrl" value="${h(ai.base_url || "")}"></label>
        <label>Model<input id="aiModel" value="${h(ai.model || "")}"></label>
        <label>API Key<input id="aiKey" type="password" value="${h(ai.api_key || "")}"></label>
      </div>
      <label>System Prompt<textarea id="systemPrompt" rows="3">${h(ai.system_prompt || "")}</textarea></label>
      <label>RSS Prompt<textarea id="rssPrompt" rows="5">${h(ai.rss_prompt || "")}</textarea></label>
      <label>PDF Prompt<textarea id="pdfPrompt" rows="5">${h(ai.pdf_prompt || "")}</textarea></label>
    </div>
    <div class="panel">
      <h2>Server</h2>
      <div class="grid">
        <label>配置 Host<input id="serverHost" value="${h(localHost)}"></label>
        <label>配置 Port<input id="serverPort" type="number" value="${h(localPort)}"></label>
        <label>Effective URL<input readonly value="${h(server.effective_local_url || "")}"></label>
        <label>访问 Token<input id="serverAuthToken" type="password" value="${h(authToken)}"></label>
      </div>
    </div>
    <div class="panel">
      <h2>本地后台</h2>
      <div class="grid">
        <label class="checkRow"><input id="startupEnabled" type="checkbox" ${startup.enabled ? "checked" : ""}>开机自启</label>
        <label>安装目录<input readonly value="${h(local.install_dir || pc.install_dir || "")}"></label>
        <label>数据目录<input id="localDataDir" value="${h(localDataDir)}"></label>
        <label>托盘配置<input readonly value="${h(local.tray_config_path || "")}"></label>
        <label>命令文件<input readonly value="${h(local.tray_command_path || pc.tray_command_path || "")}"></label>
      </div>
      <label>PDF 下载/扫描目录<textarea id="localDownloadDirs" rows="3">${h(localDownloadDirs)}</textarea></label>
      <div class="label">Host、Port、数据目录和下载目录保存后，需要重启后台才会完全生效。</div>
    </div>
    <div class="panel">
      <h2>PC / Quick Tunnel</h2>
      <div class="grid">
        ${copyField("当前 Quick Tunnel URL", currentUrl, "Quick Tunnel 启动中，稍后刷新")}
        ${copyField("后端访问 Token", authToken, "未启用 token")}
        <label>数据目录<input readonly value="${h(pc.data_dir || "")}"></label>
        <label>配置文件<input readonly value="${h(pc.config_path || "")}"></label>
        <label>Inbox<input readonly value="${h(pc.inbox_dir || "")}"></label>
        <label>PDF 上传目录<input readonly value="${h(pc.uploaded_pdf_dir || "")}"></label>
        <label>状态文件<input readonly value="${h(pc.quick_tunnel_state_path || "")}"></label>
      </div>
      <label>PDF 扫描目录<textarea readonly rows="3">${h((pc.download_dirs || []).join("\n"))}</textarea></label>
      <pre>${h(JSON.stringify(quickTunnel, null, 2))}</pre>
    </div>
    <button id="saveSettingsBtn" class="primary">保存设置</button>`;
  const enabled = String((cfg.schedule || {}).enabled ?? true);
  document.getElementById("scheduleEnabled").value = enabled;
}

async function renderMonitor() {
  const [o, feeds, events] = await Promise.all([
    api("/api/admin/overview"),
    api("/api/admin/feed-health"),
    api("/api/admin/events?limit=100"),
  ]);
  const tunnelUrl = o.tunnel.current_url || ((o.tunnel.quick || {}).url) || o.tunnel.configured_url || "";
  const pathRows = Object.entries(o.paths || {}).map(([k, v]) =>
    `<tr><td>${h(k)}</td><td>${h(v.path)}</td><td class="${v.exists ? "ok" : "warn"}">${v.exists ? "存在" : "不存在"}</td><td class="${v.writable ? "ok" : "bad"}">${v.writable ? "可写" : "不可写"}</td></tr>`
  ).join("");
  document.getElementById("monitor").innerHTML = `
    <div class="grid">
      ${card("App -> Tunnel", `<div class="${o.app.online ? "ok" : "warn"}">${o.app.online ? "App 最近在线" : "App 心跳过期或不存在"}</div><pre>${h(JSON.stringify(o.app.payload || {}, null, 2))}</pre>`)}
      ${card("Tunnel -> Flask", `<div class="${o.tunnel.process_running ? "ok" : "warn"}">Quick Tunnel: ${o.tunnel.process_running ? "运行" : "未连接"}</div><div>${h(tunnelUrl || "Quick Tunnel URL 未生成")}</div>${o.tunnel.cloudflared_process_present ? `<div class="label">检测到系统 cloudflared 进程/服务，可能是旧 MSI/Service。</div>` : ""}<pre>${h(JSON.stringify(o.tunnel.quick || {}, null, 2))}</pre>`)}
      ${card("Flask -> DB", `<div class="${o.server.listening ? "ok" : "bad"}">${h(o.server.local_url)} ${o.server.listening ? "可访问" : "不可访问"}</div>`)}
    </div>
    ${card("路径检查", `<table><thead><tr><th>项目</th><th>路径</th><th>存在</th><th>可写</th></tr></thead><tbody>${pathRows}</tbody></table>`)}
    ${card("RSS 源健康", `<table><thead><tr><th>源</th><th>状态</th><th>最近成功</th><th>错误</th></tr></thead><tbody>${feeds.map(f => `<tr><td>${h(f.title)}</td><td class="${f.status === "ok" ? "ok" : "bad"}">${h(f.status)}</td><td>${h(f.last_ok)}</td><td>${h(f.error)}</td></tr>`).join("")}</tbody></table>`)}
    ${card("事件", renderEvents(events))}
  `;
}

async function renderLogs() {
  const logs = await api("/api/logs?lines=400");
  document.getElementById("logs").innerHTML = `<pre>${h((logs || []).join("\n"))}</pre>`;
}

async function refresh() {
  try {
    if (state.view === "dashboard") await renderDashboard();
    if (state.view === "messages") await renderDigests("messages", "rss");
    if (state.view === "reading") await renderDigests("reading", "pdf");
    if (state.view === "feeds") await renderFeeds();
    if (state.view === "settings") await renderSettings();
    if (state.view === "monitor") await renderMonitor();
    if (state.view === "logs") await renderLogs();
  } catch (e) {
    if (e.message !== "unauthorized") console.error(e);
  }
}

async function openDigest(filename, title) {
  state.currentDigest = filename;
  document.getElementById("detailTitle").textContent = title || filename;
  document.getElementById("digestFrame").src = `/inbox/${encodeURIComponent(filename)}`;
  document.getElementById("chatOutput").textContent = "";
  document.getElementById("detailDialog").showModal();
}

async function refreshTunnelUrl() {
  const button = document.getElementById("refreshTunnelBtn");
  const status = document.getElementById("tunnelRefreshStatus");
  if (button) button.disabled = true;
  if (status) status.textContent = "正在请求刷新...";
  const before = await api("/api/admin/overview");
  const previousUrl = ((before.tunnel || {}).current_url) || "";
  await api("/api/admin/tunnel/refresh", {method: "POST", body: "{}"});

  for (let i = 0; i < 40; i += 1) {
    await sleep(1500);
    const overview = await api("/api/admin/overview");
    const tunnel = overview.tunnel || {};
    const url = tunnel.current_url || ((tunnel.quick || {}).url) || tunnel.configured_url || "";
    const tunnelStatus = (tunnel.quick || {}).status || "";
    if (status) status.textContent = tunnelStatus || "刷新中...";
    if (url && (url !== previousUrl || tunnelStatus === "connected")) {
      await renderDashboard();
      return;
    }
  }
  if (status) status.textContent = "仍在等待 Quick Tunnel 生成 URL";
  if (button) button.disabled = false;
}

document.addEventListener("click", async e => {
  const nav = e.target.closest(".nav");
  if (nav) setView(nav.dataset.view);
  if (e.target.id === "refreshTunnelBtn") await refreshTunnelUrl();
  if (e.target.id === "saveTokenBtn") {
    state.token = document.getElementById("tokenInput").value.trim();
    localStorage.setItem("rssaiToken", state.token);
    await api("/api/admin/session", {method: "POST", body: JSON.stringify({token: state.token})});
    document.getElementById("loginPanel").classList.add("hidden");
    refresh();
  }
  if (e.target.dataset.open) openDigest(e.target.dataset.open, e.target.dataset.title);
  if (e.target.dataset.action === "runRss") await api("/api/run/rss", {method: "POST", body: "{}"}).then(refresh);
  if (e.target.dataset.action === "runPdf") await api("/api/run/pdf", {method: "POST", body: "{}"}).then(refresh);
  if (e.target.dataset.action === "discovery") await api("/api/admin/run/rss-discovery", {method: "POST", body: "{}"}).then(refresh);
  if (e.target.dataset.action === "publish") await api("/api/admin/run/rss-publish", {method: "POST", body: "{}"}).then(refresh);
  if (e.target.id === "closeDetailBtn") document.getElementById("detailDialog").close();
  if (e.target.id === "chatSendBtn") {
    const text = document.getElementById("chatInput").value.trim();
    if (!text || !state.currentDigest) return;
    const out = document.getElementById("chatOutput");
    out.textContent = "思考中...";
    const r = await api("/api/chat", {method: "POST", body: JSON.stringify({filename: state.currentDigest, message: text, history: []})});
    out.textContent = r.reply || r.error || "";
  }
  if (e.target.id === "addFeedBtn") {
    await api("/api/feeds", {method: "POST", body: JSON.stringify({title: document.getElementById("feedTitle").value, url: document.getElementById("feedUrl").value})});
    renderFeeds();
  }
  if (e.target.id === "importOpmlBtn") {
    const input = document.getElementById("opmlImportFile");
    const file = input && input.files ? input.files[0] : null;
    if (!file) return;
    const form = new FormData();
    form.append("file", file);
    await api("/api/feeds/import", {method: "POST", body: form});
    renderFeeds();
  }
  if (e.target.dataset.deleteFeed) {
    await api(`/api/feeds/${e.target.dataset.deleteFeed}`, {method: "DELETE"});
    renderFeeds();
  }
  if (e.target.id === "saveSettingsBtn") {
    const body = {
      schedule: {
        rss_discovery_interval_minutes: Number(document.getElementById("rssDiscoveryInterval").value || 30),
        rss_interval_minutes: Number(document.getElementById("rssInterval").value || 30),
        pdf_interval_minutes: Number(document.getElementById("pdfInterval").value || 5),
        enabled: document.getElementById("scheduleEnabled").value === "true",
      },
      rss: {
        opml_path: document.getElementById("rssOpmlPath").value,
        per_feed_limit: Number(document.getElementById("perFeedLimit").value || 3),
        max_push_items: Number(document.getElementById("maxPushItems").value || 20),
      },
      ai: {
        api_key: document.getElementById("aiKey").value,
        base_url: document.getElementById("aiBaseUrl").value,
        model: document.getElementById("aiModel").value,
        system_prompt: document.getElementById("systemPrompt").value,
        rss_prompt: document.getElementById("rssPrompt").value,
        pdf_prompt: document.getElementById("pdfPrompt").value,
      },
      server: {
        host: document.getElementById("serverHost").value,
        port: Number(document.getElementById("serverPort").value || 5000),
        auth_token: document.getElementById("serverAuthToken").value,
      }
    };
    await api("/api/admin/settings", {method: "POST", body: JSON.stringify(body)});
    await api("/api/admin/local-settings", {method: "POST", body: JSON.stringify({
      local: {
        startup_enabled: document.getElementById("startupEnabled").checked,
        host: document.getElementById("serverHost").value,
        port: Number(document.getElementById("serverPort").value || 5200),
        data_dir: document.getElementById("localDataDir").value,
        download_dirs: splitDirs(document.getElementById("localDownloadDirs").value),
        tunnel_mode: "Quick",
      }
    })});
    refresh();
  }
  if (e.target.dataset.copy !== undefined) {
    const value = e.target.dataset.copy || "";
    if (!value) return;
    await navigator.clipboard.writeText(value);
    const old = e.target.textContent;
    e.target.textContent = "已复制";
    setTimeout(() => { e.target.textContent = old; }, 1000);
  }
});

setInterval(() => {
  if (["dashboard", "messages", "reading", "monitor"].includes(state.view)) refresh();
}, 15000);

const initialView = initialParams.get("view");
if (initialView && titles[initialView]) setView(initialView);
else refresh();
