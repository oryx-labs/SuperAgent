package com.superagent.media;

import com.superagent.core.chat.RunCredential;
import com.superagent.core.error.NeedsUserException;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecorder;
import com.superagent.platform.provider.CredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Speech to text on the company's OpenAI keys: timings become SRT, the minutes are billed, a failing key is switched. */
class TranscriberTest {

    @TempDir
    Path tmp;

    private final CredentialService credentials = mock(CredentialService.class);
    private final CredentialService.Choice first = new CredentialService.Choice(new RunCredential("k1", null, "a", true));
    private final CredentialService.Choice second = new CredentialService.Choice(new RunCredential("k2", null, "b", true));

    @Test
    void aFailingKeyIsSwitchedAndTheResultIsTimedSubtitlesBilledOnThePerson() throws Exception {
        when(credentials.current("codex")).thenReturn(first);
        when(credentials.failover(eq("codex"), eq(first), any())).thenReturn(Optional.of(second));
        List<String> keysTried = new ArrayList<>();
        Transcriber.Api api = (credential, file, request) -> {
            keysTried.add(credential.id());
            if (credential.id().equals("k1")) {
                throw new Transcriber.ApiException("HTTP 401: invalid api key");
            }
            assertThat(request.language()).isEqualTo("zh");
            assertThat(request.model()).isEqualTo("whisper-1");
            return OpenAiTranscriptions.parse("""
                    {"duration": 90.5, "segments": [
                      {"start": 0.0, "end": 3.2, "text": " 大家好"},
                      {"start": 3.2, "end": 65.25, "text": "今天讲第二课"}]}""");
        };
        List<Double> billed = new ArrayList<>();
        Transcriber t = new Transcriber(credentials, api, null);
        t.setUsage(new UsageRecorder() {
            @Override public Double record(UsageContext c, String json) { return null; }
            @Override public void recordCost(UsageContext c, String model, double usd) { billed.add(usd); }
        });
        Path audio = Files.write(tmp.resolve("第02课.mp3"), new byte[]{1, 2, 3});

        Transcriber.Result r = t.transcribe(audio, "zh", new UsageContext("ada", "transcribe", null, "t1", null, "codex"));

        assertThat(keysTried).containsExactly("k1", "k2");
        assertThat(r.srt()).isEqualTo("1\n00:00:00,000 --> 00:00:03,200\n大家好\n\n2\n00:00:03,200 --> 00:01:05,250\n今天讲第二课\n\n");
        assertThat(r.text()).isEqualTo("大家好\n今天讲第二课\n");
        assertThat(billed).singleElement().satisfies(usd -> assertThat(usd).isCloseTo(90.5 / 60 * 0.006, within(1e-9)));
    }

    @Test
    void withoutFfmpegAFileTheApiCantTakeIsForTheUserToFixAndWithItItIsCutIntoPieces() throws Exception {
        Path big = tmp.resolve("long.mp3");
        try (var f = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(26L * 1024 * 1024); // sparse: nothing is written
        }
        Transcriber without = new Transcriber(credentials, (c, f, r) -> { throw new AssertionError("not called"); }, null);
        assertThat(without.converts()).isFalse();
        assertThatThrownBy(() -> without.transcribe(big, null, null)).isInstanceOf(NeedsUserException.class)
                .hasMessageContaining("超过转写单次上限 24 MB").hasMessageContaining("安装 ffmpeg").hasMessageContaining("单声道 mp3");
        Path mov = Files.write(tmp.resolve("clip.mov"), new byte[]{1});
        assertThatThrownBy(() -> without.transcribe(mov, null, null)).isInstanceOf(NeedsUserException.class)
                .hasMessageContaining("不直接支持 .mov");

        when(credentials.current("codex")).thenReturn(first);
        List<Path> sent = new ArrayList<>();
        Ffmpeg fake = new Ffmpeg(Path.of("/nowhere/ffmpeg")) {
            @Override List<Path> split(Path input, Path dir, int seconds) throws java.io.IOException {
                assertThat(seconds).isEqualTo(Transcriber.PIECE_SECONDS);
                return List.of(Files.write(dir.resolve("part000.mp3"), new byte[]{1}), Files.write(dir.resolve("part001.mp3"), new byte[]{2}));
            }
        };
        Transcriber with = new Transcriber(credentials, (c, f, r) -> {
            sent.add(f.getFileName());
            return OpenAiTranscriptions.parse("{\"duration\": 1200, \"segments\": [{\"start\": 1.0, \"end\": 2.0, \"text\": \"段\"}]}");
        }, fake);
        assertThat(with.converts()).isTrue();

        Transcriber.Result r = with.transcribe(big, null, null);

        assertThat(sent).extracting(Path::toString).containsExactly("part000.mp3", "part001.mp3");
        assertThat(r.segments()).extracting(Transcriber.Segment::start).containsExactly(1.0, 1201.0);
        assertThat(r.seconds()).isEqualTo(2400.0);
    }
}
