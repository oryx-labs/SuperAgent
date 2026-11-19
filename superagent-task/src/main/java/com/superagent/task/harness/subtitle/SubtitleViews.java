package com.superagent.task.harness.subtitle;

import com.superagent.api.glossary.GlossaryTermDto;
import com.superagent.api.task.SubtitleDto;

import java.util.ArrayList;
import java.util.List;

/** A subtitle task's record as its page shows it. */
public final class SubtitleViews {

    private SubtitleViews() {
    }

    public static SubtitleDto dto(SubtitleHarness.SubtitleView v, String projectId) {
        SubtitleRecord r = v.record();
        List<SubtitleDto.Cue> cues = new ArrayList<>();
        for (int i = 0; i < v.cues().size(); i++) {
            SubtitleChecks.Cue c = v.cues().get(i);
            cues.add(new SubtitleDto.Cue(i + 1, c.start(), c.end(), c.text()));
        }
        List<SubtitleDto.Dispute> disputes = r.decisions.values().stream().map(d -> new SubtitleDto.Dispute(d.id(), d.start(),
                d.end(), d.heard(), d.calibrated(), d.text(), d.adopted(), d.doubt(), d.reason(), d.userText(),
                d.slide() == null || d.slide() >= r.slides.size() ? null
                        : r.slides.get(d.slide()).file() + " 第 " + r.slides.get(d.slide()).page() + " 页")).toList();
        List<GlossaryTermDto> terms = v.terms().stream().map(t -> new GlossaryTermDto(t.id(), t.projectId(), t.term(),
                t.aliases(), t.category(), t.source(), t.sourceRef(), t.createdBy(), t.updatedAt())).toList();
        return new SubtitleDto(r.audioName, r.references.stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).toList(),
                r.models, r.models.isEmpty() ? null : r.models.get(r.skeleton), r.failed, r.calibrators, r.single,
                r.termsFound, r.termsAdded, cues, disputes, projectId, terms, r.srtFileId, r.doubtsFileId);
    }
}
