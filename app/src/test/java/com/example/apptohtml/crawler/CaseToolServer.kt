package com.example.apptohtml.crawler

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves the case overview and the case pages for one crawl folder, on the loopback interface only.
 *
 * Every route needs [token], as a `token` query parameter or an `X-A2H-Token` header, so no other page
 * open in the browser can drive it. It writes only through [CaseSettleService], into [crawlDir], and
 * computes no verdict of its own. The folder is read afresh on every request, so the overview always
 * shows what is on disk — a case settled a moment ago reads `handled`.
 *
 * It stops on `POST /done`, or after [idleTimeoutMillis] with no request.
 */
internal class CaseToolServer(
    private val crawlDir: File,
    private val token: String,
    port: Int,
    /** The device to push to, or null when no `adb` was configured; asked afresh per request. */
    private val device: (CaseCrawl) -> DeviceCrawl?,
    /** The device crawl folder the settled files belong in, for the commands that push them by hand. */
    private val remoteDir: (CaseCrawl) -> String? = { null },
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
) {
    // 127.0.0.1, not getLoopbackAddress(): with IPv6 preferred that is ::1, where the script does not look.
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
    private val stopped = CountDownLatch(1)

    @Volatile
    private var lastRequestAt = System.currentTimeMillis()

    /** Where the server listens, once [start]ed. */
    val address: InetSocketAddress get() = server.address

    /** Starts serving on [address], one request at a time. */
    fun start() {
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Exception) {
                val reason = "The tool failed: ${e.message ?: e.toString()}"
                respond(exchange, 500, "application/json", settleJson(CaseSettleService.Outcome(OperatorSettlement.Verdict.NotSettleable(reason))))
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    /** Blocks until `POST /done` or the idle timeout, then stops the server. */
    fun awaitDone() {
        while (!stopped.await(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() - lastRequestAt > idleTimeoutMillis) break
        }
        stop()
    }

    /** Stops serving and releases the port; [awaitDone] returns. */
    fun stop() {
        stopped.countDown()
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        if (!authorized(exchange)) return forbidden(exchange)
        // Only the operator's own requests keep the tool alive; anything else must not extend it.
        lastRequestAt = System.currentTimeMillis()
        if (path == "/health" && method == "GET") return respond(exchange, 200, "text/plain", "ok")
        val caseRoute = CASE_ROUTE.matchEntire(path)
        when {
            method == "GET" && path == "/" -> overview(exchange)
            method == "POST" && path == "/done" -> {
                respond(exchange, 200, "application/json", json("stopped" to true))
                stopped.countDown()
            }
            caseRoute != null -> {
                val caseId = caseRoute.groupValues[1]
                when (method to caseRoute.groupValues[2]) {
                    "GET" to "" -> casePage(exchange, caseId)
                    "POST" to "/check" -> check(exchange, caseId)
                    "POST" to "/settle" -> settle(exchange, caseId)
                    else -> respond(exchange, 404, "text/plain", "not found")
                }
            }
            else -> respond(exchange, 404, "text/plain", "not found")
        }
    }

    /** The folder, or null after answering with why it cannot be read: a refused case file is the operator's to fix. */
    private fun loadOrRefuse(exchange: HttpExchange, asPage: Boolean): CaseCrawl? = try {
        CaseCrawl.load(crawlDir)
    } catch (refused: MalformedIdentityCases) {
        val reason = "The case file is refused: ${refused.message}"
        if (asPage) {
            respond(exchange, 200, HTML, CasePages.unavailable("Identity cases", reason, token))
        } else {
            respond(exchange, 200, "application/json", settleJson(CaseSettleService.Outcome(OperatorSettlement.Verdict.NotSettleable(reason))))
        }
        null
    }

    private fun overview(exchange: HttpExchange) {
        val crawl = loadOrRefuse(exchange, asPage = true) ?: return
        respond(exchange, 200, HTML, CasePages.overview(crawl, CaseOverview.rows(crawl), deviceStatus(crawl), token))
    }

    private fun casePage(exchange: HttpExchange, caseId: String) {
        val crawl = loadOrRefuse(exchange, asPage = true) ?: return
        val case = crawl.case(caseId)
        val screen = case?.let { crawl.screen(it.raisedOn) }
        val page = when {
            case == null -> CasePages.unavailable(caseId, "There is no case $caseId in this crawl.", token)
            case.status == IdentityCaseStatus.CLOSED -> CasePages.unavailable(
                caseId,
                "$caseId is closed: the resumed crawl confirmed it on the live screen. It is kept as history.",
                token,
            )
            screen == null -> CasePages.unavailable(
                caseId,
                "The capture of ${case.raisedOn} cannot be used: ${crawl.problems[case.raisedOn] ?: "it is not in this folder"}.",
                token,
            )
            else -> CasePages.case(case, screen, deviceStatus(crawl), token)
        }
        respond(exchange, 200, HTML, page)
    }

    private fun check(exchange: HttpExchange, caseId: String) {
        val crawl = loadOrRefuse(exchange, asPage = false) ?: return
        val verdict = OperatorSettlement.check(crawl, caseId, body(exchange))
        respond(exchange, 200, "application/json", verdictJson(verdict))
    }

    private fun settle(exchange: HttpExchange, caseId: String) {
        val push = query(exchange)["push"] == "true"
        val traits = body(exchange)
        val crawl = loadOrRefuse(exchange, asPage = false) ?: return
        val pushTo = try {
            if (push) device(crawl) else null
        } catch (e: Exception) {
            val outcome = CaseSettleService.Outcome(OperatorSettlement.Verdict.NotSettleable("Nothing written: cannot push: ${e.message}"))
            return respond(exchange, 200, "application/json", settleJson(outcome))
        }
        if (push && pushTo == null) {
            return respond(
                exchange,
                200,
                "application/json",
                settleJson(
                    CaseSettleService.Outcome(
                        OperatorSettlement.Verdict.NotSettleable("Nothing written: no adb is configured, so nothing can be pushed."),
                    ),
                ),
            )
        }
        // The service re-checks before it writes: a passing check the page made earlier is never trusted.
        val outcome = CaseSettleService.settle(crawlDir, caseId, traits, write = true, device = pushTo)
        respond(exchange, 200, "application/json", settleJson(outcome, remoteDir(crawl)))
    }

    /** A settle's answer, always with every field the page reads, whatever stopped it. */
    private fun settleJson(outcome: CaseSettleService.Outcome, remoteDir: String? = null): String = json(
        "status" to outcome.verdict.status.name,
        "succeeded" to outcome.succeeded,
        "message" to outcome.verdict.describe(),
        "written" to outcome.written.map(File::getName),
        "pushed" to outcome.pushed,
        "problem" to outcome.problem,
        "toPush" to PushCommands.lines(outcome.toPush, remoteDir),
    )

    private fun deviceStatus(crawl: CaseCrawl): CasePages.DeviceStatus {
        // adb configured but unusable for this crawl (it names no package, say) is not "no adb configured".
        val reachable = try {
            device(crawl)
        } catch (e: Exception) {
            return CasePages.DeviceStatus(ready = false, detail = "cannot push: ${e.message}; settled files will need pushing by hand")
        } ?: return CasePages.DeviceStatus(ready = false, detail = "no adb configured; settled files will need pushing by hand")
        val reason = reachable.unavailableReason
        return if (reason == null) {
            CasePages.DeviceStatus(ready = true, detail = "ready; Settle pushes the changed files")
        } else {
            CasePages.DeviceStatus(ready = false, detail = "$reason; settled files will need pushing by hand")
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean =
        query(exchange)["token"] == token || exchange.requestHeaders.getFirst(TOKEN_HEADER) == token

    private fun forbidden(exchange: HttpExchange) = respond(exchange, 403, "text/plain", "forbidden")

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }

    private fun body(exchange: HttpExchange): String = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val TOKEN_HEADER = "X-A2H-Token"
        private const val HTML = "text/html"
        private const val IDLE_POLL_MILLIS = 500L
        private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L
        private val CASE_ROUTE = Regex("""^/case/([A-Za-z0-9_\-]+)(/check|/settle)?$""")

        /** A check's answer as the page reads it. */
        fun verdictJson(verdict: OperatorSettlement.Verdict): String = json(
            "status" to verdict.status.name,
            "holds" to (verdict is OperatorSettlement.Verdict.Holds),
            "message" to verdict.describe(),
        )

        /** A flat JSON object of strings, booleans, nulls and string lists — all the pages need. */
        fun json(vararg fields: Pair<String, Any?>): String = fields.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:${encode(value)}"
        }

        private fun encode(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is String -> quote(value)
            is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
            else -> quote(value.toString())
        }

        private fun quote(text: String): String = buildString {
            append('"')
            text.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\t' -> append("\\t")
                    char < ' ' || char == '<' || char == '>' || char == '&' || char == ' ' || char == ' ' ->
                        append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
