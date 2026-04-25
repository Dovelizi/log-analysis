package com.loganalysis.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 报表截图服务，用 Playwright for Java 渲染前端页面并截图。
 * 对齐 Python services/screenshot_service.py，但改为 Java Playwright 调用。
 *
 * 注意：
 *   - Playwright 首次使用会下载浏览器二进制（~300MB），在 Docker 部署时
 *     需要提前执行 `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI
 *     -D exec.args="install --with-deps chromium"` 或在运行时允许联网。
 *   - 通过 `loganalysis.screenshot.base-url` 配置前端入口（默认本机 8080）。
 */
@Service
public class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);

    @Value("${loganalysis.screenshot.base-url:http://127.0.0.1:8080}")
    private String baseUrl;

    @Value("${loganalysis.screenshot.viewport-width:1440}")
    private int viewportWidth;

    @Value("${loganalysis.screenshot.viewport-height:900}")
    private int viewportHeight;

    @Value("${loganalysis.screenshot.timeout-ms:60000}")
    private int timeoutMs;

    /**
     * 为指定日期生成报表截图，返回 Base64 编码的 PNG。
     * 入参 date 格式 yyyy-MM-dd，None 时 Controller 层应传当天。
     */
    public String screenshotReportByDate(String date) {
        String url = baseUrl + "/#report?date=" + (date == null ? "" : date);
        return screenshotUrl(url);
    }

    /** 通用：截取指定 URL 的整页图 */
    public String screenshotUrl(String url) {
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(true);
            try (Browser browser = pw.chromium().launch(opts)) {
                Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                        .setViewportSize(viewportWidth, viewportHeight);
                try (Page page = browser.newContext(ctxOpts).newPage()) {
                    page.setDefaultTimeout(timeoutMs);
                    page.navigate(url);
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    byte[] bytes = page.screenshot(new Page.ScreenshotOptions()
                            .setType(ScreenshotType.PNG)
                            .setFullPage(true));
                    return Base64.getEncoder().encodeToString(bytes);
                }
            }
        } catch (Exception e) {
            log.warn("截图失败: url={}, 异常: {}", url, e.getMessage());
            throw new IllegalStateException("截图失败: " + e.getMessage(), e);
        }
    }
}
