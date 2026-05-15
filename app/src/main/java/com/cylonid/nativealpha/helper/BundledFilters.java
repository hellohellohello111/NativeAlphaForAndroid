package com.cylonid.nativealpha.helper;

import android.content.Context;
import android.util.Log;
import android.webkit.WebResourceRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the bundled filter list at assets/filters/native_alpha_focus.txt and
 * exposes:
 *   - shouldBlock(WebResourceRequest) → matched a network rule, return blocked response
 *   - cssForHost(host) → joined cosmetic-rule CSS for HtmlRewriter to inject
 *
 * Supports a strict subset of EasyList syntax:
 *   ||host^path              → block requests whose URL contains 'host^path' (^ means end of host or /)
 *   ||host^                  → block requests for this host (and subdomains)
 *   prefix                   → block requests whose URL contains 'prefix'
 *   domain.com##selector     → cosmetic rule for that domain
 *   ##selector               → cosmetic rule applied to every site
 *   !  comment line
 *
 * Parsed once at app startup; results cached in memory.
 */
public final class BundledFilters {

    private static final String TAG = "BundledFilters";
    private static final String ASSET_PATH = "filters/native_alpha_focus.txt";

    private final List<String> networkSubstrings = new ArrayList<>();
    private final List<String> networkHostAnchored = new ArrayList<>(); // hosts (and subdomains) to block whole
    private final Map<String, List<String>> cosmeticByDomain = new HashMap<>();
    private final List<String> cosmeticGlobal = new ArrayList<>();

    public BundledFilters(Context context) {
        load(context);
    }

    private void load(Context context) {
        try (InputStream is = context.getAssets().open(ASSET_PATH);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("!")) continue;

                int cosmeticIdx = line.indexOf("##");
                if (cosmeticIdx >= 0) {
                    String domain = line.substring(0, cosmeticIdx).trim().toLowerCase(Locale.ROOT);
                    String selector = line.substring(cosmeticIdx + 2).trim();
                    if (selector.isEmpty()) continue;
                    if (domain.isEmpty()) {
                        cosmeticGlobal.add(selector);
                    } else {
                        cosmeticByDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(selector);
                    }
                    continue;
                }

                if (line.startsWith("||")) {
                    String body = line.substring(2);
                    int caret = body.indexOf('^');
                    if (caret > 0 && (caret == body.length() - 1 || caret == body.length())) {
                        // ||host^  → host-anchored, blocks whole domain + subdomains
                        networkHostAnchored.add(body.substring(0, caret).toLowerCase(Locale.ROOT));
                    } else {
                        // ||host^path or ||host/path → match as substring
                        networkSubstrings.add(body.replace("^", "").toLowerCase(Locale.ROOT));
                    }
                } else {
                    // plain substring rule
                    networkSubstrings.add(line.toLowerCase(Locale.ROOT));
                }
            }
            Log.i(TAG, "loaded " + networkSubstrings.size() + " substring + "
                    + networkHostAnchored.size() + " host rules, "
                    + cosmeticByDomain.size() + " domain cosmetic groups, "
                    + cosmeticGlobal.size() + " global cosmetic rules");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load bundled filter asset", e);
        }
    }

    /** True if the request URL matches any network rule. */
    public boolean shouldBlock(WebResourceRequest request) {
        if (request == null) return false;
        String url = request.getUrl().toString().toLowerCase(Locale.ROOT);
        String host = request.getUrl().getHost();
        if (host != null) {
            host = host.toLowerCase(Locale.ROOT);
            for (String h : networkHostAnchored) {
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
        }
        for (String s : networkSubstrings) {
            if (url.contains(s)) return true;
        }
        return false;
    }

    /** Joined CSS for cosmetic rules that apply to this host (with global rules merged in). */
    public String cssForHost(String host) {
        if (host == null) host = "";
        host = host.toLowerCase(Locale.ROOT);

        List<String> selectors = new ArrayList<>(cosmeticGlobal);
        for (Map.Entry<String, List<String>> e : cosmeticByDomain.entrySet()) {
            String dom = e.getKey();
            if (host.equals(dom) || host.endsWith("." + dom)) {
                selectors.addAll(e.getValue());
            }
        }
        if (selectors.isEmpty()) return "";

        // Single declaration block with all selectors joined by comma — keeps
        // injected CSS small and parses identically to per-rule declarations.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectors.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append(selectors.get(i));
        }
        sb.append(" { display: none !important; }");
        return sb.toString();
    }

    public static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
