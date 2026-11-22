package com.superagent.task.harness;

import com.superagent.api.task.HarnessDto;

/** The harness state as the task page shows it (without the per-file diffs: those are fetched one at a time). */
public final class HarnessViews {

    private static final String GENERAL = "general", COMPLEX = "complex";

    private HarnessViews() {
    }

    public static HarnessDto dto(HarnessState s) {
        return new HarnessDto(s.harness,
                s.steps.stream().map(st -> new HarnessDto.Step(st.key, st.name, st.status.name().toLowerCase(),
                        st.startedAt, st.finishedAt, st.note)).toList(),
                s.repo == null ? null : new HarnessDto.Workspace(s.mode, s.mode != null && s.mode.equals("local")
                        && s.repoRoot != null ? s.repoRoot : s.repo, s.baseBranch, s.baseSha, s.branch, s.dir, s.delivery),
                s.spec, s.specApproved,
                s.criteria.stream().map(c -> new HarnessDto.Criterion(c.text(), c.met(), c.evidence(), c.agentAdded())).toList(),
                s.verifyCommands,
                s.files.stream().map(f -> new HarnessDto.ChangedFile(f.path(), f.added(), f.removed())).toList(),
                s.rounds.stream().map(r -> new HarnessDto.VerifyRound(r.round(), r.at(), r.passed(),
                        r.checks().stream().map(c -> new HarnessDto.Check(c.command(), c.exitCode(), c.millis(), c.logTail()))
                                .toList(), r.fix())).toList(),
                s.findings.stream().map(HarnessViews::finding).toList(),
                s.deliveryNote == null && s.deliveryResult == null ? null
                        : new HarnessDto.Delivery(s.delivery, s.deliveryResult, s.deliveryNote),
                s.reviews.stream().map(r -> new HarnessDto.ReviewRound(r.round(), r.kind(), r.at(), r.model(), r.verdict(),
                        r.passed(), r.blocking(), r.findings().stream().map(HarnessViews::finding).toList(),
                        r.criteria().stream().map(c -> new HarnessDto.Criterion(c.text(), c.met(), c.evidence(), false)).toList(),
                        r.rebuttals().stream().map(b -> new HarnessDto.Rebuttal(b.id(), b.reason(), b.ruling(), b.rulingReason()))
                                .toList(), r.reverted())).toList(),
                s.stopReason,
                !GENERAL.equals(s.harness) && !COMPLEX.equals(s.harness) ? null : new HarnessDto.General(s.round, s.roundLimit,
                        s.deliverables.stream().map(d -> new HarnessDto.Deliverable(d.name(), d.type())).toList(),
                        s.checks.stream().map(c -> new HarnessDto.CheckRound(c.round(), c.at(), c.passed(),
                                c.platform().stream().map(p -> new HarnessDto.PlatformCheck(p.name(), p.passed(), p.detail(), p.warning())).toList(),
                                c.verdict(), c.checker(), c.issues())).toList(), s.reply),
                s.conflicts.stream().map(c -> new HarnessDto.ConflictRound(c.round(), c.at(), c.model(), c.commit(), c.files(),
                        c.summary(), c.checks().stream().map(p -> new HarnessDto.PlatformCheck(p.name(), p.passed(), p.detail(), p.warning()))
                                .toList(), c.verdict(), c.passed(), c.reason())).toList(),
                s.article == null ? null : article(s.article));
    }

    private static HarnessDto.Article article(HarnessState.Article a) {
        HarnessState.Article.Brief b = a.brief;
        return new HarnessDto.Article(
                b == null ? null : new HarnessDto.Brief(b.genre(), b.audience(), b.knows(), b.purpose(), b.thesis(), b.words(),
                        b.style(), b.mustUse(), b.exclude(), b.acceptance()),
                a.sources.stream().map(x -> new HarnessDto.Source(x.id(), x.title(), x.ref(), x.status(), x.summary())).toList(),
                a.claims.stream().map(c -> new HarnessDto.Claim(c.text(), c.sources(), c.status())).toList(),
                a.judgement, a.outline, a.titles, a.thesis,
                a.sections.stream().map(x -> new HarnessDto.Section(x.title(), x.point(), x.sources(), x.words())).toList(),
                a.outlineApproved, a.approvals, a.approvedBy, a.draftFileId, a.draftName, a.draftSnapshot,
                a.checks.stream().map(c -> new HarnessDto.ArticleCheck(c.round(), c.at(), c.passed(),
                        c.items().stream().map(i -> new HarnessDto.Issue(i.check(), i.level(), i.where(), i.note())).toList())).toList(),
                a.reviews.stream().map(r -> new HarnessDto.ArticleReview(r.round(), r.at(), r.editorModel(), r.checkerModel(),
                        r.scores().stream().map(x -> new HarnessDto.Score(x.dimension(), x.name(), x.score(), x.quote(), x.advice())).toList(),
                        r.total(), items(r.editor()), r.titles(),
                        r.facts().stream().map(f -> new HarnessDto.Fact(f.statement(), f.sources(), f.verdict(), f.evidence(), f.adjusted()))
                                .toList(), r.reader(), r.retelling(), r.consistent(), items(r.merged()), r.route(), r.passed())).toList(),
                a.revisions.stream().map(v -> new HarnessDto.Revision(v.round(), v.at(), v.from(), items(v.items()),
                        v.responses().stream().map(x -> new HarnessDto.Response(x.id(), x.action(), x.note())).toList(), v.changed()))
                        .toList(),
                a.fixRounds, a.reviewReturns, a.outlineReturns, a.researchReturns,
                com.superagent.task.harness.article.ArticleHarness.RETURNS, a.checkWaived, a.acceptedAsIs,
                a.pack != null ? a.pack.name() : a.genre,
                a.evidence.stream().map(e -> new HarnessDto.Evidence(e.id(), e.type(), e.key(), e.body())).toList());
    }

    private static java.util.List<HarnessDto.Item> items(java.util.List<HarnessState.Article.Item> list) {
        return list.stream().map(i -> new HarnessDto.Item(i.id(), i.severity(), i.type(), i.text(), i.where(), i.from(), i.outline()))
                .toList();
    }

    private static HarnessDto.Finding finding(HarnessState.Finding f) {
        return new HarnessDto.Finding(f.severity(), f.text(), f.where(), f.state(), f.id());
    }
}
