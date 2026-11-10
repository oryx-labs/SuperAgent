package com.superagent.docs.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.link.UnreadableLinkException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches public pages for one read, like a browser that isn't logged in: keeps the cookies a site hands out along the
 * way (some public pages need them), follows redirects itself so each hop passes {@link UrlGuard}, and stops at
 * {@link #MAX_BYTES}. Uses the JVM's proxy settings (on a server behind a proxy, the http(s).proxyHost properties).
 */
final class Fetcher {

    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";
    private static final int MAX_HOPS = 6;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private final String link;
    private final HttpClient http;

    /** {@code link}: the link being read, as the user gave it (named in errors). */
    Fetcher(String link) {
        this.link = link;
        this.http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    record Page(URI uri, int status, String contentType, byte[] body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text() {
            Matcher m = CHARSET.matcher(contentType);
            Charset cs = StandardCharsets.UTF_8;
            if (m.find()) {
                try {
                    cs = Charset.forName(m.group(1));
                } catch (RuntimeException ignored) {
                    // an unknown charset name: UTF-8 is the best guess
                }
            }
            return new String(body, cs);
        }

        boolean is(String type) {
            return contentType.toLowerCase(Locale.ROOT).contains(type);
        }
    }

    Page get(URI uri, Map<String, String> headers) {
        URI at = uri;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            UrlGuard.check(at.toString());
            HttpRequest.Builder b = HttpRequest.newBuilder(at).timeout(Duration.ofSeconds(30)).GET()
                    .header("User-Agent", UA).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.forEach(b::header);
            HttpResponse<InputStream> r = send(b.build());
            int status = r.statusCode();
            String type = r.headers().firstValue("content-type").orElse("");
            var location = r.headers().firstValue("location");
            if (status >= 300 && status < 400 && location.isPresent()) {
                close(r.body());
                at = at.resolve(UrlGuard.toUri(location.get()));
                continue;
            }
            return new Page(at, status, type, read(r.body()));
        }
        throw new UnreadableLinkException(link, "跳转次数太多", null);
    }

    JsonNode json(URI uri, Map<String, String> headers) {
        return json(get(uri, headers));
    }

    JsonNode json(Page p) {
        try {
            return JSON.readTree(p.body());
        } catch (IOException e) {
            throw new UnreadableLinkException(link, "返回的不是可解析的数据（HTTP " + p.status() + "）", null);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new UnreadableLinkException(link, "连不上这个网站：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    "稍后再试，或把内容导出成文件发过来");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreadableLinkException(link, "读取被取消了", null);
        }
    }

    private byte[] read(InputStream in) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BYTES) {
                    throw new UnreadableLinkException(link, "资料超过 " + (MAX_BYTES >> 20) + "MB，太大了",
                            "把需要的部分导出成文件发过来");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UnreadableLinkException(link, "读取中断了：" + e.getMessage(), "稍后再试");
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // the redirect's body is not needed
        }
    }
}
