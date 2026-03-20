import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.time.Duration;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static void main(String[] args) throws IOException, InterruptedException {

        WebDriver driver = new ChromeDriver();
        driver.manage().window().maximize();
        driver.get("https://www.natlallergy.com/");
        waitForPageFullyLoaded(driver);
        takeScreenshot(driver, "homepage");
        product(driver);
        driver.quit();
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

    private static void product(WebDriver driver) throws IOException, InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        WebElement categoryLabel = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//span[normalize-space()=\"Find Relief From\"]")));
        new Actions(driver).moveToElement(categoryLabel).perform();
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("li.nav-1 ul.level0.submenu")));

        safeClick(driver, wait, By.xpath("//a[@id='ui-id-4']//span[contains(text(),'Dust Mites')]"));
        waitForPageFullyLoaded(driver);
        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 400);");
        takeScreenshot(driver, "dust_mites_category");

        safeClick(driver, wait,
                By.xpath("//a[normalize-space()=\"All-Cotton Allergy Mattress Covers\"]"));
        waitForPageFullyLoaded(driver);
        takeScreenshot(driver, "all_cotton_allergy_mattress_covers_product");

        WebElement sizeSelectEl = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//select[@id=\"attribute216\"]")));
        safeClick(driver, sizeSelectEl);
        new Select(sizeSelectEl).selectByValue("877");

        safeClick(driver, wait, By.xpath("//button[@id=\"product-addtocart-button\"]"));
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        Thread.sleep(3000);
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
