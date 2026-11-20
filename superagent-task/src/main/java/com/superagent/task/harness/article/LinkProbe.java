package com.superagent.task.harness.article;

import com.superagent.core.net.EgressDeniedException;
import com.superagent.core.net.EgressGuard;
import com.superagent.task.harness.article.ArticleChecks.LinkStatus;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Function;

/**
 * Whether a link in a draft opens: redirects followed by hand so every hop goes through {@link EgressGuard} (an
 * internal address is never connected to), at most {@value #REDIRECTS}; a timeout or a dropped connection is tried
 * once more.
 */
final class LinkProbe implements Function<String, LinkStatus> {

    static final int REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public LinkStatus apply(String url) {
        LinkStatus first = once(url);
        return first.error() != null && (first.error().equals("timeout") || first.error().startsWith("io:")) ? once(url) : first;
    }

    private LinkStatus once(String url) {
        String at = url;
        try {
            for (int hop = 0; hop <= REDIRECTS; hop++) {
                URI uri = EgressGuard.PUBLIC_ONLY.check(at);
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0 (SuperAgent link check)").GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                int code = r.statusCode();
                if (code < 300 || code >= 400) {
                    return new LinkStatus(code, null);
                }
                String next = r.headers().firstValue("location").orElse(null);
                if (next == null) {
                    return new LinkStatus(code, null);
                }
                at = uri.resolve(next).toString();
            }
            return new LinkStatus(0, "redirects");
        } catch (EgressDeniedException e) {
            return new LinkStatus(0, e.getMessage().contains("找不到这个域名") ? "dns" : "blocked: " + e.getMessage());
        } catch (HttpTimeoutException e) {
            return new LinkStatus(0, "timeout");
        } catch (UnknownHostException e) {
            return new LinkStatus(0, "dns");
        } catch (IOException | IllegalArgumentException e) {
            return new LinkStatus(0, "io: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LinkStatus(0, "timeout");
        }
    }
}
