package com.superagent.task.harness;

import com.superagent.api.task.HarnessDto;

/** The harness state as the task page shows it (without the per-file diffs: those are fetched one at a time). */
public final class HarnessViews {

    private HarnessViews() {
    }

    public static HarnessDto dto(HarnessState s) {
        return new HarnessDto(s.harness,
                s.steps.stream().map(st -> new HarnessDto.Step(st.key, st.name, st.status.name().toLowerCase(),
                        st.startedAt, st.finishedAt, st.note)).toList(),
                s.repo == null ? null : new HarnessDto.Workspace(s.mode, s.mode != null && s.mode.equals("local")
                        && s.repoRoot != null ? s.repoRoot : s.repo, s.baseBranch, s.baseSha, s.branch, s.dir, s.delivery),
                s.spec, s.specApproved,
                s.criteria.stream().map(c -> new HarnessDto.Criterion(c.text(), c.met(), c.evidence())).toList(),
                s.verifyCommands,
                s.files.stream().map(f -> new HarnessDto.ChangedFile(f.path(), f.added(), f.removed())).toList(),
                s.rounds.stream().map(r -> new HarnessDto.VerifyRound(r.round(), r.at(), r.passed(),
                        r.checks().stream().map(c -> new HarnessDto.Check(c.command(), c.exitCode(), c.millis(), c.logTail()))
                                .toList(), r.fix())).toList(),
                s.findings.stream().map(f -> new HarnessDto.Finding(f.severity(), f.text(), f.where(), f.state())).toList(),
                s.deliveryNote == null && s.deliveryResult == null ? null
                        : new HarnessDto.Delivery(s.delivery, s.deliveryResult, s.deliveryNote));
    }
}
