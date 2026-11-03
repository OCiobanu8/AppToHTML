package com.example.apptohtml.crawler

/**
 * The case overview and the case page, as self-contained HTML: inline CSS and JavaScript, no
 * external resources.
 *
 * Every value that comes from the crawl is HTML-escaped where it is written. The script reads what it
 * needs from the DOM rather than having crawl text interpolated into code, and it computes no verdict:
 * it posts the editor's text and shows what the server answered.
 */
internal object CasePages {

    /** Whether a device is ready for push, and if not, why — shown on both pages. */
    data class DeviceStatus(val ready: Boolean, val detail: String)

    /** The overview: every case in [rows], linking the ones that can be worked to their page. */
    fun overview(crawl: CaseCrawl, rows: List<CaseOverviewRow>, device: DeviceStatus, token: String): String = page(
        title = "Identity cases",
        body = buildString {
            append("<h1>Identity cases</h1>\n")
            append("<p class=\"meta\">Crawl <code>").append(html(crawl.directory.absolutePath)).append("</code></p>\n")
            append(deviceLine(device))
            if (rows.isEmpty()) {
                append("<p class=\"empty\">This crawl has no identity cases.</p>\n")
            } else {
                append("<table>\n<thead><tr><th>Case</th><th>Status</th><th>Kind</th><th>Screen</th><th>Why</th><th>On resume</th></tr></thead>\n<tbody>\n")
                rows.forEach { row ->
                    append("<tr class=\"").append(row.status.xmlValue).append("\">")
                    append("<td>")
                    if (row.isActionable) {
                        append("<a href=\"/case/").append(html(row.caseId)).append("?token=").append(html(token)).append("\">")
                            .append(html(row.caseId)).append("</a>")
                    } else {
                        append(html(row.caseId))
                    }
                    append("</td>")
                    append("<td><span class=\"badge ").append(row.status.xmlValue).append("\">").append(row.status.xmlValue).append("</span></td>")
                    append("<td>").append(html(row.kind.xmlValue)).append("</td>")
                    append("<td><code>").append(html(row.screenName)).append("</code>")
                    if (row.relatedScreenIds.isNotEmpty()) {
                        append("<div class=\"related\">with ").append(html(row.relatedScreenIds.joinToString(", "))).append("</div>")
                    }
                    append("</td>")
                    append("<td>").append(html(row.detail)).append("</td>")
                    append("<td>").append(html(row.onResume)).append("</td>")
                    append("</tr>\n")
                }
                append("</tbody>\n</table>\n")
            }
            append("<p class=\"note\">Settle while the crawl is stopped. A handled case is confirmed on the live screen when the crawl resumes.</p>\n")
            append("<button id=\"done\" type=\"button\">Done</button>\n<p id=\"ended\" hidden>The tool has stopped. You can close this tab.</p>\n")
        },
        token = token,
    )

    /** A page saying [caseId] cannot be worked, and why, instead of an editor. */
    fun unavailable(caseId: String, reason: String, token: String): String = page(
        title = caseId,
        body = "<p><a href=\"/?token=${html(token)}\">&larr; All cases</a></p>\n<h1>${html(caseId)}</h1>\n" +
            "<p class=\"empty\">${html(reason)}</p>\n",
        token = token,
    )

