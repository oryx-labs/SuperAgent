package com.superagent.files;

import com.superagent.core.writing.StyleGuideSource;
import com.superagent.core.writing.StyleLexicon;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileWriteStyleTest {

    @SuppressWarnings("unchecked")
    private static FileMcpTools tools(StyleGuideSource source) {
        FileMcpTools t = new FileMcpTools(null, null, null, null);
        ObjectProvider<StyleGuideSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(source);
        t.setStyle(p);
        return t;
    }

    private static final StyleGuideSource SOURCE = new StyleGuideSource() {
        @Override public Optional<String> compactGuide(boolean technical) { return Optional.empty(); }
        @Override public StyleLexicon lexicon() { return StyleLexicon.FALLBACK; }
    };

    @Test
    void markdownIsCheckedAndTheResultGoesBackToTheModel() {
        String heavy = "# 方案\n\n## 现状\n\n" + "值得注意的是，赋能业务，综上所述，全方位助力闭环。".repeat(10);
        assertThat(tools(SOURCE).writingNotes("方案.md", heavy)).contains("文字核对", "replace_id", "套话");
        assertThat(tools(SOURCE).writingNotes("方案.md", "# 方案\n\n## 现状\n\n用 `Cache` 缓存 60 秒，P95 85 ms。\n")).isEmpty();
        assertThat(tools(SOURCE).writingNotes("数据.csv", heavy)).isEmpty();
        assertThat(tools(null).writingNotes("方案.md", heavy)).isEmpty();
        assertThat(new FileMcpTools(null, null, null, null).writingNotes("方案.md", heavy)).isEmpty();
    }
}
