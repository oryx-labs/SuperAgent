package com.superagent.cost.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.usage.UsageDtos;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.usage.TokenUsage;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageRecorder;
import com.superagent.core.usage.UsageStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.cost.ModelPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every model run's tokens, on the person it was for, and what they come to. Tokens are the raw cost and are kept
 * exactly as the CLI counted them; the dollar figure is an estimate from list prices at the time (prices move, relays
 * bill differently), kept with each record so past totals don't shift.
 */
@Service
public class TokenUsageService implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);
    /** Without a listed cache price: what the main vendors charge relative to input (reads ~10%, writes 1.25×, 1h 2×). */
    private static final double CACHE_READ = 0.1;
    /** The background's own checks are booked to this "user" (see ProviderInferenceProbe). */
    private static final String SYSTEM = com.superagent.platform.provider.ProviderInferenceProbe.SYSTEM_USER;
    private static final double CACHE_WRITE = 1.25;
    private static final double CACHE_WRITE_1H = 2.0;

    private final UsageStore store;
    private final ModelPriceService prices;
    private final AgentStore agents;
    private final UserStore users;
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final com.superagent.core.task.TaskStore tasks;
    private com.superagent.core.task.TaskLinkStore links; // null in tests: no subtasks

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLinks(com.superagent.core.task.TaskLinkStore links) {
        this.links = links;
    }

    /** The task a run counts toward: a subtask's runs count toward its complex task. */
    private java.util.function.UnaryOperator<String> rootTask() {
        if (links == null) {
            return id -> id;
        }
        Map<String, String> parent = new java.util.HashMap<>();
        links.all().stream().filter(l -> l.parentId() != null).forEach(l -> parent.put(l.taskId(), l.parentId()));
        return id -> id == null ? null : parent.getOrDefault(id, id);
    }

    public TokenUsageService(UsageStore store, ModelPriceService prices, AgentStore agents, UserStore users,
                             com.superagent.core.task.TaskStore tasks) {
        this.store = store;
        this.prices = prices;
        this.agents = agents;
        this.users = users;
        this.tasks = tasks;
    }

    // --- recording ---

    @Override
    public Double record(UsageContext context, String tokensJson) {
        List<TokenUsage> used;
        try {
            used = List.of(json.readValue(tokensJson, TokenUsage[].class));
        } catch (Exception e) {
            log.warn("unreadable token usage from a {} run: {}", context.source(), tokensJson);
            return null;
        }
        Instant now = Instant.now();
        Double total = null;
        for (TokenUsage u : used) {
            if (u.nothingUsed()) {
                continue;
            }
            Double usd = cost(u);
            store.save(new UsageRecord(UUID.randomUUID().toString(), now, context.user(), context.source(),
                    context.agentId(), context.taskId(), context.sessionId(), context.cli(), u.model(), u.input(),
                    u.cacheRead(), u.cacheWrite(), u.cacheWrite1h(), u.output(), u.reasoning(), usd, u.cliCostUsd()));
            if (usd != null) {
                total = (total == null ? 0 : total) + usd;
            }
        }
        return total;
    }

    /** USD by list price; null when the model has none. */
    Double cost(TokenUsage u) {
        Optional<ModelPriceService.Price> found = prices.price(u.model());
        if (found.isEmpty()) {
            return null;
        }
        ModelPriceService.Price p = found.get();
        double read = p.cacheRead() != null ? p.cacheRead() : p.input() * CACHE_READ;
        double write = p.cacheWrite() != null ? p.cacheWrite() : p.input() * CACHE_WRITE;
        long hour = Math.min(u.cacheWrite1h(), u.cacheWrite());
        double usd = u.input() * p.input() + u.cacheRead() * read + (u.cacheWrite() - hour) * write
                + hour * p.input() * CACHE_WRITE_1H + u.output() * p.output();
        return usd / 1_000_000;
    }

    // --- reading ---

    public UsageDtos.Task forTask(String taskId) {
        // A complex task's cost is its own runs plus every subtask's.
        List<String> kids = links == null ? List.of() : links.children(taskId);
        List<UsageRecord> records = new java.util.ArrayList<>(store.forTask(taskId));
        kids.forEach(k -> records.addAll(store.forTask(k)));
        records.sort(Comparator.comparing(UsageRecord::at));
        Map<String, String> agentNames = agentNames();
        List<UsageDtos.Total> byTask = kids.isEmpty() ? List.of() : group(records, UsageRecord::taskId,
                id -> id.equals(taskId) ? "" : tasks.findTask(id).map(com.superagent.core.task.Task::title).orElse(id));
        return new UsageDtos.Task(records.stream().map(r -> row(r, agentNames)).toList(),
                group(records, UsageRecord::model, m -> m), total(records), byTask);
    }

    /** Usage from {@code from} to {@code to}: {@code user}'s own, or everyone's (null) with a breakdown by person. */
    public UsageDtos.Summary summary(String user, Instant from, Instant to, ZoneId zone) {
        List<UsageRecord> records = store.between(user, from, to);
        Map<String, String> agentNames = agentNames();
        Map<String, String> names = new java.util.HashMap<>();
        if (user == null) {
            users.listUsers().forEach(u -> names.put(u.id(), u.displayName() == null ? u.username() : u.displayName()));
        }
        List<UsageDtos.Total> byDay = group(records, r -> LocalDate.ofInstant(r.at(), zone).toString(), d -> d);
        byDay = byDay.stream().sorted(Comparator.comparing(UsageDtos.Total::key)).toList();
        // Two kinds: a task's runs (its own and choosing its model), and everything else, by agent; and apart from
        // both, the system's own background checks, which are nobody's.
        List<UsageRecord> forSystem = records.stream().filter(r -> SYSTEM.equals(r.user())).toList();
        List<UsageRecord> people = records.stream().filter(r -> !SYSTEM.equals(r.user())).toList();
        List<UsageRecord> forTasks = people.stream().filter(r -> r.taskId() != null).toList();
        List<UsageRecord> forAgents = people.stream().filter(r -> r.taskId() == null).toList();
        Map<String, String> titles = new java.util.HashMap<>();
        forTasks.forEach(r -> titles.computeIfAbsent(r.taskId(), id -> tasks.findTask(id).map(com.superagent.core.task.Task::title).orElse(null)));
        java.util.function.UnaryOperator<String> root = rootTask(); // a subtask's runs show under its complex task
        forTasks.forEach(r -> titles.computeIfAbsent(root.apply(r.taskId()), id -> tasks.findTask(id).map(com.superagent.core.task.Task::title).orElse(null)));
        List<UsageDtos.Total> byTask = group(forTasks, r -> root.apply(r.taskId()), id -> titles.get(id) == null ? "" : titles.get(id));
        List<UsageDtos.Total> byAgent = group(forAgents, r -> r.agentId() == null ? "" : r.agentId(),
                a -> a.isEmpty() ? "" : agentNames.getOrDefault(a, ""));
        return new UsageDtos.Summary(from, to, total(records), total(forTasks), total(forAgents), byDay,
                latestFirst(byTask), byAgent, group(records, UsageRecord::model, m -> m),
                user == null ? group(people, r -> r.user() == null ? "" : r.user(), u -> names.getOrDefault(u, u)) : List.of(),
                total(forSystem), group(forSystem, r -> r.source() + "|" + r.model(), k -> k));
    }

    private static List<UsageDtos.Total> latestFirst(List<UsageDtos.Total> rows) {
        return rows.stream().sorted(Comparator.comparing(UsageDtos.Total::lastAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private Map<String, String> agentNames() {
        Map<String, String> names = new java.util.HashMap<>();
        for (Agent a : agents.list()) {
            names.put(a.id(), a.name());
        }
        return names;
    }

    private static UsageDtos.Row row(UsageRecord r, Map<String, String> agentNames) {
        return new UsageDtos.Row(r.at(), r.source(), r.user(), r.agentId(),
                r.agentId() == null ? null : agentNames.get(r.agentId()), r.cli(), r.model(), r.input(), r.cacheRead(),
                r.cacheWrite(), r.output(), r.reasoning(), r.total(), r.costUsd(), r.cliCostUsd(), r.taskId());
    }

    /** Totals per key, the most tokens first. */
    private static List<UsageDtos.Total> group(List<UsageRecord> records, Function<UsageRecord, String> key,
                                               Function<String, String> label) {
        Map<String, List<UsageRecord>> by = new LinkedHashMap<>();
        records.forEach(r -> by.computeIfAbsent(String.valueOf(key.apply(r)), k -> new ArrayList<>()).add(r));
        List<UsageDtos.Total> out = new ArrayList<>();
        by.forEach((k, list) -> out.add(sum(k, label.apply(k), list)));
        out.sort(Comparator.comparingLong(UsageDtos.Total::total).reversed());
        return out;
    }

    private static UsageDtos.Total total(List<UsageRecord> records) {
        return sum("total", "total", records);
    }

    private static UsageDtos.Total sum(String key, String label, List<UsageRecord> records) {
        long in = 0, read = 0, write = 0, out = 0, reasoning = 0;
        double usd = 0;
        boolean priced = false, partial = false;
        Map<String, Long> models = new java.util.HashMap<>();
        Instant last = null;
        for (UsageRecord r : records) {
            if (r.model() != null) {
                models.merge(r.model(), r.total(), Long::sum);
            }
            last = last == null || r.at().isAfter(last) ? r.at() : last;
            in += r.input();
            read += r.cacheRead();
            write += r.cacheWrite();
            out += r.output();
            reasoning += r.reasoning();
            if (r.costUsd() != null) {
                usd += r.costUsd();
                priced = true;
            } else {
                partial = true;
            }
        }
        List<String> used = models.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        return new UsageDtos.Total(key, label, in, read, write, out, reasoning, in + read + write + out,
                priced ? usd : null, partial, records.size(), used, last);
    }
}