    /** The page for one open or handled [case] raised on [screen]: the editor beside the screen. */
    fun case(case: IdentityCase, screen: CaseScreen, device: DeviceStatus, token: String): String {
        val xmlText = screen.capture.xmlFile.readText(Charsets.UTF_8)
        val identityText = xmlText.substringFromTo("<screen-identity", "</screen-identity>")
            ?: xmlText.substringFromTo("<screen-identity", "/>")
            ?: ""
        val treeText = xmlText.indexOf("<scroll-steps>").takeIf { it >= 0 }
            ?.let { from -> xmlText.substring(from).substringFromTo("<step index=", "</step>") }
            ?: "(this capture records no scroll steps)"
        val settleLabel = if (device.ready) "Settle + Push" else "Settle"
        return page(
            title = "${case.id} ${screen.capture.xmlFile.name.removeSuffix(".xml")}",
            body = buildString {
                append("<p><a href=\"/?token=").append(html(token)).append("\">&larr; All cases</a></p>\n")
                append("<h1>").append(html(case.id)).append(" <span class=\"kind\">").append(html(case.kind.xmlValue)).append("</span></h1>\n")
                append("<p class=\"meta\">Raised on <code>").append(html(screen.capture.xmlFile.name.removeSuffix(".xml"))).append("</code>")
                if (case.relatedScreenIds.isNotEmpty()) {
                    append(" &middot; related ").append(html(case.relatedScreenIds.joinToString(", ")))
                }
                append(" &middot; <span class=\"badge ").append(case.status.xmlValue).append("\">").append(case.status.xmlValue).append("</span></p>\n")
                append("<p class=\"detail\">").append(html(case.detail)).append("</p>\n")
                append(deviceLine(device))
                append("<div class=\"grid\">\n<section>\n<h2>Traits this screen asserts</h2>\n")
                append("<textarea id=\"traits\" spellcheck=\"false\">").append(html(prefill(case, screen))).append("</textarea>\n")
                append("<div class=\"actions\"><button id=\"check\" type=\"button\">Check Traits</button> ")
                append("<button id=\"settle\" type=\"button\" hidden data-push=\"").append(device.ready).append("\">")
                    .append(settleLabel).append("</button></div>\n")
                append("<div id=\"result\" aria-live=\"polite\"></div>\n")
                append("<p class=\"note\">Settle while the crawl is stopped. Before anything is written, the device's copies are checked against the local files the settle replaces.</p>\n")
                append("</section>\n<section>\n<h2>The screen</h2>\n<h3>Pressable elements</h3>\n<ul class=\"elements\">\n")
                screen.capture.identity.elements.sortedBy { it.encoded }.forEach { element ->
                    val snippet = StringBuilder().also { ScreenIdentityXml.appendTraits(it, listOf(HasControl(element)), depth = 0) }
                        .toString().removePrefix("<traits>\n").removeSuffix("</traits>\n")
                        .lines().joinToString("\n") { line -> line.removePrefix("  ") }.trimEnd()
                    append("<li><code>").append(html(element.fingerprint.label.ifBlank { "(no label)" })).append("</code>")
                    append(" <span class=\"rid\">").append(html(element.fingerprint.resourceId ?: "no resource id")).append("</span>")
                    append(" <button type=\"button\" class=\"copy\" data-snippet=\"").append(html(snippet)).append("\">copy as has-control</button></li>\n")
                }
                append("</ul>\n<h3>Identity as recorded</h3>\n<pre>").append(html(identityText)).append("</pre>\n")
                append("<details><summary>First viewport tree</summary><pre>").append(html(treeText)).append("</pre></details>\n")
                append("</section>\n</div>\n")
                append("<input type=\"hidden\" id=\"case-id\" value=\"").append(html(case.id)).append("\">\n")
            },
            token = token,
        )
    }

    /**
     * What the editor starts with: the traits the screen asserts now — a settled screen that stopped
     * holding, or an operator's earlier settle — or, for a screen that asserts nothing, the proposal the
     * crawler tried; then examples of every trait, commented out.
     */
    internal fun prefill(case: IdentityCase, screen: CaseScreen): String {
        val start = screen.capture.identity.traits.ifEmpty { case.rejectedProposal }
        val body = StringBuilder().also { ScreenIdentityXml.appendTraits(it, start, depth = 0) }.toString()
            .removePrefix("<traits>\n").removeSuffix("</traits>\n")
        return buildString {
            append("<traits>\n")
            append(body)
            append(EXAMPLES)
            append("</traits>\n")
        }
    }

