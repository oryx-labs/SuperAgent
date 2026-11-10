package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    @Test
    void keepsThePagesMainTextWithItsStructureAndDropsTheChrome() {
        Document doc = Jsoup.parse("""
                <html><head><title>站点</title><meta property="og:title" content="光通讯入门"><script>var x=1</script></head>
                <body><nav>首页 | 登录</nav><header>广告条</header>
                <article><h1>光通讯入门</h1><p>光纤用全反射传光。</p>
                <ul><li>单模光纤</li><li>多模光纤</li></ul>
                <table><tr><th>概念</th><th>英文</th></tr><tr><td>波分复用</td><td>WDM</td></tr></table>
                <pre>loss = 0.2 dB/km</pre></article>
                <footer>版权所有</footer></body></html>""");

        assertThat(HtmlText.title(doc)).isEqualTo("光通讯入门");
        String text = HtmlText.main(doc);
        assertThat(text).contains("# 光通讯入门", "光纤用全反射传光。", "- 单模光纤", "| 概念 | 英文 |", "| 波分复用 | WDM |",
                "```\nloss = 0.2 dB/km\n```");
        assertThat(text).doesNotContain("首页", "广告条", "版权所有", "var x");
    }

    @Test
    void longContentComesInPartsThatSayWhereToGoOn() {
        String text = "字".repeat(ReadLinkTools.PART + 10);
        LinkContent c = new LinkContent("https://example.com/a", "长文", text, LinkContent.PUBLIC, false);

        String first = ReadLinkTools.part(c, 0);
        assertThat(first).contains("《长文》", "公开内容，未登录", "只读到了一部分", "start=" + ReadLinkTools.PART);
        String rest = ReadLinkTools.part(c, ReadLinkTools.PART);
        assertThat(rest).contains("（从第 " + ReadLinkTools.PART + " 字开始）").doesNotContain("接着读");
    }
}
