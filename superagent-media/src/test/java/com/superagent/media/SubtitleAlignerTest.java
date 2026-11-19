package com.superagent.media;

import com.superagent.core.media.TextTokens;
import com.superagent.core.media.Transcript;
import com.superagent.core.media.TranscriptAligner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Transcripts lined up on the skeleton: what all agree on is taken, each difference is one disputed span. */
class SubtitleAlignerTest {

    private final SubtitleAligner aligner = new SubtitleAligner();

    private static Transcript timed(String... cues) {
        List<Transcript.Cue> out = new ArrayList<>();
        for (int i = 0; i < cues.length; i++) {
            out.add(new Transcript.Cue(i * 3, i * 3 + 3, cues[i]));
        }
        return new Transcript("whisper-1", true, out, cues.length * 3, 0);
    }

    private static Transcript text(String model, String text) {
        return new Transcript(model, false, List.of(new Transcript.Cue(0, 9, text)), 9, 0);
    }

    @Test
    void agreedCuesAreTakenAndEachDifferenceIsOneDisputedSpan() {
        TranscriptAligner.Alignment a = aligner.align(List.of(
                timed("今天我们讲 transformer 里的 attention 机制。", "先回顾一下上节课", "它的问题是 context window 不够用"),
                text("gpt-4o-transcribe", "今天我们讲Transformer里的Attention机制，先回顾一下上节课。它的问题是context window不够用"),
                text("gpt-4o-mini-transcribe", "今天我们讲穿死佛么里的 attention 机制 先回顾一下上节课 它的问题是 context window 不够用")),
                0);

        assertThat(a.models()).containsExactly("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe");
        // Case and punctuation don't count: only the misheard word is in dispute.
        assertThat(a.disputes()).isEqualTo(1);
        assertThat(a.agreedCues()).isEqualTo(2);
        TranscriptAligner.AlignedCue first = a.cues().get(0);
        assertThat(first.spans()).hasSize(3);
        TranscriptAligner.Span d = first.spans().get(1);
        assertThat(d.disputed()).isTrue();
        assertThat(d.versions()).containsExactly("transformer", "Transformer", "穿死佛么");
        assertThat(first.spans().get(0).text()).isEqualTo("今天我们讲");
        assertThat(first.spans().get(2).text()).isEqualTo("里的 attention 机制");
        assertThat(a.cues().get(2).spans()).singleElement()
                .satisfies(s -> assertThat(s.text()).isEqualTo("它的问题是 context window 不够用"));
    }

    @Test
    void wordsOnlyOneVersionHeardAreDisputedAndLongTextAlignsInWindows() {
        TranscriptAligner.Alignment a = aligner.align(List.of(timed("我们用 LoRA 微调"),
                text("gpt-4o-transcribe", "我们用 LoRA 来微调")), 0);
        assertThat(a.cues().get(0).spans()).extracting(TranscriptAligner.Span::disputed).containsExactly(false, true, false);
        assertThat(a.cues().get(0).spans().get(1).versions()).containsExactly("", "来");

        // Well past one window: an identical copy agrees everywhere, one changed character is the only dispute.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("模型 ").append("token").append(i).append(" 很好");
        }
        String same = sb.toString();
        String changed = same.replace("token333 很好", "token333 很坏");
        TranscriptAligner.Alignment big = aligner.align(List.of(timed(same), text("x", changed)), 0);
        assertThat(big.disputes()).isEqualTo(1);
        assertThat(TextTokens.of(same)).hasSizeGreaterThan(2 * SubtitleAligner.WINDOW);
    }
}