    private val EXAMPLES = """
        |  <!-- has-list: a container whose rows repeat a shape; ids from the tree on the right.
        |  <has-list container-resource-id="com.example:id/list" min-rows="2">
        |    <row-child resource-id="android:id/title" />
        |  </has-list>
        |  -->
        |  <!-- has-control: a control that is on this screen; use "copy as has-control" on the right.
        |  <has-control>
        |    <element label="checkout" resource-id="com.example:id/checkout" class="android.widget.Button" list-item="false" checkable="false" editable="false" back="false" />
        |  </has-control>
        |  -->
        |  <!-- lacks-control: a control that is NOT on this screen, to tell it from a lookalike.
        |  <lacks-control>
        |    <element label="sign in" resource-id="com.example:id/sign_in" class="android.widget.Button" list-item="false" checkable="false" editable="false" back="false" />
        |  </lacks-control>
        |  -->
        |""".trimMargin()

    private fun deviceLine(device: DeviceStatus): String =
        "<p class=\"device ${if (device.ready) "ready" else "absent"}\">Device: ${html(device.detail)}</p>\n"

    private fun String.substringFromTo(open: String, close: String): String? {
        val start = indexOf(open)
        if (start < 0) return null
        val end = indexOf(close, start)
        if (end < 0) return null
        return substring(start, end + close.length)
    }

