package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;
import com.superagent.core.link.LinkReader;
import com.superagent.core.tenant.TenantContext;
import com.superagent.docs.FeishuDocTools;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads links for agents and for a task's materials. A platform the user authorized is read as them through its
 * official API (complete and stable); otherwise, and when that fails, the link is read as the public sees it. Results
 * are kept a few minutes per company and user, so checking a task's materials and then working from them reads once.
 */
@Service
public class LinkReaderService implements LinkReader {

    private static final Duration KEEP = Duration.ofMinutes(10);
    private static final int KEEP_MAX = 200;

    private final FeishuDocTools feishu;
    private final List<LinkSource> sources = List.of(new FeishuPublicSource(), new GoogleDocsSource(), new GitHubSource(),
            new WeChatSource());
    private final LinkSource generic = new GenericSource();
    private final Map<String, Kept> kept = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
            return size() > KEEP_MAX;
        }
    };

    private record Kept(LinkContent content, Instant at) {
    }

    public LinkReaderService(FeishuDocTools feishu) {
        this.feishu = feishu;
    }

    @Override
    public LinkContent read(String url, String user) {
        URI uri = UrlGuard.check(url);
        String key = TenantContext.require() + "|" + user + "|" + uri;
        synchronized (kept) {
            Kept k = kept.get(key);
            if (k != null && k.at().plus(KEEP).isAfter(Instant.now())) {
                return k.content();
            }
        }
        LinkContent content = fresh(uri, url.strip(), user);
        synchronized (kept) {
            kept.put(key, new Kept(content, Instant.now()));
        }
        return content;
    }

    private LinkContent fresh(URI uri, String link, String user) {
        LinkSource source = sources.stream().filter(s -> s.handles(uri)).findFirst().orElse(generic);
        if (source instanceof FeishuPublicSource && user != null && feishu.authorized(user)) {
            try {
                return feishu.readAsUser(user, link);
            } catch (RuntimeException asUserFailed) {
                // e.g. a public document from another company: the user's token can't open it, the public page can
            }
        }
        return source.read(uri, link, new Fetcher(link));
    }
}
