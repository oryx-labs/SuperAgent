package com.superagent.api.task;

import com.superagent.api.glossary.GlossaryTermDto;

import java.util.List;
import java.util.Map;

/**
 * A subtitle task's result as its page shows it: the subtitles as they stand, every disputed span (what each speech
 * model heard, what each text model wrote, what was taken and why), and the glossary they were spelt by.
 */
public record SubtitleDto(String audio, List<String> references, List<String> models, String skeleton, List<String> failed,
                          List<String> calibrators, boolean single, int termsFound, int termsAdded, List<Cue> cues,
                          List<Dispute> disputes, String projectId, List<GlossaryTermDto> terms, String srtFileId,
                          String doubtsFileId) {

    public record Cue(int n, double start, double end, String text) {
    }

    /** {@code heard}: in the order of {@link #models}; {@code slide}: the reference page it was matched with. */
    public record Dispute(String id, double start, double end, List<String> heard, Map<String, String> calibrated,
                          String text, boolean adopted, boolean doubt, String reason, String userText, String slide) {
    }
}
