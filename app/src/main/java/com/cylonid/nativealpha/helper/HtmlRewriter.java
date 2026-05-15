package com.cylonid.nativealpha.helper;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Intercepts main-frame HTML responses, parses with JSoup, injects:
 *   1. A <style id="native-alpha-prerender"> with `html { visibility: hidden }`
 *      and any cosmetic rules from BundledFilters scoped to the request host.
 *   2. A small <script> exposing window.__nativeAlphaReveal() and a 3s
 *      safety-net timeout.
 *
 * Result: the browser parses the page with our barrier in place, so cosmetic
 * rules are part of the initial render — zero FOUC. Userscripts call
 * __nativeAlphaReveal() at the end of their init to lift the barrier.
 */
public final class HtmlRewriter {

    private static final String TAG = "HtmlRewriter";
    private static final String STYLE_ID = "native-alpha-prerender";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024; // 8 MB cap

    public boolean shouldRewrite(WebResourceRequest request) {
        if (request == null || !request.isForMainFrame()) return false;
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String url = request.getUrl().toString();
        return url.startsWith("http://") || url.startsWith("https://");
    }

    public WebResourceResponse rewrite(WebResourceRequest request, BundledFilters filters) {
        String url = request.getUrl().toString();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            // Pass through request headers from the WebView
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    // Strip headers that conflict with our own handling
                    String name = h.getKey();
                    if (name == null) continue;
                    if (name.equalsIgnoreCase("Accept-Encoding")
                            || name.equalsIgnoreCase("Connection")
                            || name.equalsIgnoreCase("Host")) continue;
                    conn.setRequestProperty(name, h.getValue());
                }
            }
            // We can handle gzip/deflate; advertise both
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

            // Forward cookies the WebView has for this URL
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            conn.connect();
            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                return null; // Let WebView handle non-HTML
            }

            // Propagate any Set-Cookie response headers back to the WebView
            Map<String, List<String>> respHeaders = conn.getHeaderFields();
            if (respHeaders != null) {
                for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                        for (String c : e.getValue()) {
                            CookieManager.getInstance().setCookie(url, c);
                        }
                    }
                }
            }

            // Strip Content-Security-Policy so our injected <script> and <style>
            // aren't blocked. CSP can otherwise leave the visibility-hidden
            // barrier in place forever (the reveal script gets CSP-blocked).
            // Also strip CSP <meta> tags in the body parse pass below.

            byte[] body = readBody(conn);
            if (body == null) return null;

            String charset = parseCharset(contentType);
            String html = new String(body, Charset.forName(charset));

            Document doc = Jsoup.parse(html, url);
            doc.outputSettings().prettyPrint(false);
            Element head = doc.head();
            if (head == null) return null;

            // Strip CSP meta tags so inline script + style work.
            doc.select("meta[http-equiv]").forEach(m -> {
                String v = m.attr("http-equiv");
                if (v != null && (v.equalsIgnoreCase("Content-Security-Policy")
                        || v.equalsIgnoreCase("Content-Security-Policy-Report-Only"))) {
                    m.remove();
                }
            });

            String host = request.getUrl().getHost();
            String cosmeticCss = filters != null ? filters.cssForHost(host) : "";

            String styleCss = "html { visibility: hidden !important; }"
                    + (cosmeticCss.isEmpty() ? "" : "\n" + cosmeticCss);
            Element style = doc.createElement("style").attr("id", STYLE_ID).text(styleCss);
            head.prependChild(style);

            Element script = doc.createElement("script").appendChild(new org.jsoup.nodes.DataNode(
                    "(function(){"
                            + "window.__nativeAlphaReveal=function(){"
                            + "var b=document.getElementById('" + STYLE_ID + "');if(b)b.remove();};"
                            + "setTimeout(window.__nativeAlphaReveal,3000);"
                            + "})();"));
            head.prependChild(script);

            byte[] modified = doc.outerHtml().getBytes(StandardCharsets.UTF_8);

            // Build a minimal response-header map (avoid hop-by-hop entries
            // and CSP which would block our injected script/style).
            Map<String, String> outHeaders = new HashMap<>();
            if (respHeaders != null) {
                for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                    if (e.getKey() == null) continue;
                    String key = e.getKey();
                    if (key.equalsIgnoreCase("Content-Length")
                            || key.equalsIgnoreCase("Content-Encoding")
                            || key.equalsIgnoreCase("Transfer-Encoding")
                            || key.equalsIgnoreCase("Connection")
                            || key.equalsIgnoreCase("Content-Security-Policy")
                            || key.equalsIgnoreCase("Content-Security-Policy-Report-Only")) continue;
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        outHeaders.put(key, e.getValue().get(0));
                    }
                }
            }

            String reason = conn.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = "OK";

            return new WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    status,
                    reason,
                    outHeaders,
                    new ByteArrayInputStream(modified)
            );
        } catch (IOException e) {
            Log.w(TAG, "rewrite failed for " + url + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "unexpected error rewriting " + url, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] readBody(HttpURLConnection conn) throws IOException {
        InputStream in;
        try {
            in = conn.getInputStream();
        } catch (IOException e) {
            in = conn.getErrorStream();
            if (in == null) throw e;
        }
        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding)) {
            in = new GZIPInputStream(in);
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            in = new InflaterInputStream(in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                Log.w(TAG, "response exceeded " + MAX_BODY_BYTES + " bytes, aborting rewrite");
                in.close();
                return null;
            }
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    private String parseCharset(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx < 0) return "UTF-8";
        String cs = contentType.substring(idx + 8).trim();
        // strip trailing ; and quotes
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi);
        cs = cs.replace("\"", "").replace("'", "").trim();
        try {
            // validate
            Charset.forName(cs);
            return cs;
        } catch (Exception e) {
            return "UTF-8";
        }
    }
}
