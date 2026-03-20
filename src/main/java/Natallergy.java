import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.JavascriptExecutor;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.time.Duration;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Natallergy {



    static String IMGBB_API_KEY = "3b23b07a37fbcee41d4984d100162a10";
    static String RUN_DATE = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    static String RUN_TIME = new SimpleDateFormat("HH-mm-ss").format(new Date());
    static int totalSteps = 0;
    static int passedSteps = 0;
    static int failedSteps = 0;
    static String START_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    static List<String> htmlSteps = new ArrayList<>();

    static String SS_DIR = "C:\\Users\\deepa\\Documents\\Automation\\Natallergy\\screenshots\\" + RUN_DATE + "\\" + RUN_TIME;
    static String HTML_DIR = "C:\\Users\\deepa\\Documents\\Automation\\Natallergy\\html\\" + RUN_DATE + "\\" + RUN_TIME;
    static String CSV_PATH = "C:\\Users\\deepa\\Documents\\Automation\\Natallergy\\Natallergy.csv";

    /** Storefront used for browsing, checkout, and rewriting sitemap &lt;loc&gt; hosts when they differ (e.g. multi-store). */
    static final String SITE_BASE = "https://www.natlallergy.com";

    private static final String SITEMAP_URL = SITE_BASE + "/sitemap.xml";
    private static final Pattern SITEMAP_LOC_PATTERN = Pattern.compile("<loc>\\s*([^<]+?)\\s*</loc>", Pattern.CASE_INSENSITIVE);
    /** Cap how many sitemap product pages we open while filling the cart (avoids an endless run). */
    private static final int MAX_SITEMAP_PRODUCT_TRIES = 50;
    private static final Duration ADD_TO_CART_STOCK_WAIT = Duration.ofSeconds(28);
    /** Before checkout, add this many distinct random in-stock products (inclusive range). */
    private static final int MIN_PRODUCTS_BEFORE_CHECKOUT = 3;
    private static final int MAX_PRODUCTS_BEFORE_CHECKOUT = 4;

    /**
     * Builds a visible Chrome session. Uses WebDriverManager so driver download/version resolution does not rely on
     * Selenium Manager alone (often hangs behind corporate networks, Defender, or OneDrive-synced projects).
     * {@code --remote-allow-origins=*} avoids Chrome 111+ CDP handshake stalls with some Selenium pairings.
     */
    private static WebDriver createChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--window-position=0,0");
        options.addArguments("--disable-search-engine-choice-screen");
        options.addArguments("--remote-allow-origins=*");

        String manualDriver = System.getProperty("webdriver.chrome.driver");
        if (manualDriver != null && !manualDriver.isBlank()) {
            System.out.println("[Natallergy] Using chromedriver from -Dwebdriver.chrome.driver=" + manualDriver);
        } else {
            System.out.println("[Natallergy] Resolving ChromeDriver (WebDriverManager; first run may download driver)…");
            System.out.flush();
            WebDriverManager.chromedriver().setup();
        }
        System.out.flush();

        System.out.println("[Natallergy] Launching Chrome window…");
        System.out.flush();
        return new ChromeDriver(options);
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        WebDriver driver = createChromeDriver();
        try {
            driver.manage().window().maximize();
            System.out.println("[Natallergy] Chrome should be visible. Loading storefront: " + SITE_BASE + "/");
            driver.get(SITE_BASE + "/");
            waitForPageFullyLoaded(driver);

            System.out.println("[Natallergy] Downloading & parsing sitemap.xml (often 30–120 s, ~1 MB). "
                    + "The browser may sit on the homepage until this finishes — this is normal.");
            List<String> productUrls = discoverProductUrlsFromSitemap();
            if (productUrls.isEmpty()) {
                throw new IllegalStateException("No product URLs matched filters from " + SITEMAP_URL);
            }
            System.out.println("[Natallergy] Sitemap ready: " + productUrls.size() + " product URLs. Capturing homepage screenshot…");

            takeScreenshot(driver, "homepage");
            product(driver, args, productUrls);
        } finally {
            driver.quit();
        }
    }

    private static void safeClick(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    private static void safeClick(WebDriver driver, WebDriverWait wait, By locator) {
        safeClick(driver, wait.until(ExpectedConditions.elementToBeClickable(locator)));
    }

    /**
     * Magento checkout inputs are often read-only until focused or bound with Knockout — {@link WebElement#clear()}
     * can throw {@link InvalidElementStateException}. Sets value via JS and dispatches input/change.
     */
    private static void fillTextInput(WebDriver driver, WebElement element, String text) {
        ((JavascriptExecutor) driver).executeScript(
                "var e=arguments[0], t=arguments[1];"
                        + "e.removeAttribute('readonly'); e.removeAttribute('disabled');"
                        + "e.focus(); e.value=t;"
                        + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                        + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                        + "e.dispatchEvent(new Event('blur',{bubbles:true}));",
                element, text);
    }

    /** Magento checkout shows loading masks/spinners; clicking Next while they run can leave fields empty in the UI. */
    private static void waitForCheckoutSpinnersGone(WebDriver driver) {
        WebDriverWait w = new WebDriverWait(driver, Duration.ofSeconds(60));
        w.until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                "return typeof jQuery === 'undefined' || jQuery.active === 0")));
        w.until(d -> {
            List<WebElement> spinners = d.findElements(By.cssSelector(
                    ".loading-mask, .opc-block-shipping-information .loading-mask, "
                            + "#checkout-step-shipping .loading-mask, .field._field-loading .spinner"));
            for (WebElement s : spinners) {
                try {
                    if (s.isDisplayed()) {
                        return false;
                    }
                } catch (StaleElementReferenceException ignored) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * After shipping → payment, wait for the payment block and loaders to finish, then a short settle for widgets/iframes
     * before capturing a screenshot.
     */
    private static void waitForPaymentMethodsLoaded(WebDriver driver) throws InterruptedException {
        WebDriverWait w = new WebDriverWait(driver, Duration.ofSeconds(45));
        waitForCheckoutSpinnersGone(driver);
        w.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#checkout-step-payment, #checkout-payment-method-load")));
        WebDriverWait payMaskWait = new WebDriverWait(driver, Duration.ofSeconds(45));
        payMaskWait.until(d -> {
            List<WebElement> masks = d.findElements(By.cssSelector(
                    "#checkout-step-payment .loading-mask, #checkout-payment-method-load .loading-mask, "
                            + ".opc-payment .loading-mask"));
            for (WebElement m : masks) {
                try {
                    if (m.isDisplayed()) {
                        return false;
                    }
                } catch (StaleElementReferenceException e) {
                    return false;
                }
            }
            return true;
        });
        waitForCheckoutSpinnersGone(driver);
        Thread.sleep(3000);
    }

    private static String inputValue(WebDriver driver, WebElement input) {
        Object v = ((JavascriptExecutor) driver).executeScript("return arguments[0].value;", input);
        return v != null ? String.valueOf(v) : "";
    }

    private static void ensureShippingTextFilled(WebDriver driver, WebElement el, String text) {
        if (el == null || text == null) {
            return;
        }
        if (inputValue(driver, el).trim().isEmpty()) {
            fillTextInput(driver, el, text);
        }
    }

    private static final By SHIPPING_NEW_ADDRESS_FORM = By.id("shipping-new-address-form");

    private static WebElement shippingFormInput(WebDriver driver, WebDriverWait wait, String nameAttr) {
        return wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#shipping-new-address-form input[name='" + nameAttr + "']")));
    }

    private static WebElement shippingFormSelect(WebDriver driver, WebDriverWait wait, String nameAttr) {
        return wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#shipping-new-address-form select[name='" + nameAttr + "']")));
    }

    /**
     * Fills {@code #shipping-new-address-form} (Knockout shipping address). Country/region first so AJAX does not
     * clear text fields. Line 1 must be a street address (site blocks PO boxes).
     */
    private static void fillShippingNewAddressForm(WebDriver driver, WebDriverWait wait) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(SHIPPING_NEW_ADDRESS_FORM));

        WebElement country = shippingFormSelect(driver, wait, "country_id");
        new Select(country).selectByValue("US");
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));", country);
        waitForCheckoutSpinnersGone(driver);

        WebDriverWait regionWait = new WebDriverWait(driver, Duration.ofSeconds(30));
        regionWait.until(d -> {
            try {
                WebElement sel = d.findElement(
                        By.cssSelector("#shipping-new-address-form select[name='region_id']"));
                for (WebElement o : new Select(sel).getOptions()) {
                    if ("12".equals(o.getAttribute("value"))) {
                        return true;
                    }
                }
            } catch (NoSuchElementException | StaleElementReferenceException e) {
                return false;
            }
            return false;
        });
        WebElement region = shippingFormSelect(driver, wait, "region_id");
        new Select(region).selectByValue("12");
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));", region);
        waitForCheckoutSpinnersGone(driver);

        fillShippingInput(driver, wait, "firstname", "deepak");
        fillShippingInput(driver, wait, "lastname", "Maheshwari");
        fillShippingInput(driver, wait, "company", "Exinent");
        fillShippingInput(driver, wait, "street[0]", "123 Main Street");
        fillShippingInput(driver, wait, "city", "Los Angeles");
        fillShippingInput(driver, wait, "postcode", "90001");
        fillShippingInput(driver, wait, "telephone", "9870999521");

        waitForCheckoutSpinnersGone(driver);

        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "firstname"), "deepak");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "lastname"), "Maheshwari");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "company"), "Exinent");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "street[0]"), "123 Main Street");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "city"), "Los Angeles");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "postcode"), "90001");
        ensureShippingTextFilled(driver, shippingFormInput(driver, wait, "telephone"), "9870999521");
    }

    private static void fillShippingInput(WebDriver driver, WebDriverWait wait, String nameAttr, String value) {
        WebElement el = shippingFormInput(driver, wait, nameAttr);
        safeClick(driver, el);
        fillTextInput(driver, el, value);
    }

    /** Fetches XML and returns every {@code <loc>} value (sitemap index or urlset). */
    private static List<String> extractSitemapLocs(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = SITEMAP_LOC_PATTERN.matcher(xml);
        while (m.find()) {
            String loc = m.group(1).trim();
            if (!loc.isEmpty()) {
                out.add(loc);
            }
        }
        return out;
    }

    private static String fetchHttpText(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(60_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; NatallergyAutomation/1.0)");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + " for " + urlString);
        }
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * If the sitemap lists another storefront host (common on multi-store Magento), rewrite path/query to {@link #SITE_BASE}
     * so the session stays on the site under test.
     */
    private static String rewriteLocHostToSiteBase(String loc) {
        try {
            URI u = URI.create(loc.trim());
            URI base = URI.create(SITE_BASE);
            if (u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost())) {
                return loc.trim();
            }
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String q = u.getRawQuery();
            String frag = u.getRawFragment();
            StringBuilder sb = new StringBuilder();
            sb.append(base.getScheme()).append("://").append(base.getHost());
            if (base.getPort() != -1) {
                sb.append(":").append(base.getPort());
            }
            sb.append(path);
            if (q != null) {
                sb.append("?").append(q);
            }
            if (frag != null) {
                sb.append("#").append(frag);
            }
            return sb.toString();
        } catch (Exception e) {
            return loc.trim();
        }
    }

    /**
     * Recursively collects leaf page URLs: follows child {@code .xml} entries (sitemap index), otherwise adds all {@code loc}s.
     */
    private static void collectSitemapLeafUrls(String sitemapUrl, int depth, Set<String> leafUrls) throws IOException {
        if (depth > 12) {
            return;
        }
        String xml = fetchHttpText(sitemapUrl);
        List<String> locs = extractSitemapLocs(xml);
        boolean hasChildXml = false;
        for (String loc : locs) {
            if (loc.toLowerCase().endsWith(".xml")) {
                hasChildXml = true;
                break;
            }
        }
        if (hasChildXml) {
            for (String loc : locs) {
                if (loc.toLowerCase().endsWith(".xml")) {
                    collectSitemapLeafUrls(loc, depth + 1, leafUrls);
                }
            }
        } else {
            for (String loc : locs) {
                leafUrls.add(rewriteLocHostToSiteBase(loc));
            }
        }
    }

    /** URLs that look like catalog product detail pages (not categories, cart, or account). */
    private static boolean isCatalogProductPage(String url) {
        try {
            URI u = URI.create(url);
            if (u.getHost() == null) {
                return false;
            }
            String siteHost = URI.create(SITE_BASE).getHost();
            if (siteHost != null && !u.getHost().equalsIgnoreCase(siteHost)) {
                return false;
            }
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return false;
            }
            String lower = url.toLowerCase();
            int q = lower.indexOf('?');
            String pathLower = q >= 0 ? lower.substring(0, q) : lower;
            if (pathLower.contains("/customer/")
                    || pathLower.contains("/checkout")
                    || pathLower.contains("/cart")
                    || pathLower.contains("/catalogsearch/")
                    || pathLower.contains("/wishlist")) {
                return false;
            }
            if (pathLower.contains("/catalog/category/")) {
                return false;
            }
            if (pathLower.endsWith(".html")) {
                return true;
            }
            return pathLower.contains("/catalog/product/view/");
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> discoverProductUrlsFromSitemap() throws IOException {
        Set<String> leaf = new LinkedHashSet<>();
        collectSitemapLeafUrls(SITEMAP_URL, 0, leaf);
        List<String> products = new ArrayList<>();
        for (String u : leaf) {
            if (isCatalogProductPage(u)) {
                products.add(u);
            }
        }
        return products;
    }

    /** {@code args[0]} parsed as {@link Random} seed for shuffling sitemap order; otherwise a random seed. */
    private static Random shuffleRngFromArgs(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            try {
                return new Random(Long.parseLong(args[0].trim()));
            } catch (NumberFormatException ignored) {
                // use random seed below
            }
        }
        return new Random(ThreadLocalRandom.current().nextLong());
    }

    private static boolean hasExplicitOutOfStockIndicators(WebDriver driver) {
        By[] selectors = new By[]{
                By.cssSelector(".stock.unavailable"),
                By.cssSelector(".availability.out-of-stock"),
                By.cssSelector(".product-info-stock-sku .stock.unavailable"),
                By.cssSelector("#product-options-wrapper .stock.unavailable"),
                By.cssSelector(".product.alert.stock"),
        };
        for (By by : selectors) {
            for (WebElement el : driver.findElements(by)) {
                try {
                    if (el.isDisplayed()) {
                        return true;
                    }
                } catch (StaleElementReferenceException ignored) {
                    // continue
                }
            }
        }
        for (WebElement el : driver.findElements(By.cssSelector("[itemprop='availability']"))) {
            try {
                if (!el.isDisplayed()) {
                    continue;
                }
                String href = el.getAttribute("href");
                if (href != null && href.toLowerCase().contains("outofstock")) {
                    return true;
                }
            } catch (StaleElementReferenceException ignored) {
                // continue
            }
        }
        return false;
    }

    private static boolean isAddToCartButtonEnabled(WebDriver driver) {
        List<WebElement> buttons = driver.findElements(By.id("product-addtocart-button"));
        if (buttons.isEmpty()) {
            return false;
        }
        WebElement b = buttons.get(0);
        try {
            if (!b.isDisplayed()) {
                return false;
            }
        } catch (StaleElementReferenceException e) {
            return false;
        }
        if (b.getAttribute("disabled") != null) {
            return false;
        }
        if ("true".equalsIgnoreCase(b.getAttribute("aria-disabled"))) {
            return false;
        }
        String cls = b.getAttribute("class");
        return cls == null || !cls.contains("disabled");
    }

    /**
     * After options are chosen, waits for either a salable add-to-cart control or a clear OOS state (Magento).
     * @return true if add to cart can be used; false if clearly OOS or button never becomes enabled in time
     */
    private static boolean waitForInStockAddToCartOrGiveUp(WebDriver driver) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ADD_TO_CART_STOCK_WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (hasExplicitOutOfStockIndicators(driver)) {
                return false;
            }
            if (isAddToCartButtonEnabled(driver)) {
                return true;
            }
            Thread.sleep(350);
        }
        if (hasExplicitOutOfStockIndicators(driver)) {
            return false;
        }
        return isAddToCartButtonEnabled(driver);
    }

    private static String urlSlugForScreenshots(String productUrl) {
        try {
            String path = URI.create(productUrl).getPath();
            if (path == null || path.isEmpty()) {
                return "product";
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.endsWith(".html")) {
                last = last.substring(0, last.length() - 5);
            }
            last = last.replaceAll("[^a-zA-Z0-9_-]+", "_");
            if (last.isEmpty()) {
                return "product";
            }
            return last.length() > 60 ? last.substring(0, 60) : last;
        } catch (Exception e) {
            return "product";
        }
    }

    /**
     * Chooses the first real option on each visible configurable-attribute {@code select} (Magento), re-querying after AJAX.
     */
    private static void selectConfigurableOptionsIfPresent(WebDriver driver, WebDriverWait wait) throws InterruptedException {
        for (int round = 0; round < 8; round++) {
            List<WebElement> selects = driver.findElements(By.cssSelector(
                    "select[id^='attribute'], select.super-attribute-select, #product-options-wrapper select"));
            int acted = 0;
            for (WebElement selEl : selects) {
                try {
                    if (!selEl.isDisplayed()) {
                        continue;
                    }
                    Select s = new Select(selEl);
                    String current = s.getFirstSelectedOption().getAttribute("value");
                    if (current != null && !current.isEmpty() && !"0".equals(current)) {
                        continue;
                    }
                    for (WebElement o : s.getOptions()) {
                        String v = o.getAttribute("value");
                        if (v != null && !v.isEmpty() && !"0".equals(v)) {
                            s.selectByValue(v);
                            acted++;
                            waitForPageFullyLoaded(driver);
                            Thread.sleep(400);
                            break;
                        }
                    }
                } catch (StaleElementReferenceException | NoSuchElementException ignored) {
                    // next round refreshes the list
                }
            }
            if (acted == 0) {
                break;
            }
        }
    }

    /**
     * Opens a product URL, selects options, and adds to cart when salable. Does not navigate away on success.
     * @return true if add-to-cart was clicked after a salable state
     */
    private static boolean visitProductAndAddToCartIfSalable(WebDriver driver, WebDriverWait wait, String candidate,
            int visitOrdinal) throws IOException, InterruptedException {
        driver.get(candidate);
        waitForPageFullyLoaded(driver);
        takeScreenshot(driver, "sitemap_product_visit" + visitOrdinal + "_" + urlSlugForScreenshots(candidate));

        selectConfigurableOptionsIfPresent(driver, wait);

        if (!waitForInStockAddToCartOrGiveUp(driver)) {
            System.out.println("Skipping (out of stock or add to cart unavailable): " + candidate);
            takeScreenshot(driver, "sitemap_product_oos_" + urlSlugForScreenshots(candidate), false,
                    "Out of stock or add to cart stayed disabled — trying next sitemap product");
            return false;
        }

        safeClick(driver, wait, By.xpath("//button[@id=\"product-addtocart-button\"]"));
        System.out.println("Added to cart: " + candidate);
        Thread.sleep(1500);
        takeScreenshot(driver, "after_add_line_" + visitOrdinal + "_" + urlSlugForScreenshots(candidate));
        return true;
    }

    private static void product(WebDriver driver, String[] args, List<String> productUrls) throws IOException, InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        int targetLines = ThreadLocalRandom.current().nextInt(MIN_PRODUCTS_BEFORE_CHECKOUT, MAX_PRODUCTS_BEFORE_CHECKOUT + 1);
        List<Integer> order = new ArrayList<>(productUrls.size());
        for (int i = 0; i < productUrls.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, shuffleRngFromArgs(args));

        Set<String> addedUrls = new LinkedHashSet<>();
        int maxVisits = Math.min(productUrls.size(), MAX_SITEMAP_PRODUCT_TRIES);
        int visitCount = 0;

        System.out.println("Sitemap products found: " + productUrls.size()
                + " — adding " + targetLines + " random distinct in-stock product(s), up to " + maxVisits + " page visits");

        for (int listPos : order) {
            if (addedUrls.size() >= targetLines) {
                break;
            }
            if (visitCount >= maxVisits) {
                break;
            }
            String candidate = productUrls.get(listPos);
            if (addedUrls.contains(candidate)) {
                continue;
            }
            visitCount++;
            if (visitProductAndAddToCartIfSalable(driver, wait, candidate, visitCount)) {
                addedUrls.add(candidate);
            }
        }

        if (addedUrls.size() < targetLines) {
            throw new IllegalStateException("Added only " + addedUrls.size() + " of " + targetLines
                    + " distinct products after " + visitCount + " sitemap visits (cap " + maxVisits + ")");
        }

        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        Thread.sleep(2000);
        takeScreenshot(driver, "after_add_to_cart_top");

        safeClick(driver, wait, By.xpath("//a[@class=\"action showcart\"]"));
        takeScreenshot(driver, "minicart_open");

        safeClick(driver, wait, By.xpath("//span[normalize-space()=\"View and Edit Cart\"]"));
        waitForPageFullyLoaded(driver);
        takeScreenshot(driver, "cart_page");

        WebElement qtyInput = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//th//span[normalize-space()='Qty']/ancestor::table//tbody//td[contains(@class,'qty')]//input")));
        safeClick(driver, qtyInput);
        qtyInput.sendKeys(Keys.chord(Keys.CONTROL, "a"), "3");

        safeClick(driver, wait, By.xpath("//span[normalize-space()=\"Update Shopping Cart\"]"));
        waitForPageFullyLoaded(driver);
        takeScreenshot(driver, "cart_page_after_qty_change");

        WebElement couponInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("coupon_code")));
        safeClick(driver, couponInput);
        couponInput.sendKeys(Keys.chord(Keys.CONTROL, "a"), "exitest");

        safeClick(driver, wait, By.xpath("//span[normalize-space()=\"Apply Discount\"]"));
        waitForPageFullyLoaded(driver);

        takeScreenshot(driver, "cart_after_apply_discount");

        safeClick(driver, wait, By.xpath("//span[normalize-space()=\"Proceed to Checkout\"]"));
        waitForPageFullyLoaded(driver);
        waitForCheckoutSpinnersGone(driver);

        WebElement customerEmail = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//input[@id=\"customer-email\"]")));
        safeClick(driver, customerEmail);
        fillTextInput(driver, customerEmail, "deepak.maheshwari@exinent.com");
        waitForCheckoutSpinnersGone(driver);

        fillShippingNewAddressForm(driver, wait);

        By continueCheckout = By.xpath("//button[@class=\"button action continue primary\"]");
        waitForCheckoutSpinnersGone(driver);
        safeClick(driver, wait, continueCheckout);
        waitForPageFullyLoaded(driver);
        waitForPaymentMethodsLoaded(driver);
        takeScreenshot(driver, "checkout_after_shipping_continue");

        driver.get("https://www.natlallergy.com/");
        waitForPageFullyLoaded(driver);

        WebElement searchInput = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//input[@id=\"search\"]")));
        safeClick(driver, searchInput);
        searchInput.sendKeys(Keys.chord(Keys.CONTROL, "a"), "Allergy");
        waitForSearchSuggestionsVisibleOrSettle(driver);
        takeScreenshot(driver, "search_input");
    }

    /**
     * Lets autocomplete / AJAX search results render before capturing. Tries common Magento + Algolia containers;
     * if none appear in time, falls back to a fixed delay.
     */
    private static void waitForSearchSuggestionsVisibleOrSettle(WebDriver driver) throws InterruptedException {
        WebDriverWait w = new WebDriverWait(driver, Duration.ofSeconds(15));
        try {
            w.until(d -> {
                if (!Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                        "return typeof jQuery === 'undefined' || jQuery.active === 0"))) {
                    return false;
                }
                for (WebElement e : d.findElements(By.cssSelector(
                        "#search_autocomplete, .search-autocomplete, ul[role='listbox'], div[role='listbox'], "
                                + ".algolia-autocomplete, .aa-Panel, .aa-dropdown-menu"))) {
                    try {
                        if (e.isDisplayed()) {
                            return true;
                        }
                    } catch (StaleElementReferenceException ignored) {
                        // retry other candidates
                    }
                }
                return false;
            });
        } catch (TimeoutException e) {
            Thread.sleep(2500);
        }
        Thread.sleep(500);
    }

    /** Wait until the document is complete and any jQuery AJAX has settled (no-op if jQuery is absent). */
    private static void waitForPageFullyLoaded(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        wait.until(d -> "complete".equals(
                ((JavascriptExecutor) d).executeScript("return document.readyState")));
        wait.until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                "return typeof jQuery === 'undefined' || jQuery.active === 0")));
    }

    // ------------------ Screenshot + Upload + CSV + HTML ------------------
    private static void takeScreenshot(WebDriver driver, String title) throws IOException {
        takeScreenshot(driver, title, true, "Step completed successfully");
    }

    private static void takeScreenshot(WebDriver driver, String title, boolean isPass, String details) throws IOException {
        totalSteps++;
        if (isPass) passedSteps++;
        else failedSteps++;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String statusPrefix = isPass ? "SUCCESS_" : "ERROR_";
        String fileName = statusPrefix + title + "_" + timestamp + ".png";

        File folder = new File(SS_DIR);
        if (!folder.exists()) folder.mkdirs();

        File outputFile = new File(folder, fileName);
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Screenshot saved: " + outputFile.getAbsolutePath());

        String uploadedUrl = "Upload failed/skipped";
        try {
            uploadedUrl = uploadToImgbb(outputFile);
            System.out.println("Uploaded URL: " + uploadedUrl);
        } catch (Exception e) {
            System.out.println("Could not upload to Imgbb: " + e.getMessage());
        }

        writeCsv(timestamp, title, uploadedUrl, outputFile.getName());
        writeHtmlReport(timestamp, title, outputFile.getName(), uploadedUrl, isPass, details);
    }

    /** Full-page PNG via Chrome DevTools (falls back to viewport screenshot if not Chrome). */
    private static void takeFullPageScreenshot(WebDriver driver, String title) throws IOException {
        totalSteps++;
        passedSteps++;

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String fileName = "SUCCESS_" + title + "_" + timestamp + ".png";

        File folder = new File(SS_DIR);
        if (!folder.exists()) folder.mkdirs();

        File outputFile = new File(folder, fileName);
        byte[] pngBytes;
        if (driver instanceof ChromeDriver) {
            Map<String, Object> params = new HashMap<>();
            params.put("format", "png");
            params.put("captureBeyondViewport", true);
            params.put("fromSurface", true);
            Map<String, Object> result = ((ChromeDriver) driver).executeCdpCommand("Page.captureScreenshot", params);
            String data = (String) result.get("data");
            pngBytes = Base64.getDecoder().decode(data);
        } else {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            pngBytes = Files.readAllBytes(src.toPath());
        }
        Files.write(outputFile.toPath(), pngBytes);

        System.out.println("Full page screenshot saved: " + outputFile.getAbsolutePath());

        String uploadedUrl = "Upload failed/skipped";
        try {
            uploadedUrl = uploadToImgbb(outputFile);
            System.out.println("Uploaded URL: " + uploadedUrl);
        } catch (Exception e) {
            System.out.println("Could not upload to Imgbb: " + e.getMessage());
        }

        writeCsv(timestamp, title, uploadedUrl, outputFile.getName());
        writeHtmlReport(timestamp, title, outputFile.getName(), uploadedUrl, true, "Full page capture");
    }

    private static String uploadToImgbb(File imageFile) throws IOException {
        byte[] fileContent = Files.readAllBytes(imageFile.toPath());
        String encodedImage = Base64.getEncoder().encodeToString(fileContent);

        String data = "key=" + IMGBB_API_KEY +
                "&image=" + URLEncoder.encode(encodedImage, "UTF-8");

        URL url = new URL("https://api.imgbb.com/1/upload");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");

        OutputStream os = conn.getOutputStream();
        os.write(data.getBytes());
        os.flush();
        os.close();

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) response.append(line);
        br.close();

        String json = response.toString();
        return json.split("\"url\":\"")[1].split("\"")[0].replace("\\/", "/");
    }

    private static void writeCsv(String timestamp, String title, String url, String localFileName) {
        File fileObj = new File(CSV_PATH);
        if (!fileObj.getParentFile().exists()) {
            fileObj.getParentFile().mkdirs();
        }
        boolean fileExists = fileObj.exists();

        try (FileWriter fw = new FileWriter(CSV_PATH, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {

            if (!fileExists) {
                out.println("Timestamp,Title,LocalFile,UploadedURL");
            }
            out.println(timestamp + "," + title + "," + localFileName + "," + url);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeHtmlReport(String timestamp, String title, String localFileName, String url, boolean isPass, String details) {
        File htmlFolder = new File(HTML_DIR);
        if (!htmlFolder.exists()) htmlFolder.mkdirs();

        String htmlFile = HTML_DIR + "\\TestReport.html";
        String relativeImgPath = "../../../screenshots/" + RUN_DATE + "/" + RUN_TIME + "/" + localFileName;

        String stepStatusClass = isPass ? "pass" : "fail";
        String stepStatusIcon = isPass ? "✅" : "❌";

        StringBuilder stepHtml = new StringBuilder();
        stepHtml.append("            <div class=\"test-step ").append(stepStatusClass).append("\">\n");
        stepHtml.append("                <div class=\"step-header\">\n");
        stepHtml.append("                    <span>").append(stepStatusIcon).append(" ").append(title.replace("_", " ").toUpperCase()).append("</span>\n");
        stepHtml.append("                    <span class=\"step-time\">").append(timestamp.split("_")[1].replace("-", ":")).append("</span>\n");
        stepHtml.append("                </div>\n");
        stepHtml.append("                <div class=\"step-details\">").append(details).append("</div>\n");
        stepHtml.append("                <div style=\"margin-top: 15px;\">\n");
        stepHtml.append("                    <a href=\"").append(relativeImgPath).append("\" target=\"_blank\">\n");
        stepHtml.append("                        <img class=\"screenshot\" src=\"").append(relativeImgPath).append("\" alt=\"").append(title).append("\">\n");
        stepHtml.append("                    </a>\n");
        stepHtml.append("                </div>\n");
        stepHtml.append("                <div style=\"margin-top: 10px;\">\n");
        stepHtml.append("                    <a class=\"btn\" href=\"").append(relativeImgPath).append("\" target=\"_blank\">View Local</a>\n");
        if (url != null && url.startsWith("http")) {
            stepHtml.append("                    <a class=\"btn imgbb\" href=\"").append(url).append("\" target=\"_blank\">View ImgBB</a>\n");
        }
        stepHtml.append("                </div>\n");
        stepHtml.append("            </div>");

        htmlSteps.add(stepHtml.toString());

        try (FileWriter fw = new FileWriter(htmlFile, false);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {

            double passRate = totalSteps > 0 ? ((double) passedSteps / totalSteps) * 100 : 0;
            String overallStatus = failedSteps > 0 ? "FAILED" : "PASSED";
            String statusBadgeClass = failedSteps > 0 ? "status-fail" : "status-pass";

            out.println("<!DOCTYPE html>");
            out.println("<html lang=\"en\">");
            out.println("<head>");
            out.println("    <meta charset=\"UTF-8\">");
            out.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            out.println("    <title>NutriDyn Automation - Test Report</title>");
            out.println("    <style>");
            out.println("        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 20px; background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%); }");
            out.println("        .container { max-width: 1400px; margin: 0 auto; background: white; border-radius: 15px; box-shadow: 0 10px 30px rgba(0,0,0,0.1); padding: 40px; }");
            out.println("        .header { text-align: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 40px; border-radius: 15px; margin-bottom: 40px; }");
            out.println("        .header h1 { margin: 0; font-size: 2.5em; font-weight: 300; }");
            out.println("        .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 25px; margin-bottom: 40px; }");
            out.println("        .summary-card { background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%); padding: 25px; border-radius: 15px; text-align: center; border-left: 6px solid #667eea; transition: transform 0.3s; }");
            out.println("        .summary-card:hover { transform: translateY(-5px); }");
            out.println("        .summary-card h3 { margin: 0 0 15px 0; color: #333; font-size: 1.1em; }");
            out.println("        .summary-card .number { font-size: 2.5em; font-weight: bold; color: #333; margin-bottom: 10px; }");
            out.println("        .progress-bar { width: 100%; height: 25px; background-color: #e9ecef; border-radius: 12px; overflow: hidden; margin: 15px 0; }");
            out.println("        .progress-fill { height: 100%; background: linear-gradient(90deg, #28a745, #20c997); transition: width 1s ease; border-radius: 12px; }");
            out.println("        .test-results { margin: 40px 0; display: flex; flex-direction: column; gap: 15px; }");
            out.println("        .test-step { margin: 15px 0; padding: 20px; border-radius: 12px; border-left: 6px solid; transition: all 0.3s; }");
            out.println("        .test-step:hover { transform: translateX(5px); }");
            out.println("        .test-step.pass { background: linear-gradient(135deg, #d4edda 0%, #c3e6cb 100%); border-left-color: #28a745; }");
            out.println("        .test-step.fail { background: linear-gradient(135deg, #f8d7da 0%, #f5c6cb 100%); border-left-color: #dc3545; }");
            out.println("        .step-header { font-weight: bold; margin-bottom: 12px; font-size: 1.1em; }");
            out.println("        .step-details { font-size: 0.95em; color: #666; line-height: 1.5; }");
            out.println("        .step-time { font-size: 0.85em; color: #888; float: right; background: rgba(0,0,0,0.05); padding: 4px 8px; border-radius: 5px; }");
            out.println("        .screenshot { max-width: 300px; border-radius: 8px; margin: 15px 0; border: 2px solid #ddd; transition: transform 0.3s; cursor: pointer; }");
            out.println("        .screenshot:hover { transform: scale(1.05); }");
            out.println("        .timestamp { text-align: center; color: #666; margin: 25px 0; font-size: 1.1em; }");
            out.println("        .status-badge { display: inline-block; padding: 8px 20px; border-radius: 25px; color: white; font-weight: bold; }");
            out.println("        .status-pass { background: linear-gradient(135deg, #28a745 0%, #20c997 100%); }");
            out.println("        .status-fail { background: linear-gradient(135deg, #dc3545 0%, #c82333 100%); }");
            out.println("        .btn { display: inline-block; padding: 8px 15px; background: linear-gradient(135deg, #28a745 0%, #20c997 100%); color: white; text-decoration: none; border-radius: 20px; font-weight: bold; font-size: 0.9em; margin-right: 10px; transition: opacity 0.3s; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }");
            out.println("        .btn:hover { opacity: 0.9; }");
            out.println("        .btn.imgbb { background: linear-gradient(135deg, #17a2b8 0%, #117a8b 100%); }");
            out.println("    </style>");
            out.println("</head>");
            out.println("<body>");
            out.println("    <div class=\"container\">");
            out.println("        <div class=\"header\">");
            out.println("            <h1>NutriDyn Automation</h1>");
            out.println("            <p style=\"font-size: 1.2em; margin: 10px 0;\">Test Report with Detailed Steps</p>");
            out.println("            <div class=\"timestamp\">Generated on: " + RUN_DATE + " at " + RUN_TIME.replace("-", ":") + "</div>");
            out.println("        </div>");
            out.println("        <div class=\"summary\">");
            out.println("            <div class=\"summary-card\">");
            out.println("                <h3>Overall Status</h3>");
            out.println("                <div class=\"status-badge " + statusBadgeClass + "\">" + overallStatus + "</div>");
            out.println("            </div>");
            out.println("            <div class=\"summary-card\">");
            out.println("                <h3>Total Steps</h3>");
            out.println("                <div class=\"number\">" + totalSteps + "</div>");
            out.println("            </div>");
            out.println("            <div class=\"summary-card\">");
            out.println("                <h3>Passed</h3>");
            out.println("                <div class=\"number\" style=\"color: #28a745;\">" + passedSteps + "</div>");
            out.println("            </div>");
            out.println("            <div class=\"summary-card\">");
            out.println("                <h3>Failed</h3>");
            out.println("                <div class=\"number\" style=\"color: #dc3545;\">" + failedSteps + "</div>");
            out.println("            </div>");
            out.println("            <div class=\"summary-card\">");
            out.println("                <h3>Pass Rate</h3>");
            out.println("                <div class=\"number\">" + String.format("%.1f", passRate) + "%</div>");
            out.println("                <div class=\"progress-bar\">");
            out.println("                    <div class=\"progress-fill\" style=\"width: " + passRate + "%\"></div>");
            out.println("                </div>");
            out.println("            </div>");
            out.println("        </div>");
            String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            out.println("        <div style=\"text-align: center; margin: 20px 0;\">");
            out.println("            <p><strong>Test Duration:</strong> " + START_TIME + " to " + currentTime + "</p>");
            out.println("        </div>");
            out.println("        <div class=\"test-results\">");
            out.println("            <h2>Detailed Test Results</h2>");
            out.println("            <p style=\"color: #666; margin-bottom: 30px;\">Step-by-step execution details with screenshots</p>");
            for (String step : htmlSteps) {
                out.println(step);
            }
            out.println("        </div>");
            out.println("        <div style=\"margin-top: 50px; padding-top: 20px; border-top: 1px solid #dee2e6; text-align: center; color: #6c757d;\">");
            out.println("            <p>Generated by NutriDyn Automation Framework</p>");
            out.println("        </div>");
            out.println("    </div>");
            out.println("</body>");
            out.println("</html>");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}