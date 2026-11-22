package com.superagent.orchestrator;

import com.superagent.agent.AgentService;
import com.superagent.agent.log.AgentLogService;
import com.superagent.api.proxyconfirm.ProxyConfirmDtos;
import com.superagent.core.error.ConflictException;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.notice.Notice;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.notice.NoticeStore;
import com.superagent.core.proxyconfirm.ConfirmType;
import com.superagent.core.proxyconfirm.ProxyConfirmGate;
import com.superagent.core.proxyconfirm.ProxyConfirmRecord;
import com.superagent.core.proxyconfirm.ProxyConfirmRule;
import com.superagent.core.proxyconfirm.ProxyConfirmStore;
import com.superagent.project.ProjectService;
import com.superagent.task.TaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Confirm for me": SuperAgent confirms, on the user's behalf and by the user's rules, what can go on by itself, and
 * hands back to the person whatever is unclear. Off unless the user turned it on; tier 3 is never confirmed.
 * Each confirmation is recorded, written to SuperAgent's run log and told to the owner on the to-do page.
 */
@Service
public class ProxyConfirmService implements ProxyConfirmGate {

    private final ProxyConfirmStore store;
    private final NoticeStore notices;
    private final AgentService agents;
    private final AgentLogService runLog;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<TaskService> tasks; // lazy: the task module asks this gate
    private final ObjectProvider<ProjectService> projects;

    public ProxyConfirmService(ProxyConfirmStore store, NoticeStore notices, AgentService agents, AgentLogService runLog,
                               ApplicationEventPublisher events, ObjectProvider<TaskService> tasks,
                               ObjectProvider<ProjectService> projects) {
        this.store = store;
        this.notices = notices;
        this.agents = agents;
        this.runLog = runLog;
        this.events = events;
        this.tasks = tasks;
        this.projects = projects;
    }

    // --- rules ---