    /** Escapes text for HTML element content and quoted attributes alike. */
    fun html(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    /**
     * The page around [body]. Crawl text is only ever substituted after the margins are trimmed,
     * so nothing in it can be taken for a margin.
     */
    private fun page(title: String, body: String, token: String): String =
        HEAD.replace("@TITLE@", html(title)).replace("@TOKEN@", html(token)) + body + SCRIPT

    private val HEAD = """
        |<!doctype html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>@TITLE@</title>
        |<style>
        |:root { --bg: #fbfbfa; --fg: #1f2328; --muted: #656d76; --line: #d8dee4; --panel: #ffffff;
        |  --accent: #0b6bcb; --ok: #1a7f37; --bad: #cf222e; --warn: #9a6700; }
        |@media (prefers-color-scheme: dark) { :root { --bg: #0d1117; --fg: #e6edf3; --muted: #8d96a0;
        |  --line: #30363d; --panel: #161b22; --accent: #4493f8; --ok: #3fb950; --bad: #f85149; --warn: #d29922; } }
        |* { box-sizing: border-box; }
        |body { margin: 0 auto; max-width: 1400px; padding: 24px 16px; background: var(--bg); color: var(--fg);
        |  font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
        |h1 { font-size: 22px; margin: 4px 0 8px; } h2 { font-size: 16px; margin: 0 0 8px; } h3 { font-size: 13px; margin: 16px 0 6px; color: var(--muted); }
        |a { color: var(--accent); } code, pre, textarea { font: 12.5px/1.45 ui-monospace, "Cascadia Mono", Consolas, monospace; }
        |.meta, .note, .related, .rid { color: var(--muted); } .detail { padding: 8px 12px; border-left: 3px solid var(--warn); background: var(--panel); }
        |.kind { font-weight: 400; color: var(--muted); font-size: 15px; }
        |.device.ready::before { content: "\25CF "; color: var(--ok); } .device.absent::before { content: "\25CF "; color: var(--muted); }
        |table { border-collapse: collapse; width: 100%; background: var(--panel); }
        |th, td { text-align: left; vertical-align: top; padding: 8px 10px; border-bottom: 1px solid var(--line); }
        |tr.closed td { color: var(--muted); }
        |.badge { display: inline-block; padding: 0 8px; border-radius: 10px; border: 1px solid var(--line); font-size: 12px; }
        |.badge.open { color: var(--warn); } .badge.handled { color: var(--accent); } .badge.closed { color: var(--muted); }
        |.grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; margin-top: 16px; }
        |@media (max-width: 900px) { .grid { grid-template-columns: minmax(0, 1fr); } }
        |section { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px; min-width: 0; }
        |textarea { width: 100%; min-height: 420px; padding: 10px; background: var(--bg); color: var(--fg); border: 1px solid var(--line); border-radius: 6px; resize: vertical; }
        |pre { overflow: auto; max-height: 420px; padding: 10px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; margin: 0; }
        |button { font: inherit; padding: 6px 14px; border-radius: 6px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); cursor: pointer; }
        |button#settle { background: var(--accent); border-color: var(--accent); color: #fff; }
        |button.copy { padding: 1px 8px; font-size: 12px; } .actions { margin: 10px 0; }
        |ul.elements { padding-left: 18px; margin: 0; } ul.elements li { margin: 4px 0; overflow-wrap: anywhere; }
        |#result { white-space: pre-wrap; } #result .ok { color: var(--ok); } #result .bad { color: var(--bad); }
        |</style>
        |</head>
        |<body>
        |<input type="hidden" id="token" value="@TOKEN@">
        |""".trimMargin()

    private val SCRIPT = """
        |<script>
        |(function () {
        |  var token = document.getElementById('token').value;
        |  function post(path, body) {
        |    return fetch(path, { method: 'POST', headers: { 'X-A2H-Token': token, 'Content-Type': 'text/plain; charset=utf-8' }, body: body || '' })
        |      .then(function (r) {
        |        return r.json().catch(function () { return { message: 'The tool answered ' + r.status + ' with no result.' }; });
        |      })
        |      .catch(function () { return { message: 'The tool is not answering. Was it stopped?' }; });
        |  }
        |  function show(el, ok, text) { el.textContent = ''; var p = document.createElement('div'); p.className = ok ? 'ok' : 'bad'; p.textContent = text; el.appendChild(p); }
        |  var done = document.getElementById('done');
        |  if (done) done.addEventListener('click', function () {
        |    post('/done').then(function () { done.hidden = true; document.getElementById('ended').hidden = false; });
        |  });
        |  document.querySelectorAll('button.copy').forEach(function (b) {
        |    b.addEventListener('click', function () {
        |      navigator.clipboard.writeText(b.getAttribute('data-snippet')).then(function () { b.textContent = 'copied'; setTimeout(function () { b.textContent = 'copy as has-control'; }, 1200); });
        |    });
        |  });
        |  var editor = document.getElementById('traits');
        |  if (!editor) return;
        |  var caseId = document.getElementById('case-id').value;
        |  var settle = document.getElementById('settle');
        |  var result = document.getElementById('result');
        |  var passed = null;
        |  editor.addEventListener('input', function () { if (editor.value !== passed) settle.hidden = true; });
        |  document.getElementById('check').addEventListener('click', function () {
        |    var text = editor.value;
        |    post('/case/' + encodeURIComponent(caseId) + '/check', text).then(function (r) {
        |      show(result, r.holds, r.message);
        |      passed = r.holds ? text : null;
        |      settle.hidden = !(r.holds && editor.value === text);
        |    });
        |  });
        |  settle.addEventListener('click', function () {
        |    var push = settle.getAttribute('data-push') === 'true';
        |    settle.disabled = true;
        |    post('/case/' + encodeURIComponent(caseId) + '/settle?push=' + push, editor.value).then(function (r) {
        |      settle.disabled = false;
        |      var lines = [r.message || 'No answer from the tool.'];
        |      if ((r.written || []).length) lines.push('Written: ' + r.written.join(', '));
        |      if ((r.pushed || []).length) lines.push('Pushed: ' + r.pushed.join(', '));
        |      if ((r.toPush || []).length) lines.push('Still to push, before the crawl resumes:\n' + r.toPush.join('\n'));
        |      if (r.problem) lines.push(r.problem);
        |      show(result, r.succeeded, lines.join('\n'));
        |      if (r.succeeded) settle.hidden = true;
        |    });
        |  });
        |})();
        |</script>
        |</body>
        |</html>
        |""".trimMargin()
}
