package com.kernfolio

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.kernfolio.domain.Instrument
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.InviteCodeRepository
import com.kernfolio.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.openqa.selenium.By
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.logging.LogType
import org.openqa.selenium.logging.LoggingPreferences
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.support.ui.WebDriverWait
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.Testcontainers
import org.testcontainers.containers.BrowserWebDriverContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.logging.Level

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserFlowTest {

    companion object {
        private val wireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

        private const val BROWSER_LOCALE = "sv-SE"

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("optimizer.base-url") { "http://localhost:${wireMockServer.port()}" }
            registry.add("market-data.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }

    @LocalServerPort var port: Int = 0

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var cachedPriceRepository: CachedPriceRepository
    @Autowired lateinit var cachedFxRateRepository: CachedFxRateRepository
    @Autowired lateinit var instrumentRepository: InstrumentRepository

    private lateinit var chrome: BrowserWebDriverContainer<*>
    private lateinit var driver: RemoteWebDriver
    private lateinit var wait: WebDriverWait

    private var adminInviteCode: String? = null
    private var user2InviteCode: String? = null
    private var portfolioPath: String? = null
    private var resultsPath: String? = null

    @BeforeAll
    fun setup() {
        Testcontainers.exposeHostPorts(port)

        val chromeOptions = ChromeOptions().apply {
            addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu")
            // Pin a non-US locale. All user-visible numbers are formatted in
            // the browser (format.js), so under sv-SE anything that bypasses
            // the formatter — server-rendered or raw toFixed — shows up with
            // a "." decimal and fails the exact-string assertions below.
            addArguments("--lang=$BROWSER_LOCALE")
            setExperimentalOption("prefs", mapOf("intl.accept_languages" to BROWSER_LOCALE))
            val logPrefs = LoggingPreferences()
            logPrefs.enable(LogType.BROWSER, Level.ALL)
            setCapability("goog:loggingPrefs", logPrefs)
        }

        chrome = BrowserWebDriverContainer<Nothing>()
            .withCapabilities(chromeOptions)
        chrome.withEnv("LANG", "sv_SE.UTF-8")
        chrome.withEnv("LANGUAGE", "sv_SE:sv")
        chrome.start()

        driver = RemoteWebDriver(chrome.seleniumAddress, chromeOptions)
        wait = WebDriverWait(driver, Duration.ofSeconds(15))

        // Fail loudly if the pin silently fell back to the image default.
        val resolved = driver.executeScript("return new Intl.NumberFormat().resolvedOptions().locale") as String
        check(resolved.startsWith("sv")) { "Browser locale not pinned: expected sv-SE, got $resolved" }
    }

    @AfterAll
    fun teardown() {
        if (::driver.isInitialized) driver.quit()
        if (::chrome.isInitialized) chrome.stop()
    }

    private fun baseUrl() = "http://host.testcontainers.internal:$port"

    private fun navigateTo(path: String) {
        driver.get("${baseUrl()}$path")
    }

    private fun fillField(id: String, value: String) {
        val el = driver.findElement(By.id(id))
        el.clear()
        el.sendKeys(value)
    }

    private fun waitForUrl(containsPath: String) {
        wait.until(ExpectedConditions.urlContains(containsPath))
    }

    private fun waitForPortfolioDetail() {
        wait.until { driver.currentUrl?.matches(Regex(".*/portfolios/[0-9a-f-]{36}$")) == true }
    }

    // sv-SE uses no-break spaces for digit grouping and before "%". Fold
    // them to plain spaces so expected strings stay readable.
    private fun visibleText(by: By): String =
        driver.findElement(by).text.replace('\u00a0', ' ').replace('\u202f', ' ')

    private fun assertPageContains(text: String) {
        assertThat(driver.pageSource).contains(text)
    }

    private fun loginAs(username: String, password: String) {
        driver.manage().deleteAllCookies()
        navigateTo("/login")
        fillField("username", username)
        fillField("password", password)
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForUrl("/dashboard")
    }

    // ── Bootstrap ────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `bootstrap provides invite code`() {
        adminInviteCode = inviteCodeRepository.findAllValid().first().code
        assertThat(adminInviteCode).isNotBlank()
    }

    // ── Registration & login ─────────────────────────────────────────

    @Test
    @Order(2)
    fun `register admin via browser form`() {
        navigateTo("/register")
        assertPageContains("Create an account")

        fillField("username", "badmin")
        fillField("email", "badmin@browser.test")
        fillField("password", "Br0wserP@ss!")
        fillField("confirmPassword", "Br0wserP@ss!")
        fillField("inviteCode", adminInviteCode!!)
        driver.findElement(By.cssSelector("button[type='submit']")).click()

        waitForUrl("/login")
        assertPageContains("Registration successful")
        assertThat(userRepository.findByUsername("badmin")!!.role).isEqualTo("ADMIN")
    }

    @Test
    @Order(3)
    fun `login as admin`() {
        loginAs("badmin", "Br0wserP@ss!")
        assertPageContains("Your Portfolios")
    }

    @Test
    @Order(4)
    fun `admin generates invite code via browser`() {
        navigateTo("/admin/users")
        driver.findElement(By.cssSelector("form[action='/admin/users/invite'] button[type='submit']")).click()
        waitForUrl("/admin/users")

        val codeEl = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".font-mono.font-bold.tracking-wider"))
        )
        user2InviteCode = codeEl.text
        assertThat(user2InviteCode).isNotBlank()
    }

    @Test
    @Order(5)
    fun `register non-admin user`() {
        navigateTo("/register")
        fillField("username", "buser")
        fillField("email", "buser@browser.test")
        fillField("password", "BuserP@ss123")
        fillField("confirmPassword", "BuserP@ss123")
        fillField("inviteCode", user2InviteCode!!)
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForUrl("/login")
        assertThat(userRepository.findByUsername("buser")!!.role).isEqualTo("USER")
    }

    @Test
    @Order(6)
    fun `login as non-admin user`() {
        loginAs("buser", "BuserP@ss123")
        assertPageContains("Your Portfolios")
    }

    // ── Portfolio CRUD ───────────────────────────────────────────────

    @Test
    @Order(7)
    fun `create portfolio via browser form`() {
        navigateTo("/portfolios/new")
        fillField("name", "Browser Portfolio")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()

        waitForPortfolioDetail()
        portfolioPath = driver.currentUrl!!.replace(baseUrl(), "")
        assertPageContains("Browser Portfolio")
    }

    // ── Position CRUD via JS ─────────────────────────────────────────

    @Test
    @Order(8)
    fun `add positions via JS AJAX`() {
        navigateTo(portfolioPath!!)

        // Save is blocked when the total portfolio value isn't set — weight_pct
        // would be undefined and we'd persist 0%. Enter a total first.
        val totalInput = driver.findElement(By.id("total-value-input"))
        totalInput.sendKeys("100000")

        data class Pos(val ticker: String, val currency: String, val sector: String)
        val positions = listOf(
            Pos("GOOG", "USD", "Technology"),
            Pos("CSU.TO", "CAD", "Technology"),
            Pos("8PSB", "GBP", "Commodities"),
        )

        for (pos in positions) {
            driver.findElement(By.id("add-position-btn")).click()

            val newRow = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
            )

            newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys(pos.ticker)
            val currField = newRow.findElement(By.cssSelector(".js-currency"))
            currField.clear()
            currField.sendKeys(pos.currency)
            val sectorField = newRow.findElement(By.cssSelector("[name='sector']"))
            sectorField.clear()
            sectorField.sendKeys(pos.sector)

            newRow.findElement(By.cssSelector(".js-save-position")).click()

            wait.until(ExpectedConditions.stalenessOf(newRow))
            wait.until(
                ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//td[contains(@class, 'font-mono') and contains(text(), '${pos.ticker}')]")
                )
            )
        }

        assertPageContains("GOOG")
        assertPageContains("CSU.TO")
        assertPageContains("8PSB")
    }

    @Test
    @Order(9)
    fun `position type toggle shows and hides fields`() {
        driver.findElement(By.id("add-position-btn")).click()

        val newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )

        // Equity fields visible, cash fields hidden
        val equityField = newRow.findElement(By.cssSelector(".js-ticker-input"))
        assertThat(equityField.isDisplayed).isTrue()

        // Switch to CASH
        val typeSelect = Select(newRow.findElement(By.cssSelector(".js-pos-type")))
        typeSelect.selectByValue("CASH")

        wait.until { !equityField.isDisplayed }
        val cashField = newRow.findElement(By.cssSelector(".js-cash-amount"))
        assertThat(cashField.isDisplayed).isTrue()

        // Cancel to clean up
        newRow.findElement(By.cssSelector(".js-cancel-row")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))
    }

    @Test
    @Order(10)
    fun `delete position via JS confirm dialog`() {
        val deleteBtns = driver.findElements(By.cssSelector("[data-delete-position]"))
        assertThat(deleteBtns).isNotEmpty()

        val lastBtn = deleteBtns.last()
        val row = lastBtn.findElement(By.xpath("./ancestor::tr"))
        lastBtn.click()

        driver.switchTo().alert().accept()
        wait.until(ExpectedConditions.stalenessOf(row))

        // Re-add position
        driver.findElement(By.id("add-position-btn")).click()
        val newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys("8PSB")
        val currField = newRow.findElement(By.cssSelector(".js-currency"))
        currField.clear()
        currField.sendKeys("GBP")
        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))
    }

    @Test
    @Order(11)
    fun `edit portfolio via browser`() {
        navigateTo("$portfolioPath/edit")
        assertPageContains("Edit Portfolio")
        fillField("name", "Browser Portfolio Edited")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForUrl(portfolioPath!!)
        assertPageContains("Browser Portfolio Edited")
    }

    // ── Optimization ─────────────────────────────────────────────────

    @Test
    @Order(12)
    fun `seed market data for optimization`() {
        instrumentRepository.save(Instrument("GOOG", "Alphabet", "NASDAQ", "USD", "Technology", BigDecimal("2000000000000")))
        instrumentRepository.save(Instrument("CSU.TO", "Constellation Software", "TSX", "CAD", "Technology", BigDecimal("70000000000")))
        instrumentRepository.save(Instrument("8PSB", "Physical Silver ETC", "LSE", "GBP", "Commodities", BigDecimal("500000000")))

        // OptimizerService.ensureCoverage backfills missing FX history
        // through Frankfurter before running conversion. The 10-day FX
        // seed below doesn't cover the 5-year lookback, so an old-side
        // gap fetch fires. Stub an empty response to keep the optimizer
        // using whatever's already cached.
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""{"rates": {}}""")
                )
        )

        val dates = (1L..10L).map { LocalDate.now().minusDays(it) }
        for (date in dates) {
            cachedPriceRepository.upsert("GOOG", date, BigDecimal("175.00"), "USD")
            cachedPriceRepository.upsert("CSU.TO", date, BigDecimal("4200.00"), "CAD")
            cachedPriceRepository.upsert("8PSB", date, BigDecimal("45.00"), "GBP")
            cachedFxRateRepository.upsert("EURUSD", date, BigDecimal("1.08500000"))
            cachedFxRateRepository.upsert("EURCAD", date, BigDecimal("1.47000000"))
            cachedFxRateRepository.upsert("EURGBP", date, BigDecimal("0.86000000"))
        }
    }

    @Test
    @Order(13)
    fun `optimize form - advanced toggle and JS submission`() {
        val optimizeResponseJson = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/optimize-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/optimize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(optimizeResponseJson)
                )
        )

        navigateTo("$portfolioPath/optimize")
        assertPageContains("Optimize Portfolio")

        // Verify advanced panel is hidden
        val panel = driver.findElement(By.id("advanced-panel"))
        assertThat(panel.getAttribute("class") ?: "").contains("hidden")

        // Toggle advanced params
        driver.findElement(By.id("advanced-toggle")).click()
        wait.until { !(panel.getAttribute("class") ?: "").contains("hidden") }

        // Submit optimization
        driver.findElement(By.cssSelector("#optimize-form button[type='submit']")).click()

        // Spinner should appear
        wait.until {
            val spinner = driver.findElement(By.id("spinner"))
            !(spinner.getAttribute("class") ?: "").contains("hidden")
        }

        // Wait for redirect to results
        waitForUrl("/results/")
        resultsPath = driver.currentUrl!!.replace(baseUrl(), "")
    }

    @Test
    @Order(14)
    fun `results page renders charts`() {
        navigateTo(resultsPath!!)
        assertPageContains("Optimization Results")
        assertPageContains("black_litterman")

        // Wait for Chart.js canvases
        val pie = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("allocation-pie")))
        val bar = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("allocation-bar")))

        wait.until { pie.size.width > 0 && pie.size.height > 0 }
        wait.until { bar.size.width > 0 && bar.size.height > 0 }

        // Verify Chart.js actually rendered
        val pieRendered = driver.executeScript(
            "return typeof Chart !== 'undefined' && Chart.getChart(document.getElementById('allocation-pie')) !== undefined"
        ) as Boolean
        assertThat(pieRendered).isTrue()

        // Metric cards and the weights table are filled client-side from
        // the JSON API, formatted in the (sv-SE) browser locale. Values
        // come from fixtures/wiremock/__files/optimize-response.json.
        wait.until { visibleText(By.id("metric-expected-return")) == "9,20 %" }
        assertThat(visibleText(By.id("metric-volatility"))).isEqualTo("17,80 %")
        assertThat(visibleText(By.id("metric-sharpe"))).isEqualTo("0,32")
        // Minus sign is U+2212 in sv-SE, so match on the digits.
        assertThat(visibleText(By.id("metric-cvar95"))).endsWith("2,80 %")
        assertThat(visibleText(By.id("run-created-at"))).isNotBlank()

        assertPageContains("Optimized Weights")
        wait.until { visibleText(By.id("weights-tbody")).contains("GOOG") }
        val weightsText = visibleText(By.id("weights-tbody"))
        assertThat(weightsText).containsPattern("\\d+,\\d{2} %")
        assertThat(weightsText).doesNotContainPattern("\\d+\\.\\d{2}\\s?%")
    }

    @Test
    @Order(15)
    fun `discrete allocation reads localStorage`() {
        driver.executeScript("localStorage.setItem('totalValue', '100000')")
        driver.executeScript(
            "localStorage.setItem('currentHoldings', JSON.stringify({GOOG: 100, 'CSU.TO': 10, '8PSB': 50}))"
        )
        driver.navigate().refresh()

        wait.until {
            driver.findElement(By.id("discrete-allocation")).text.isNotBlank()
        }
    }

    // ── Admin JS features ────────────────────────────────────────────

    @Test
    @Order(16)
    fun `admin toggle user via JS fetch`() {
        loginAs("badmin", "Br0wserP@ss!")
        navigateTo("/admin/users")

        // Find Disable button for buser
        val disableBtn = wait.until(
            ExpectedConditions.elementToBeClickable(
                By.xpath("//tr[contains(., 'buser')]//button[@data-toggle-user][@data-enabled='false']")
            )
        )
        disableBtn.click()

        // Wait for row to be replaced — button should now say Enable
        wait.until(
            ExpectedConditions.presenceOfElementLocated(
                By.xpath("//tr[contains(., 'buser')]//button[@data-toggle-user][@data-enabled='true']")
            )
        )

        // Re-enable
        val enableBtn = driver.findElement(
            By.xpath("//tr[contains(., 'buser')]//button[@data-toggle-user][@data-enabled='true']")
        )
        enableBtn.click()
        wait.until(
            ExpectedConditions.presenceOfElementLocated(
                By.xpath("//tr[contains(., 'buser')]//button[@data-toggle-user][@data-enabled='false']")
            )
        )
    }

    @Test
    @Order(17)
    fun `admin update feature flag via JS fetch`() {
        navigateTo("/admin/flags")

        val saveBtn = wait.until(
            ExpectedConditions.elementToBeClickable(By.cssSelector("[data-update-flag]"))
        )
        val flagRow = saveBtn.findElement(By.xpath("./ancestor::tr"))
        val rolloutInput = flagRow.findElement(By.cssSelector("input[name='rolloutPct']"))
        rolloutInput.clear()
        rolloutInput.sendKeys("75")

        saveBtn.click()
        wait.until(ExpectedConditions.stalenessOf(flagRow))

        // Verify new row has the updated value
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-update-flag]")))
    }

    // ── Delete portfolio via confirm ─────────────────────────────────

    @Test
    @Order(18)
    fun `delete portfolio via confirm dialog`() {
        loginAs("buser", "BuserP@ss123")
        navigateTo(portfolioPath!!)

        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
        assertPageContains("No portfolios yet")
    }

    // ── XSS encoding validation ──────────────────────────────────────

    @Test
    @Order(30)
    fun `XSS in portfolio name is escaped`() {
        val xssPayload = "<script>window.__xss=1</script><img onerror=alert(1) src=x>"

        navigateTo("/portfolios/new")
        fillField("name", xssPayload)
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()

        val xssPortfolioPath = driver.currentUrl!!.replace(baseUrl(), "")

        // Script should NOT have executed
        val xssFlag = driver.executeScript("return window.__xss")
        assertThat(xssFlag).isNull()

        // The payload text should be visible as escaped text
        assertThat(driver.pageSource).contains("&lt;script&gt;")
        assertThat(driver.pageSource).doesNotContain("<script>window.__xss")

        // Clean up
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(31)
    fun `XSS in portfolio description is escaped`() {
        val xssPayload = "\"><style>body{display:none}</style><svg onload=alert(1)>"

        navigateTo("/portfolios/new")
        fillField("name", "XSS Desc Test")
        driver.findElement(By.id("description")).sendKeys(xssPayload)
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()

        // Body should still be visible (style injection failed)
        val bodyVisible = driver.executeScript(
            "return window.getComputedStyle(document.body).display !== 'none'"
        ) as Boolean
        assertThat(bodyVisible).isTrue()

        // SVG onload should not have fired
        assertThat(driver.pageSource).doesNotContain("<svg onload")

        // Clean up
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(32)
    fun `XSS in position fields is escaped`() {
        // Create a portfolio for this test
        navigateTo("/portfolios/new")
        fillField("name", "XSS Position Test")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()
        val xssPfPath = driver.currentUrl!!.replace(baseUrl(), "")

        // Save is blocked without a total portfolio value set.
        driver.findElement(By.id("total-value-input")).sendKeys("100000")

        // Add position with XSS payloads. The name column is no longer a
        // user-editable field — it's derived from the `instruments` table at
        // render time — so this test now only covers the ticker and sector
        // inputs. Name-escaping is exercised against `instruments.name` via
        // template output-escaping (see partial/position-saved-row.kte,
        // which renders `${displayName}` via JTE's default HTML escape).
        driver.findElement(By.id("add-position-btn")).click()
        val newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        val tickerField = newRow.findElement(By.cssSelector(".js-ticker-input"))
        tickerField.sendKeys("<b>XSS</b>")
        val sectorField = newRow.findElement(By.cssSelector("[name='sector']"))
        sectorField.sendKeys("<img/onerror=alert(1)>")
        val currField = newRow.findElement(By.cssSelector(".js-currency"))
        currField.clear()
        currField.sendKeys("USD")

        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))

        // Reload to see server-rendered output
        navigateTo(xssPfPath)

        // Payloads should be escaped, not interpreted
        assertThat(driver.pageSource).doesNotContain("<img/onerror")
        val xssExec = driver.executeScript("return window.__xss")
        assertThat(xssExec).isNull()

        // Clean up
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(33)
    fun `XSS in registration username is escaped in admin view`() {
        // Generate invite code
        loginAs("badmin", "Br0wserP@ss!")
        navigateTo("/admin/users")
        driver.findElement(By.cssSelector("form[action='/admin/users/invite'] button[type='submit']")).click()
        waitForUrl("/admin/users")
        val codeEl = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".font-mono.font-bold.tracking-wider"))
        )
        val xssInviteCode = codeEl.text

        // Register with XSS username
        navigateTo("/register")
        fillField("username", "<script>alert(1)</script>")
        fillField("email", "xss@browser.test")
        fillField("password", "XssP@ss1234")
        fillField("confirmPassword", "XssP@ss1234")
        fillField("inviteCode", xssInviteCode)
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForUrl("/login")

        // Login as admin and check admin/users page
        loginAs("badmin", "Br0wserP@ss!")
        navigateTo("/admin/users")

        // The script tag should be visible as text, not executed
        assertThat(driver.pageSource).doesNotContain("<script>alert(1)</script>")
        assertThat(driver.pageSource).contains("&lt;script&gt;")
    }

    @Test
    @Order(34)
    fun `XSS in invite code field is escaped in error page`() {
        navigateTo("/register")
        fillField("username", "safename")
        fillField("email", "safe@browser.test")
        fillField("password", "SafeP@ss123")
        fillField("confirmPassword", "SafeP@ss123")
        fillField("inviteCode", "<script>alert('xss')</script>")
        driver.findElement(By.cssSelector("button[type='submit']")).click()

        // Should show error page (invalid invite code) — wait for re-render since URL stays /register
        wait.until { driver.pageSource?.contains("Invalid or expired") == true }
        assertPageContains("Invalid or expired")
        assertThat(driver.pageSource).doesNotContain("<script>alert('xss')</script>")
    }

    @Test
    @Order(35)
    fun `template injection probe is escaped`() {
        navigateTo("/portfolios/new")
        fillField("name", "{{7*7}} \${7*7}")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()

        // Should see the literal text, not "49"
        assertPageContains("{{7*7}}")
        assertThat(driver.pageSource).doesNotContain(">49<")

        // Clean up
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(36)
    fun `weight display covers cash and equity in base and foreign currency`() {
        // Seed independent instruments + FX so this test doesn't depend on
        // the optimization suite's data (and can't be poisoned by it).
        val today = LocalDate.now()
        cachedFxRateRepository.upsert("EURSEK", today, BigDecimal("11.00000000"))
        instrumentRepository.save(Instrument("WTSTEU", "Weight Test EUR", "TEST", "EUR", "Test", BigDecimal("0")))
        instrumentRepository.save(Instrument("WTSTSE", "Weight Test SEK", "TEST", "SEK", "Test", BigDecimal("0")))
        cachedPriceRepository.upsert("WTSTEU", today, BigDecimal("100.00"), "EUR")
        cachedPriceRepository.upsert("WTSTSE", today, BigDecimal("1100.00"), "SEK")

        navigateTo("/portfolios/new")
        fillField("name", "Weight Display Test")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()
        val pfPath = driver.currentUrl!!.replace(baseUrl(), "")

        driver.findElement(By.id("total-value-input")).sendKeys("100000")

        // Each case is rigged to land at exactly 10,00 % (sv-SE) so an exact string
        // match suffices. Covers the four quadrants: cash × equity × base ×
        // foreign currency. EURSEK=11.00, so SEK amounts/prices are 11× the
        // EUR equivalent.
        data class Case(
            val type: String,
            val ticker: String,
            val currency: String,
            val amountOrShares: String,
            val rowClue: String,
        )
        val cases = listOf(
            Case("CASH", "", "EUR", "10000", "CASH.EUR"),
            Case("CASH", "", "SEK", "110000", "CASH.SEK"),
            Case("EQUITY", "WTSTEU", "EUR", "100", "WTSTEU"),
            Case("EQUITY", "WTSTSE", "SEK", "100", "WTSTSE"),
        )

        for (c in cases) {
            driver.findElement(By.id("add-position-btn")).click()
            val newRow = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
            )
            Select(newRow.findElement(By.cssSelector(".js-pos-type"))).selectByValue(c.type)

            val currField = newRow.findElement(By.cssSelector(".js-currency"))
            currField.clear()
            currField.sendKeys(c.currency)

            if (c.type == "EQUITY") {
                newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys(c.ticker)
                newRow.findElement(By.cssSelector(".js-shares")).sendKeys(c.amountOrShares)
            } else {
                newRow.findElement(By.cssSelector(".js-cash-amount")).sendKeys(c.amountOrShares)
            }

            newRow.findElement(By.cssSelector(".js-save-position")).click()
            wait.until(ExpectedConditions.stalenessOf(newRow))

            val savedRow = wait.until(
                ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//tr[.//td[contains(normalize-space(), '${c.rowClue}')]]")
                )
            )
            // Weights are rendered client-side once the post-save refresh
            // lands, so wait for the text rather than asserting immediately.
            val rowBy = By.xpath("//tr[.//td[contains(normalize-space(), '${c.rowClue}')]]")
            try {
                wait.until { visibleText(rowBy).contains("10,00 %") }
            } catch (e: org.openqa.selenium.TimeoutException) {
                throw AssertionError(
                    "weight for ${c.type} ${c.currency} (${c.rowClue}): expected \"10,00 %\" in \"${visibleText(rowBy)}\"", e
                )
            }
        }

        // Clean up so this portfolio doesn't clutter later tests.
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(37)
    fun `total portfolio value auto-bumps and allocation progress reflects positions`() {
        val today = LocalDate.now()
        instrumentRepository.save(Instrument("PGRSEU", "Progress Test EUR", "TEST", "EUR", "Test", BigDecimal("0")))
        instrumentRepository.save(Instrument("PGRSE2", "Progress Test EUR 2", "TEST", "EUR", "Test", BigDecimal("0")))
        cachedPriceRepository.upsert("PGRSEU", today, BigDecimal("100.00"), "EUR")
        cachedPriceRepository.upsert("PGRSE2", today, BigDecimal("100.00"), "EUR")

        navigateTo("/portfolios/new")
        fillField("name", "Progress Meter Test")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()
        val pfPath = driver.currentUrl!!.replace(baseUrl(), "")

        val totalInput = driver.findElement(By.id("total-value-input"))

        // No total entered — saving a position should auto-bump the total
        // to the position's base-currency value.
        driver.findElement(By.id("add-position-btn")).click()
        var newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        Select(newRow.findElement(By.cssSelector(".js-pos-type"))).selectByValue("CASH")
        val currField = newRow.findElement(By.cssSelector(".js-currency"))
        currField.clear()
        currField.sendKeys("EUR")
        newRow.findElement(By.cssSelector(".js-cash-amount")).sendKeys("500")
        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))

        wait.until { (totalInput.getAttribute("value") ?: "") == "500" }
        // The progress text renders only after the save + rebalance round
        // trips finish, which is later than the total-input bump above.
        wait.until {
            visibleText(By.id("allocation-progress-text")).contains("100 % allocated")
        }

        // Manually set a larger total so the next position lands below 100%
        // and the progress meter shows a non-zero "remaining".
        totalInput.clear()
        totalInput.sendKeys("2000")

        driver.findElement(By.id("add-position-btn")).click()
        newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys("PGRSEU")
        newRow.findElement(By.cssSelector(".js-shares")).sendKeys("5")  // 5 × 100 EUR = 500 EUR
        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))

        // Filled is now 500 EUR cash + 500 EUR equity = 1000 of 2000.
        wait.until {
            val t = visibleText(By.id("allocation-progress-text"))
            t.contains("50,00 %") && t.contains("remaining")
        }
        assertThat(visibleText(By.id("allocation-progress-text"))).contains("1 000,00 EUR")

        // Over-allocating via a new equity position should bump the total up
        // again so filled matches.
        driver.findElement(By.id("add-position-btn")).click()
        newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        // Different ticker so this is an ADD, not an overwrite of the 5-share
        // PGRSEU position from the previous step. 20 × 100 = 2000 EUR on top
        // of the existing 1000 EUR → 3000 EUR total.
        newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys("PGRSE2")
        newRow.findElement(By.cssSelector(".js-shares")).sendKeys("20")
        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))

        wait.until { (totalInput.getAttribute("value") ?: "").startsWith("3000") }
        wait.until {
            visibleText(By.id("allocation-progress-text")).contains("100 % allocated")
        }

        // Clean up
        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(38)
    fun `intrinsic value, implied CAGR and confidence render in the browser locale`() {
        instrumentRepository.save(Instrument("IVTEST", "IV Locale Test", "TEST", "EUR", "Test", BigDecimal("0")))
        cachedPriceRepository.upsert("IVTEST", LocalDate.now(), BigDecimal("1000.00"), "EUR")

        navigateTo("/portfolios/new")
        fillField("name", "IV Locale Test")
        fillField("baseCurrency", "EUR")
        driver.findElement(By.cssSelector("button[type='submit']")).click()
        waitForPortfolioDetail()

        driver.findElement(By.id("add-position-btn")).click()
        val newRow = wait.until(
            ExpectedConditions.presenceOfElementLocated(By.cssSelector("tr[data-new-row]"))
        )
        val currField = newRow.findElement(By.cssSelector(".js-currency"))
        currField.clear()
        currField.sendKeys("EUR")
        newRow.findElement(By.cssSelector(".js-ticker-input")).sendKeys("IVTEST")
        newRow.findElement(By.cssSelector(".js-shares")).sendKeys("1")
        // IV 2000 vs price 1000 → (2000/1000)^(1/5) - 1 = 14.87 %/yr.
        newRow.findElement(By.cssSelector(".js-iv")).sendKeys("2000")
        newRow.findElement(By.cssSelector(".js-confidence")).sendKeys("80")
        newRow.findElement(By.cssSelector(".js-save-position")).click()
        wait.until(ExpectedConditions.stalenessOf(newRow))

        val rowBy = By.xpath("//tr[.//td[contains(normalize-space(), 'IVTEST')]]")
        wait.until { visibleText(rowBy).contains("14,87 %/yr over 5y") }
        val rowText = visibleText(rowBy)
        assertThat(rowText).contains("2 000,00")   // IV/share, grouped + comma decimal
        assertThat(rowText).contains("80 %")       // confidence
        assertThat(rowText).doesNotContain("2,000.00").doesNotContain("14.87")

        driver.findElement(By.cssSelector("form[data-confirm] button[type='submit']")).click()
        driver.switchTo().alert().accept()
        waitForUrl("/dashboard")
    }

    @Test
    @Order(90)
    fun `no browser console errors from entire test run`() {
        // Navigate to a page to ensure logs are flushed
        navigateTo("/login")

        try {
            val logs = driver.manage().logs().get(LogType.BROWSER)
            val severeErrors = logs.filter { it.level.intValue() >= Level.SEVERE.intValue() }
            // Filter out known non-issues (e.g., favicon 404)
            val realErrors = severeErrors.filter { !it.message.contains("favicon.ico") }
            assertThat(realErrors).isEmpty()
        } catch (_: Exception) {
            // Some WebDriver configs don't support log retrieval — skip gracefully
        }
    }
}