    /** The rule in force: the project's own, else the user's global one, else none (off). */
    public Optional<ProxyConfirmRule> effective(String owner, String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            Optional<ProxyConfirmRule> own = store.findRule(owner, projectId);
            if (own.isPresent()) {
                return own;
            }
        }
        return store.findRule(owner, ProxyConfirmRule.GLOBAL);
    }

    public ProxyConfirmDtos.RuleDto rule(String owner, String projectId) {
        String pid = scope(owner, projectId);
        Optional<ProxyConfirmRule> own = store.findRule(owner, pid);
        ProxyConfirmRule shown = own.or(() -> store.findRule(owner, ProxyConfirmRule.GLOBAL)).orElse(null);
        return view(pid, shown, own.isEmpty() && !pid.isEmpty());
    }

    public ProxyConfirmDtos.RuleDto save(String owner, String projectId, ProxyConfirmDtos.SaveRule request) {
        String pid = scope(owner, projectId);
        Set<ConfirmType> types = EnumSet.noneOf(ConfirmType.class);
        if (request.types() == null) {
            Arrays.stream(ConfirmType.values()).filter(t -> t.tier() == 1).forEach(types::add);
        } else {
            for (String name : request.types()) {
                ConfirmType t;
                try {
                    t = ConfirmType.valueOf(name);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("未知的代确认类型：" + name);
                }
                if (!t.allowed()) {
                    throw new IllegalArgumentException(name + " 不能开启代确认：开 PR、邀请或移除协作者、删除、超出预算、密钥和授权，只提醒、必须本人确认");
                }
                types.add(t);
            }
        }
        ProxyConfirmRule rule = new ProxyConfirmRule(owner, pid, request.enabled(), types, Instant.now());
        store.saveRule(rule);
        return view(pid, rule, false);
    }

    /** A project goes back to the user's global rule. */
    public ProxyConfirmDtos.RuleDto inherit(String owner, String projectId) {
        String pid = scope(owner, projectId);
        if (!pid.isEmpty()) {
            store.deleteRule(owner, pid);
        }
        return rule(owner, pid);
    }

    private String scope(String owner, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return ProxyConfirmRule.GLOBAL;
        }
        ProjectService ps = projects.getIfAvailable();
        if (ps != null) {
            ps.get(projectId, owner); // only a member can set a project's rule
        }
        return projectId;
    }

    private static ProxyConfirmDtos.RuleDto view(String projectId, ProxyConfirmRule rule, boolean inherited) {
        boolean on = rule != null && rule.enabled();
        List<ProxyConfirmDtos.TypeDto> types = Arrays.stream(ConfirmType.values())
                .map(t -> new ProxyConfirmDtos.TypeDto(t.name(), t.tier(),
                        rule == null ? t.tier() == 1 : rule.types().contains(t) && t.allowed(), t.revocable())).toList();
        return new ProxyConfirmDtos.RuleDto(projectId, on, inherited, types);
    }

    // --- deciding ---

    @Override
    public Decision decide(String owner, String projectId, ConfirmType type, String objectType, String objectId,
                           Map<String, Object> facts) {
        try {
            ProxyConfirmRule rule = effective(owner, projectId).orElse(null);
            if (rule == null || !rule.enabled()) {
                return Decision.refer(null); // off: exactly as without this feature
            }
            if (!type.allowed()) {
                return refer(owner, type, objectType, objectId, "这类事项只能本人确认（" + type.name() + "）");
            }
            if (!rule.allows(type)) {
                return Decision.refer(null); // not ticked
            }
            String doubt = doubt(type, facts);
            if (doubt != null) {
                return refer(owner, type, objectType, objectId, doubt);
            }
            String reason = reason(type, facts);
            ProxyConfirmRecord record = new ProxyConfirmRecord(UUID.randomUUID().toString(), owner,
                    projectId == null ? "" : projectId, type, objectType, objectId, reason, type.revocable(),
                    ProxyConfirmRecord.CONFIRMED, Instant.now());
            store.saveRecord(record);
            log(owner, "代我确认：" + type.name() + " " + objectType + " " + objectId, reason);
            events.publishEvent(new NoticeEvents.ProxyConfirmed(record.id(), owner, type.name(), objectType, objectId, reason,
                    record.revocable(), false));
            return new Decision(true, reason, record.id());
        } catch (RuntimeException e) {
            return Decision.refer("判断时出错，交给本人：" + e.getMessage()); // unsure: back to the person
        }
    }

    @Override
    public Optional<ConfirmType> claimable(String owner, String recordId, String objectId) {
        return store.findRecord(recordId).filter(r -> r.owner().equals(owner) && ProxyConfirmRecord.CONFIRMED.equals(r.status())
                && r.objectId().equals(objectId)).map(ProxyConfirmRecord::type);
    }

    @Override
    public void attach(String owner, String recordId, String objectId) {
        store.findRecord(recordId).filter(r -> r.owner().equals(owner)).ifPresent(r -> store.saveRecord(new ProxyConfirmRecord(
                r.id(), r.owner(), r.projectId(), r.type(), r.objectType(), objectId, r.reason(), r.revocable(), r.status(), r.at())));
    }

    private Decision refer(String owner, ConfirmType type, String objectType, String objectId, String why) {
        log(owner, "代我确认：转给本人 " + type.name() + " " + objectType + " " + objectId, why);
        notices.save(notices.findByKey(owner, "proxyconfirm:refer:" + objectType + ":" + objectId)
                .map(n -> n.with(Notice.Kind.CONFIRM, Notice.State.OPEN, n.title(), why, "SuperAgent"))
                .orElseGet(() -> new Notice(UUID.randomUUID().toString(), owner, "proxyconfirm:refer:" + objectType + ":" + objectId,
                        Notice.Kind.CONFIRM, Notice.State.OPEN, "SuperAgent 没有代你确认：" + type.name(), why, "SuperAgent",
                        objectType, objectId, Instant.now(), Instant.now())));
        return Decision.refer(why);
    }

    /** Null when nothing stands in the way; else why it goes back to the person. */
    private static String doubt(ConfirmType type, Map<String, Object> facts) {
        return switch (type) {
            case TASK_PARAMS -> number(facts, "openParams") == 0 ? null : "参数表里还有 " + number(facts, "openParams") + " 项待你确认（❓）";
            case CODING_SPEC -> number(facts, "criteria") == 0 ? "规格里没有验收标准"
                    : number(facts, "commands") == 0 ? "规格里没有验证命令"
                    : !aligned(facts.get("prdAligned")) ? "规格里有待定或和 PRD 对不上的地方" : null;
            case WRITING_OUTLINE -> number(facts, "sections") == 0 ? "提纲里没有小节"
                    : number(facts, "problems") > 0 ? "提纲还有没解决的问题" : null;
            default -> Boolean.TRUE.equals(facts.get("clear")) ? null : "信息不全或有歧义，交给本人";
        };
    }

    /** {@code prdAligned} is a value, or a check that is only run once everything else has passed (it costs a model call). */
    private static boolean aligned(Object v) {
        return Boolean.TRUE.equals(v instanceof java.util.function.Supplier<?> s ? s.get() : v);
    }

    private static String reason(ConfirmType type, Map<String, Object> facts) {
        return switch (type) {
            case TASK_PARAMS -> "参数表里没有待确认项（❓），按你的规则直接创建";
            case CODING_SPEC -> "验收标准 " + number(facts, "criteria") + " 条、验证命令 " + number(facts, "commands") + " 条，齐全且和 PRD 一致";
            case WRITING_OUTLINE -> "提纲完整（" + number(facts, "sections") + " 个小节），没有未解决的问题";
            default -> String.valueOf(facts.getOrDefault("why", "条件齐全，按你的规则确认"));
        };
    }

    private static int number(Map<String, Object> facts, String key) {
        return facts.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private void log(String owner, String summary, String detail) {
        agents.findSuperAgent(owner).ifPresent(sa -> runLog.info(sa, AgentLogService.SUPERAGENT, summary, detail));
    }

    // --- records ---

    public List<ProxyConfirmDtos.RecordDto> mine(String owner) {
        return store.records(owner, 200).stream().map(ProxyConfirmService::dto).toList();
    }

    /** Admin audit: everyone's in this company, filtered by user and type. */
    public List<ProxyConfirmDtos.RecordDto> audit(String owner, String type, Instant from, Instant to) {
        return store.records(owner == null || owner.isBlank() ? null : owner, 1000).stream()
                .filter(r -> type == null || type.isBlank() || r.type().name().equals(type))
                .filter(r -> from == null || !r.at().isBefore(from))
                .filter(r -> to == null || r.at().isBefore(to))
                .map(ProxyConfirmService::dto).toList();
    }

    /** Takes a confirmation back while its task has not started; says so plainly when it can't be. */
    public ProxyConfirmDtos.RecordDto revoke(String owner, String id) {
        ProxyConfirmRecord r = store.findRecord(id).filter(x -> x.owner().equals(owner))
                .orElseThrow(() -> new NotFoundException("没有这条代确认记录"));
        if (ProxyConfirmRecord.REVOKED.equals(r.status())) {
            return dto(r);
        }
        if (!r.revocable()) {
            throw new ConflictException("不可撤回：" + r.type().name() + " 一旦确认就已经生效，没有可以恢复的状态");
        }
        TaskService ts = tasks.getObject();
        if (!"pending".equalsIgnoreCase(ts.status(r.objectId(), owner).status())) {
            throw new ConflictException("不可撤回：任务已经开始执行");
        }
        ts.holdForConfirmation(r.objectId(), owner);
        ProxyConfirmRecord revoked = r.revoked();
        store.saveRecord(revoked);
        log(owner, "代我确认：已撤回 " + r.type().name() + " " + r.objectType() + " " + r.objectId(), r.reason());
        events.publishEvent(new NoticeEvents.ProxyConfirmed(r.id(), owner, r.type().name(), r.objectType(), r.objectId(),
                "任务还没开始，已回到待你确认；在待办里确认后才会开始。", false, true));
        return dto(revoked);
    }

    private static ProxyConfirmDtos.RecordDto dto(ProxyConfirmRecord r) {
        return new ProxyConfirmDtos.RecordDto(r.id(), r.owner(), r.projectId(), r.type().name(), r.type().tier(), r.objectType(),
                r.objectId(), r.reason(), r.revocable() && ProxyConfirmRecord.CONFIRMED.equals(r.status()), r.status(), r.at());
    }
}
