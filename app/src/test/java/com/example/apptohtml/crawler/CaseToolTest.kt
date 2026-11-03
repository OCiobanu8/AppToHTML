package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.CaseFolderFixtures.batteryUsage
import com.example.apptohtml.crawler.CaseFolderFixtures.case
import com.example.apptohtml.crawler.CaseFolderFixtures.elementOf
import com.example.apptohtml.crawler.CaseFolderFixtures.fingerprint
import com.example.apptohtml.crawler.CaseFolderFixtures.home
import com.example.apptohtml.crawler.CaseFolderFixtures.lookalike
import com.example.apptohtml.crawler.CaseFolderFixtures.settingsFolder
import com.example.apptohtml.crawler.CaseFolderFixtures.traitsText
import com.example.apptohtml.crawler.CaseFolderFixtures.writeScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** The case tool: which job runs, what the survey prints, and the overview server on localhost. */
class CaseToolTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val token = "t0k3n-for-tests"
    private val servers = mutableListOf<CaseToolServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    // ---- which job runs ------------------------------------------------------------------------

    @Test
    fun `the script's arguments select exactly one job`() {
        fun select(vararg pairs: Pair<String, String>) = CaseToolMode.select(mapOf(*pairs)::get)

        assertEquals(CaseToolMode.None, select())
        assertTrue(select("a2h.target" to "a.xml") is CaseToolMode.ValidateOne)
        assertEquals(CaseToolMode.Survey(File("d")), select("a2h.crawl" to "d", "a2h.survey" to "true"))
        assertEquals(
            CaseToolMode.Serve(File("d"), 4000, "x", device = null),
            select("a2h.crawl" to "d", "a2h.port" to "4000", "a2h.token" to "x"),
        )
        assertEquals(
            CaseToolMode.Settle(File("d"), "case_000", File("t.xml"), dryRun = true, device = null),
            select("a2h.crawl" to "d", "a2h.settle" to "case_000", "a2h.traits" to "t.xml", "a2h.dryRun" to "true"),
        )
        val pushing = select(
            "a2h.crawl" to "d", "a2h.settle" to "case_000", "a2h.traits" to "t.xml", "a2h.push" to "true", "a2h.adb" to "adb.exe",
        ) as CaseToolMode.Settle
        assertEquals(CaseToolMode.DeviceSettings(File("adb.exe"), serial = null, remoteDir = null), pushing.device)
    }

    @Test
    fun `a job that cannot run says what is missing instead of running half`() {
        fun select(vararg pairs: Pair<String, String>) = CaseToolMode.select(mapOf(*pairs)::get)

        assertTrue((select("a2h.crawl" to "d", "a2h.settle" to "case_000") as CaseToolMode.Invalid).reason.contains("traits"))
        assertTrue(
            (select("a2h.crawl" to "d", "a2h.settle" to "c", "a2h.traits" to "t", "a2h.push" to "true") as CaseToolMode.Invalid)
                .reason.contains("adb"),
        )
        assertTrue((select("a2h.crawl" to "d") as CaseToolMode.Invalid).reason.contains("port"))
        assertTrue((select("a2h.crawl" to "d", "a2h.port" to "1") as CaseToolMode.Invalid).reason.contains("token"))
    }

    // ---- text survey and reports ---------------------------------------------------------------

    @Test
    fun `the survey's cases section lists every case with its status, screen and detail`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val handled = CaseCrawl.load(dir).cases.map { if (it.id == "case_001") it.copy(status = IdentityCaseStatus.HANDLED) else it }
        IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), handled)

        val text = SurveyCases.render(dir).text

        assertTrue(text, text.startsWith("=== cases ==="))
        val rows = CaseOverview.rows(CaseCrawl.load(dir))
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertTrue(text, text.contains("${row.caseId}  ${row.status.xmlValue}"))
            assertTrue(text, text.contains(row.screenName))
            assertTrue(text, text.contains(row.detail))
        }
        assertTrue(text, text.contains("screen_00002_cart  on resume: confirmed on the live screen"))
        assertTrue(text, text.contains("screen_00001_battery  on resume: left as it is"))
    }

    @Test
    fun `a crawl with no cases says so`() {
        val dir = tempFolder.newFolder()
        writeScreen(dir, "screen_00000", "Home", home)

        assertEquals("=== cases ===\n(none)", SurveyCases.render(dir).text)
    }

    @Test
    fun `a refused case file makes the survey INVALID_INPUT, not a verdict about the screens`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.fileIn(dir).writeText("<identity-cases schema=\"v9\" />")

        val section = SurveyCases.render(dir)

        assertTrue(section.refused)
        assertTrue(section.text, section.text.contains("INVALID_INPUT"))
        assertEquals(ScreenIdentityReport.Status.INVALID_INPUT, SurveyCases.worst(setOf(ScreenIdentityReport.Status.HOLDS), unreadable = section.refused))
        assertEquals(
            ScreenIdentityReport.Status.UNSETTLED,
            SurveyCases.worst(setOf(ScreenIdentityReport.Status.HOLDS, ScreenIdentityReport.Status.UNSETTLED), unreadable = false),
        )
    }

    @Test
    fun `the server answers a refused case file with the reason, on every route`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.fileIn(dir).writeText("<identity-cases schema=\"v9\" />")
        val server = serve(dir)

        listOf("GET" to "/", "GET" to "/case/case_000").forEach { (method, path) ->
            val reply = request(server, method, path)
            assertEquals(200, reply.code)
            assertTrue(reply.body, reply.body.contains("The case file is refused"))
        }
        listOf("/case/case_000/check", "/case/case_000/settle?push=false").forEach { path ->
            val reply = request(server, "POST", path, traitsText(CaseFolderFixtures.adaptive))
            assertEquals(200, reply.code)
            assertTrue(reply.body, reply.body.contains("The case file is refused"))
        }
    }

    @Test
    fun `a settle with no device lists the adb push commands for the files still to push`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = CaseToolServer(dir, token, port = 0, device = { null }, remoteDir = { "/sdcard/crawl" }).also { it.start(); servers += it }

        val reply = request(server, "POST", "/case/case_000/settle?push=false", traitsText(CaseFolderFixtures.adaptive)).body

        assertTrue(reply, reply.contains("\"succeeded\":true"))
        listOf("screen_00001_battery.xml", "screen_00001_battery.html", "identity-cases.xml").forEach { name ->
            val expected = "adb push \"${File(dir, name).absolutePath}\" \"/sdcard/crawl/$name\""
            assertTrue(reply, reply.contains(CaseToolServer.json("x" to expected).removePrefix("{\"x\":").removeSuffix("}")))
        }
        val cli = SettleReport.render(
            "case_000",
            dryRun = false,
            CaseSettleService.Outcome(OperatorSettlement.Verdict.NotSettleable("x"), toPush = listOf(File(dir, "a.xml"))),
            remoteDir = "/sdcard/crawl",
        )
        assertTrue(cli, cli.contains("still to push"))
        assertTrue(cli, cli.contains("adb push \"${File(dir, "a.xml").absolutePath}\" \"/sdcard/crawl/a.xml\""))
    }

    @Test
    fun `a handled case's editor starts from the traits the operator settled`() {
        val dir = settingsFolder(tempFolder.newFolder())
        CaseSettleService.settle(dir, "case_000", traitsText(CaseFolderFixtures.adaptive), write = true, device = null)

        val page = request(serve(dir), "GET", "/case/case_000").body
        // The element also appears on the screen side of the page, so only the editor's text counts.
        val editor = page.substringAfter("<textarea id=\"traits\" spellcheck=\"false\">").substringBefore("</textarea>")

        assertTrue(editor, editor.contains(CasePages.html("""<element label="adaptive battery"""")))
    }

    // ---- adb ------------------------------------------------------------------------------------

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** A stand-in for adb: a script that runs [body] (cmd on Windows, sh elsewhere). */
    private fun fakeAdb(windowsBody: String, unixBody: String): File {
        val dir = tempFolder.newFolder("fake-adb")
        return if (windows) {
            File(dir, "adb.cmd").apply { writeText("@echo off\r\n$windowsBody\r\n") }
        } else {
            File(dir, "adb").apply { writeText("#!/bin/sh\n$unixBody\n"); setExecutable(true) }
        }
    }

    @Test
    fun `a hung adb is given up on after the timeout`() {
        val adb = fakeAdb(windowsBody = "ping -n 8 127.0.0.1 >nul", unixBody = "sleep 8")
        val device = AdbDeviceCrawl(adb, serial = null, remoteDir = "/sdcard/crawl", timeoutSeconds = 1)
        val started = System.currentTimeMillis()

        val reason = device.unavailableReason

        val elapsed = System.currentTimeMillis() - started
        assertTrue("$reason", reason!!.contains("timed out"))
        assertTrue("waited ${elapsed}ms for a 1s timeout", elapsed < 5_000)
    }

    @Test
    fun `Settle + Push from the page pushes the three files to the device`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val device = FakeDevice.mirroring(dir)

        val reply = request(serve(dir, device = device), "POST", "/case/case_000/settle?push=true", traitsText(CaseFolderFixtures.adaptive)).body

        assertTrue(reply, reply.contains("\"succeeded\":true"))
        assertEquals(listOf("screen_00001_battery.xml", "screen_00001_battery.html", IdentityCaseStore.FILE_NAME), device.pushes)
        assertTrue(reply, reply.contains("\"pushed\":[\"screen_00001_battery.xml\",\"screen_00001_battery.html\",\"identity-cases.xml\"]"))
    }

    @Test
    fun `the case page's device line and Settle button follow what the device can do`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val cases = listOf(
            serve(dir, device = FakeDevice.mirroring(dir)) to Triple("Device: ready; Settle pushes the changed files", "true", "Settle + Push"),
            serve(dir, device = FakeDevice(unavailableReason = "no ready device (offline)")) to
                Triple("Device: no ready device (offline); settled files will need pushing by hand", "false", "Settle"),
            serve(dir, device = null) to Triple("Device: no adb configured; settled files will need pushing by hand", "false", "Settle"),
        )

        cases.forEach { (server, expected) ->
            val (line, push, label) = expected
            val page = request(server, "GET", "/case/case_000").body
            assertTrue(page, page.contains(line))
            assertTrue(page, page.contains("data-push=\"$push\">$label</button>"))
        }
    }

    @Test
    fun `a crawl adb cannot push to is named as such, and its push writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = CaseToolServer(dir, token, port = 0, device = { error("Cannot tell which app this crawl belongs to") })
            .also { it.start(); servers += it }
        val before = fingerprint(dir)

        val overview = request(server, "GET", "/").body
        val reply = request(server, "POST", "/case/case_000/settle?push=true", traitsText(CaseFolderFixtures.adaptive))

        assertTrue(overview, overview.contains("Device: cannot push: Cannot tell which app this crawl belongs to"))
        assertFalse(overview, overview.contains("no adb configured"))
        assertEquals(200, reply.code)
        assertTrue(reply.body, reply.body.contains("Nothing written: cannot push: Cannot tell which app"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `adb that answers but has no ready device is not used`() {
        val adb = fakeAdb(windowsBody = "echo offline", unixBody = "echo offline")
        val device = AdbDeviceCrawl(adb, serial = null, remoteDir = "/sdcard/crawl")

        val reason = device.unavailableReason

        assertTrue("$reason", reason!!.contains("no ready device"))
        assertTrue(reason, reason.contains("offline"))
    }

    @Test
    fun `the token can come from a one-time file, which is deleted once read`() {
        val tokenFile = File(tempFolder.root, "token").apply { writeText("secret-token\n") }

        val mode = CaseToolMode.select(mapOf("a2h.crawl" to "d", "a2h.port" to "1", "a2h.tokenFile" to tokenFile.path)::get)

        assertEquals("secret-token", (mode as CaseToolMode.Serve).token)
        assertFalse(tokenFile.exists())
    }

    @Test
    fun `a device that cannot be read refuses the push as unreadable, not as changed`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val adb = fakeAdb(
            windowsBody = "if \"%1\"==\"get-state\" (echo device& exit /b 0)\r\nping -n 8 127.0.0.1 >nul",
            unixBody = "if [ \"$1\" = get-state ]; then echo device; exit 0; fi\nsleep 8",
        )
        val device = AdbDeviceCrawl(adb, serial = null, remoteDir = "/sdcard/crawl", timeoutSeconds = 1)
        val before = fingerprint(dir)

        val outcome = CaseSettleService.settle(dir, "case_000", traitsText(CaseFolderFixtures.adaptive), write = true, device = device)

        assertTrue("${outcome.problem}", outcome.problem!!.contains("the device could not be read"))
        assertFalse(outcome.problem!!, outcome.problem!!.contains("changed since the pull"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `every settle answer carries the fields the page reads`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val refusedDir = settingsFolder(tempFolder.newFolder()).also { IdentityCaseStore.fileIn(it).writeText("<identity-cases schema=\"v9\" />") }

        listOf(serve(dir) to "case_009", serve(refusedDir) to "case_000", serve(dir, device = null) to "case_000").forEachIndexed { index, (server, caseId) ->
            val push = if (index == 2) "true" else "false"
            val reply = request(server, "POST", "/case/$caseId/settle?push=$push", traitsText(CaseFolderFixtures.adaptive)).body
            listOf("\"status\":", "\"succeeded\":", "\"message\":", "\"written\":[", "\"pushed\":[", "\"problem\":", "\"toPush\":[").forEach { field ->
                assertTrue("$reply lacks $field", reply.contains(field))
            }
        }
    }

    @Test
    fun `a remote path reaches the device shell as one quoted word`() {
        val argsFile = File(tempFolder.root, "adb-args.txt")
        val adb = fakeAdb(windowsBody = "echo %* > \"${argsFile.absolutePath}\"", unixBody = "echo \"$@\" > '${argsFile.absolutePath}'")
        val device = AdbDeviceCrawl(adb, serial = null, remoteDir = "/sdcard/my crawl")

        // The fake prints no hash, so the read itself fails; what is asserted is what adb was given.
        runCatching { device.sha256("a b.xml") }

        // On Windows the JVM also wraps an argument holding spaces in double quotes, which adb strips
        // again before the device sees it; what matters is that the device shell gets one quoted word.
        val seen = argsFile.readText()
        assertTrue(seen, seen.contains("shell sha256sum"))
        assertTrue(seen, seen.contains("'/sdcard/my crawl/a b.xml'"))
    }

    @Test
    fun `the single-screen report names who settled the identity`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val home = File(dir, "screen_00000_home.xml")
        val battery = File(dir, "screen_00001_battery.xml")

        assertTrue(ScreenIdentityReport.validate(home, home, dir, isRootScreen = true).text.contains("settled by: machine"))
        assertTrue(ScreenIdentityReport.validate(battery, battery, dir, isRootScreen = false).text.contains("settled by: nobody"))
    }

    @Test
    fun `a command-line settle that holds but cannot push reports SETTLE_FAILED`() {
        val holds = OperatorSettlement.Verdict.Holds("screen_00001", ScreenIdentity.EMPTY, 2)

        assertEquals("HOLDS", SettleReport.status(dryRun = true, CaseSettleService.Outcome(holds)))
        assertEquals("HOLDS", SettleReport.status(dryRun = false, CaseSettleService.Outcome(holds)))
        assertEquals(SettleReport.SETTLE_FAILED, SettleReport.status(dryRun = false, CaseSettleService.Outcome(holds, problem = "x")))
        assertEquals("NOT_UNIQUE", SettleReport.status(false, CaseSettleService.Outcome(OperatorSettlement.Verdict.NotUnique("s", emptyList(), listOf("t")))))
    }

    // ---- the command-line jobs, exactly as the script reads them --------------------------------

    @Test
    fun `the text survey prints one line per screen, the tally, the cases and the status, in today's format`() {
        val dir = settingsFolder(tempFolder.newFolder("crawl"))

        val lines = CaseToolJobs.survey(dir).lines()

        assertEquals("=== crawl: 3 captures, 3 usable ===", lines.first())
        // The screen and tally lines keep the e9c6fe5 format: status padded to 14, one space, then the name.
        assertTrue(lines.toString(), lines.containsAll(listOf(
            "HOLDS          screen_00000_home",
            "UNSETTLED      screen_00001_battery",
            "HOLDS          screen_00002_cart",
            "=== tally ===",
            "HOLDS          2",
            "UNSETTLED      1",
            "=== cases ===",
            "surveyUsable=3",
            "surveyUnreadable=0",
        )))
        assertEquals("status=UNSETTLED", lines.last())
        assertTrue(lines.indexOf("HOLDS          screen_00000_home") < lines.indexOf("=== tally ==="))
        assertTrue(lines.indexOf("=== tally ===") < lines.indexOf("=== cases ==="))
    }

    @Test
    fun `the text survey of a crawl whose case file is refused ends INVALID_INPUT`() {
        val dir = settingsFolder(tempFolder.newFolder("crawl"))
        IdentityCaseStore.fileIn(dir).writeText("<identity-cases schema=\"v9\" />")

        val lines = CaseToolJobs.survey(dir).lines()

        assertTrue(lines.toString(), lines.any { it.startsWith("INVALID_INPUT  ") && it.contains(IdentityCaseStore.FILE_NAME) })
        assertEquals("status=INVALID_INPUT", lines.last())
    }

    @Test
    fun `the text survey names each capture it cannot read and ends INVALID_INPUT`() {
        val dir = settingsFolder(tempFolder.newFolder("crawl"))
        File(dir, "screen_00003_broken.xml").writeText("<screen><crawl")

        val lines = CaseToolJobs.survey(dir).lines()

        assertEquals("=== crawl: 4 captures, 3 usable ===", lines.first())
        assertTrue(lines.toString(), lines.any { it.startsWith("INVALID_INPUT  screen_00003_broken.xml: ") })
        assertTrue(lines.contains("surveyUnreadable=1"))
        assertEquals("status=INVALID_INPUT", lines.last())
    }

    @Test
    fun `a command-line settle without -Push never reaches the device, even when adb is named`() {
        val mode = CaseToolMode.select(
            mapOf("a2h.crawl" to "d", "a2h.settle" to "case_000", "a2h.traits" to "t.xml", "a2h.adb" to "adb.exe")::get,
        ) as CaseToolMode.Settle

        assertEquals(null, mode.device)
    }

    @Test
    fun `a command-line push for a crawl that names no package says so and ends INVALID_INPUT`() {
        val dir = tempFolder.newFolder()
        IdentityCaseStore.write(IdentityCaseStore.fileIn(dir), listOf(case("case_000", "screen_00001")))
        val traits = File(tempFolder.root, "traits.xml").apply { writeText(traitsText(CaseFolderFixtures.adaptive)) }
        val settings = CaseToolMode.DeviceSettings(File(tempFolder.root, "adb.exe"), serial = null, remoteDir = null)

        val output = CaseToolJobs.settle(CaseToolMode.Settle(dir, "case_000", traits, dryRun = false, device = settings))

        assertTrue(output, output.startsWith("INVALID_INPUT: cannot push:"))
        assertTrue(output, output.endsWith("status=INVALID_INPUT"))
    }

    @Test
    fun `a failing adb push is reported as a failure`() {
        val adb = fakeAdb(windowsBody = "echo push refused& exit /b 1", unixBody = "echo push refused; exit 1")
        val device = AdbDeviceCrawl(adb, serial = null, remoteDir = "/sdcard/crawl")

        try {
            device.push(File(tempFolder.root, "x.xml").apply { writeText("x") }, "x.xml")
            fail("a failed push was taken for a success")
        } catch (failed: IllegalStateException) {
            assertTrue(failed.message, failed.message!!.contains("push refused"))
        }
    }

    @Test
    fun `a command-line dry run reports what a settle would do and writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val traits = File(tempFolder.root, "traits.xml").apply { writeText(traitsText(CaseFolderFixtures.adaptive)) }
        val before = fingerprint(dir)

        val output = CaseToolJobs.settle(CaseToolMode.Settle(dir, "case_000", traits, dryRun = true, device = null))

        assertTrue(output, output.startsWith("SETTLE case_000 (dry run)"))
        assertTrue(output, output.contains("would write the screen's .xml and .html and mark case_000 handled"))
        assertTrue(output, output.endsWith("status=HOLDS"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `a command-line settle writes, lists what still needs pushing, and ends HOLDS`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val traits = File(tempFolder.root, "traits.xml").apply { writeText(traitsText(CaseFolderFixtures.adaptive)) }

        val output = CaseToolJobs.settle(CaseToolMode.Settle(dir, "case_000", traits, dryRun = false, device = null))

        assertTrue(output, output.contains("written: screen_00001_battery.xml"))
        assertTrue(output, output.contains("still to push, before the crawl resumes:"))
        assertTrue(output, output.endsWith("status=HOLDS"))
        assertEquals(IdentityCaseStatus.HANDLED, CaseCrawl.load(dir).case("case_000")!!.status)
    }

    @Test
    fun `a command-line settle without its traits file says so and ends INVALID_INPUT`() {
        val dir = settingsFolder(tempFolder.newFolder())

        val output = CaseToolJobs.settle(CaseToolMode.Settle(dir, "case_000", File(tempFolder.root, "missing.xml"), dryRun = false, device = null))

        assertTrue(output, output.startsWith("INVALID_INPUT: no traits file at"))
        assertTrue(output, output.endsWith("status=INVALID_INPUT"))
    }

    @Test
    fun `the overview lists cases sorted by id, whatever order the file gives them`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val loaded = CaseCrawl.load(dir)
        val shuffled = CaseCrawl(loaded.directory, loaded.cases.reversed() + case("case_002", "screen_00001"), loaded.screens, loaded.problems)

        val rows = CaseOverview.rows(shuffled)
        val page = CasePages.overview(shuffled, rows, CasePages.DeviceStatus(false, "x"), token)

        assertEquals(listOf("case_000", "case_001", "case_002"), rows.map { it.caseId })
        assertTrue(page.indexOf(">case_000<") < page.indexOf(">case_001<"))
        assertTrue(page.indexOf(">case_001<") < page.indexOf(">case_002<"))
    }

    @Test
    fun `each overview row says what a resume will do with its case`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases.map { if (it.id == "case_001") it.copy(status = IdentityCaseStatus.HANDLED) else it },
        )

        val page = request(serve(dir), "GET", "/").body

        val row000 = page.substringAfter("case_000</a>").substringBefore("</tr>")
        val row001 = page.substringAfter("case_001</a>").substringBefore("</tr>")
        assertTrue(row000, row000.contains("<td>left as it is</td>"))
        assertTrue(row000, row000.contains("<td>proposal-does-not-hold</td>"))
        assertTrue(row001, row001.contains("<td>confirmed on the live screen</td>"))
        assertTrue(row001, row001.contains("<code>screen_00002_cart</code>"))
    }

    @Test
    fun `the case page shows every part of the screen and of the case`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases.map {
                if (it.id == "case_000") it.copy(kind = IdentityCaseKind.UNIQUENESS_COLLISION, relatedScreenIds = listOf("screen_00002")) else it
            },
        )

        val page = request(serve(dir), "GET", "/case/case_000").body
        val tree = page.substringAfter("<summary>First viewport tree</summary><pre>").substringBefore("</pre>")
        val snippet = page.substringAfter("data-snippet=\"").substringBefore("\">")

        assertTrue(page, page.contains("related screen_00002"))
        assertTrue(page, page.contains("<span class=\"badge open\">open</span>"))
        assertTrue(page, page.contains("Device: no adb configured"))
        // The tree: the first scroll step, escaped, holding the screen's own labels.
        assertTrue(tree, tree.startsWith(CasePages.html("<step index=\"0\"")))
        assertTrue(tree, tree.contains("Battery usage"))
        // A snippet is an attribute value: its quotes are escaped, so it cannot close the attribute.
        assertTrue(snippet, snippet.startsWith("&lt;has-control&gt;"))
        assertTrue(snippet, snippet.contains("label=&quot;"))
        assertFalse(snippet, snippet.contains("\""))
    }

    // ---- the server ----------------------------------------------------------------------------

    private fun serve(dir: File, device: DeviceCrawl? = null): CaseToolServer =
        CaseToolServer(dir, token, port = 0, device = { device }).also { it.start(); servers += it }

    private data class Reply(val code: Int, val body: String)

    private fun request(
        server: CaseToolServer,
        method: String,
        path: String,
        body: String? = null,
        withToken: Boolean = true,
        tokenValue: String = token,
    ): Reply {
        val connection = URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (withToken) connection.setRequestProperty(CaseToolServer.TOKEN_HEADER, tokenValue)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
        return Reply(code, text)
    }

    @Test
    fun `every route refuses a request without the right token, and the server listens only on loopback`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = serve(dir)
        val before = fingerprint(dir)

        assertTrue(server.address.address.isLoopbackAddress)
        assertEquals("the script polls 127.0.0.1", "127.0.0.1", server.address.address.hostAddress)
        val routes = listOf(
            "GET" to "/", "GET" to "/health", "GET" to "/case/case_000",
            "POST" to "/case/case_000/check", "POST" to "/case/case_000/settle", "POST" to "/done",
        )
        routes.forEach { (method, path) ->
            val body = if (method == "POST") traitsText(batteryUsage) else null
            assertEquals("$method $path without a token", 403, request(server, method, path, body, withToken = false).code)
            assertEquals("$method $path with a wrong token", 403, request(server, method, path, body, tokenValue = "nope").code)
        }
        // The token must match exactly: empty, a prefix of it, or it with more appended are all refused.
        listOf("", token.dropLast(1), token + "x").forEach { wrong ->
            assertEquals("token \"$wrong\"", 403, request(server, "GET", "/health?token=$wrong", withToken = false).code)
            assertEquals("header \"$wrong\"", 403, request(server, "GET", "/health", tokenValue = wrong).code)
        }
        assertEquals(before, fingerprint(dir))
        assertEquals(200, request(server, "GET", "/health").code)
        assertEquals(200, request(server, "GET", "/?token=$token", withToken = false).code)
    }

    @Test
    fun `the overview lists every case, links the ones that can be worked, and not the closed ones`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases.map { if (it.id == "case_001") it.copy(status = IdentityCaseStatus.HANDLED) else it } +
                case("case_002", "screen_00001", status = IdentityCaseStatus.CLOSED),
        )

        val page = request(serve(dir), "GET", "/").body

        assertTrue(page, page.contains("""<a href="/case/case_000?token=$token">case_000</a>"""))
        // case_001 is handled: it is still linked, so its traits can be settled again before the resume.
        assertTrue(page, page.contains("""<a href="/case/case_001?token=$token">case_001</a>"""))
        assertTrue(page, page.contains("<td>case_002</td>"))
        assertFalse(page, page.contains("/case/case_002"))
        CaseOverview.rows(CaseCrawl.load(dir)).forEach { row ->
            assertTrue(page.contains(CasePages.html(row.detail)))
            assertTrue(page.contains(row.screenName))
        }
    }

    @Test
    fun `an overview of a crawl with no cases says so`() {
        val dir = tempFolder.newFolder()
        writeScreen(dir, "screen_00000", "Home", home)

        assertTrue(request(serve(dir), "GET", "/").body.contains("This crawl has no identity cases."))
    }

    @Test
    fun `the case page carries the case, the screen's xml and the rejected proposal`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val rejected = listOf<Trait>(HasControl(elementOf(home, "open_cart")))
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases.map { if (it.id == "case_000") it.copy(rejectedProposal = rejected) else it },
        )

        val page = request(serve(dir), "GET", "/case/case_000").body

        assertTrue(page, page.contains("<h1>case_000 <span class=\"kind\">proposal-does-not-hold</span></h1>"))
        assertTrue(page, page.contains("screen_00001_battery"))
        assertTrue(page, page.contains(CasePages.html("the proposal asserts nothing")))
        assertTrue(page, page.contains(CasePages.html("""<screen-identity package="com.example.shop"""")))
        assertTrue(page, page.contains("<textarea id=\"traits\""))
        assertTrue(page, page.contains(CasePages.html("""<element label="open_cart"""")))
        assertTrue(page, page.contains(CasePages.html("<!-- has-list:")))
        assertTrue(page, page.contains("copy as has-control"))
    }

    @Test
    fun `a settled-does-not-hold case starts the editor from the screen's current identity`() {
        val dir = settingsFolder(tempFolder.newFolder())

        val page = request(serve(dir), "GET", "/case/case_001").body
        // The recorded identity is also on the screen side of the page, so only the editor's text counts.
        val editor = page.substringAfter("<textarea id=\"traits\" spellcheck=\"false\">").substringBefore("</textarea>")

        assertTrue(editor, editor.contains(CasePages.html("""<has-list container-resource-id="${LifecycleTrees.CART_ITEMS}"""")))
    }

    @Test
    fun `an unknown or closed case gets a page saying so, not an editor`() {
        val dir = settingsFolder(tempFolder.newFolder())
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases + case("case_002", "screen_00001", status = IdentityCaseStatus.CLOSED),
        )
        val server = serve(dir)

        listOf("case_009" to "There is no case case_009", "case_002" to "case_002 is closed").forEach { (caseId, phrase) ->
            val page = request(server, "GET", "/case/$caseId").body
            assertTrue(page, page.contains(phrase))
            assertFalse(page, page.contains("<textarea"))
        }
    }

    @Test
    fun `crawl text reaching a page is escaped`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val hostile = "</td><script>alert('x')</script>\" onmouseover=\"alert(1)"
        IdentityCaseStore.write(
            IdentityCaseStore.fileIn(dir),
            CaseCrawl.load(dir).cases.map { if (it.id == "case_000") it.copy(detail = hostile) else it },
        )
        val server = serve(dir)

        listOf("/", "/case/case_000").forEach { path ->
            val page = request(server, "GET", path).body
            assertFalse("$path carries the raw text", page.contains(hostile))
            assertFalse("$path lets a quote close an attribute", page.contains("\" onmouseover=\""))
            assertTrue(page.contains(CasePages.html(hostile)))
        }
        // An attribute value: a label carrying a quote must stay inside its data-snippet.
        assertEquals("&quot;", CasePages.html("\""))
    }

    @Test
    fun `check answers exactly what the engine answers`() {
        val dir = settingsFolder(tempFolder.newFolder())
        writeScreen(dir, "screen_00003", "Battery", lookalike)
        val server = serve(dir)
        val cases = listOf(
            traitsText(batteryUsage),
            traitsText(HasControl(elementOf(lookalike, "Battery saver"))),
            traitsText(HasControl(elementOf(home, "open_cart"))),
            traitsText(LacksControl(elementOf(home, "open_cart"))),
            traitsText(CaseFolderFixtures.adaptive),
        )

        cases.forEach { text ->
            val expected = CaseToolServer.verdictJson(OperatorSettlement.check(CaseCrawl.load(dir), "case_000", text))
            assertEquals(expected, request(server, "POST", "/case/case_000/check", text).body)
        }
        val holds = request(server, "POST", "/case/case_000/check", traitsText(CaseFolderFixtures.adaptive)).body
        assertTrue(holds, holds.contains("\"holds\":true"))
    }

    @Test
    fun `settle re-checks, so traits that do not hold write nothing even after a passing check`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = serve(dir)
        val good = traitsText(CaseFolderFixtures.adaptive)
        val bad = traitsText(HasControl(elementOf(home, "open_cart")))
        assertTrue(request(server, "POST", "/case/case_000/check", good).body.contains("\"holds\":true"))
        val before = fingerprint(dir)

        val refused = request(server, "POST", "/case/case_000/settle?push=false", bad).body

        assertTrue(refused, refused.contains("\"succeeded\":false"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `settle writes traits that hold, reports the files, and the overview then shows the case handled`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = serve(dir)

        val reply = request(server, "POST", "/case/case_000/settle?push=false", traitsText(CaseFolderFixtures.adaptive)).body

        assertTrue(reply, reply.contains("\"succeeded\":true"))
        assertTrue(reply, reply.contains("\"written\":[\"screen_00001_battery.xml\",\"screen_00001_battery.html\",\"identity-cases.xml\"]"))
        assertEquals(IdentityCaseStatus.HANDLED, CaseCrawl.load(dir).case("case_000")!!.status)
        assertTrue(request(server, "GET", "/").body.contains("""<span class="badge handled">handled</span>"""))
    }

    @Test
    fun `asking to push with no adb configured writes nothing`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val before = fingerprint(dir)

        val reply = request(serve(dir, device = null), "POST", "/case/case_000/settle?push=true", traitsText(CaseFolderFixtures.adaptive)).body

        assertTrue(reply, reply.contains("\"succeeded\":false"))
        assertEquals(before, fingerprint(dir))
    }

    @Test
    fun `done stops the server`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = serve(dir)
        val waiter = thread { server.awaitDone() }

        assertEquals(200, request(server, "POST", "/done").code)
        waiter.join(10_000)

        assertFalse("awaitDone did not return", waiter.isAlive)
        try {
            request(server, "GET", "/health")
            fail("the server still answers after Done")
        } catch (expected: java.io.IOException) {
        }
    }

    @Test
    fun `requests without the token do not keep the server alive`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = CaseToolServer(dir, token, port = 0, device = { null }, idleTimeoutMillis = 300).also { it.start(); servers += it }
        val started = System.currentTimeMillis()
        val waiter = thread { server.awaitDone() }
        // Keep knocking well past the idle timeout; the server must stop while the knocking goes on.
        while (waiter.isAlive && System.currentTimeMillis() - started < 5_000) {
            runCatching { request(server, "GET", "/health", withToken = false) }
            Thread.sleep(50)
        }
        val stoppedAfter = System.currentTimeMillis() - started

        assertFalse("unauthorized requests kept the server alive", waiter.isAlive)
        assertTrue("stopped only after ${stoppedAfter}ms, when the knocking ended", stoppedAfter < 3_000)
    }

    @Test
    fun `the server stops itself after its idle timeout`() {
        val dir = settingsFolder(tempFolder.newFolder())
        val server = CaseToolServer(dir, token, port = 0, device = { null }, idleTimeoutMillis = 200).also { it.start(); servers += it }
        val waiter = thread { server.awaitDone() }

        waiter.join(10_000)

        assertFalse("the idle timeout did not stop the server", waiter.isAlive)
    }
}
