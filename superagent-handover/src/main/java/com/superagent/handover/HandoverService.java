package com.superagent.handover;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.agent.AgentService;
import com.superagent.api.handover.HandoverPreviewDto;
import com.superagent.api.handover.HandoverPreviewDto.TaskCounts;
import com.superagent.api.handover.HandoverResultDto;
import com.superagent.api.user.SaveUserRequest;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.SuperAgentWork;
import com.superagent.core.agent.SuperAgentWorkStore;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.file.FileFolderStore;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.handover.HandoverRecord;
import com.superagent.core.handover.HandoverRecordStore;
import com.superagent.core.project.ProjectStore;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.User;
import com.superagent.files.FileFolderService;
import com.superagent.files.FileService;
import com.superagent.platform.user.PeopleDirectory;
import com.superagent.platform.user.UserService;
import com.superagent.project.ProjectService;
import com.superagent.skills.SkillService;
import com.superagent.task.TaskService;
import com.superagent.task.TaskService.TaskHandoverResult;
import com.superagent.task.automation.AutomationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin employee handover: preview and execute bulk ownership transfer from one user to another
 * inside the same company. Domain modules own their transfer primitives; this service only orchestrates.
 */
@Service
public class HandoverService {

    private static final Logger log = LoggerFactory.getLogger(HandoverService.class);

    private final PeopleDirectory people;
    private final UserService users;
    private final AgentService agents;
    private final TaskService tasks;
    private final TaskStore taskStore;
    private final AutomationService automations;
    private final AutomationStore automationStore;
    private final FileService files;
    private final FileFolderService folders;
    private final FileFolderStore folderStore;
    private final WorkFileStore fileStore;
    private final ProjectService projects;
    private final ProjectStore projectStore;
    private final SkillService skills;
    private final HandoverRecordStore records;
    private final ObjectMapper json = new ObjectMapper();

    private SuperAgentWorkStore superWorks; // optional in older wiring

    public HandoverService(PeopleDirectory people, UserService users, AgentService agents, TaskService tasks,
                           TaskStore taskStore, AutomationService automations, AutomationStore automationStore,
                           FileService files, FileFolderService folders, FileFolderStore folderStore,
                           WorkFileStore fileStore, ProjectService projects, ProjectStore projectStore,
                           SkillService skills, HandoverRecordStore records) {
        this.people = people;
        this.users = users;
        this.agents = agents;
        this.tasks = tasks;
        this.taskStore = taskStore;
        this.automations = automations;
        this.automationStore = automationStore;
        this.files = files;
        this.folders = folders;
        this.folderStore = folderStore;
        this.fileStore = fileStore;
        this.projects = projects;
        this.projectStore = projectStore;
        this.skills = skills;
        this.records = records;
    }

    @Autowired(required = false)
    void setSuperWorks(SuperAgentWorkStore superWorks) {
        this.superWorks = superWorks;
    }

    public HandoverPreviewDto preview(String fromUsername, String toUsername) {
        Pair pair = resolvePair(fromUsername, toUsername);
        Snapshot snap = snapshot(pair.from().id());
        List<String> warnings = warnings(pair.from().id(), snap);
        return new HandoverPreviewDto(pair.from().id(), pair.from().username(), pair.to().id(), pair.to().username(),
                snap.agents, snap.superAgents, snap.tasks, snap.automations, snap.files, snap.folders, snap.projects,
                snap.personalSkills, snap.openWork, warnings);
    }

    public HandoverResultDto execute(String fromUsername, String toUsername, boolean disableFromUser, String byUserId) {
        Pair pair = resolvePair(fromUsername, toUsername);
        Snapshot before = snapshot(pair.from().id());
        List<String> warnings = warnings(pair.from().id(), before);

        Instant now = Instant.now();
        HandoverRecord running = new HandoverRecord(UUID.randomUUID().toString(), pair.from().id(),
                pair.from().username(), pair.to().id(), pair.to().username(), byUserId, HandoverRecord.RUNNING,
                null, now, null);
        records.save(running);

        try {
            int agentsMoved = agents.transferOwnership(pair.from().id(), pair.to().id());
            TaskHandoverResult taskResult = tasks.transferOwnership(pair.from().id(), pair.to().id());
            int automationsMoved = automations.transferOwnership(pair.from().id(), pair.to().id());
            int filesMoved = files.transferOwnership(pair.from().id(), pair.to().id());
            int foldersMoved = folders.transferOwnership(pair.from().id(), pair.to().id());
            int projectsMoved = projects.transferOwnership(pair.from().id(), pair.to().id());
            int skillsMoved = skills.transferPersonalSkills(pair.from().id(), pair.to().id());
            int workMoved = transferOpenSuperAgentWork(pair.from().id(), pair.to().id());

            boolean disabled = false;
            if (disableFromUser) {
                users.update(pair.from().id(), new SaveUserRequest(null, null, null, false, null, null, null, null));
                disabled = true;
            }

            HandoverResultDto.Counts counts = new HandoverResultDto.Counts(agentsMoved, taskResult.stopped(),
                    taskResult.reassigned(), automationsMoved, filesMoved, foldersMoved, projectsMoved, skillsMoved,
                    workMoved);
            HandoverResultDto result = new HandoverResultDto(running.id(), HandoverRecord.COMPLETED,
                    pair.from().username(), pair.to().username(), disabled, counts, warnings, Instant.now());
            records.save(running.completed(toJson(result)));
            log.info("handover {} completed: {} → {} by {}", running.id(), pair.from().username(),
                    pair.to().username(), byUserId);
            return result;
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            records.save(running.failed(toJson(err)));
            throw e;
        }
    }

    private int transferOpenSuperAgentWork(String fromUserId, String toUserId) {
        if (superWorks == null) {
            return 0;
        }
        int n = 0;
        for (SuperAgentWork w : superWorks.list(fromUserId)) {
            if (w.open()) {
                superWorks.save(w.withOwner(toUserId));
                n++;
            }
        }
        return n;
    }

    private Pair resolvePair(String fromUsername, String toUsername) {
        if (fromUsername == null || fromUsername.isBlank() || toUsername == null || toUsername.isBlank()) {
            throw new IllegalArgumentException("fromUsername and toUsername are required");
        }
        if (fromUsername.strip().equalsIgnoreCase(toUsername.strip())) {
            throw new IllegalArgumentException("离开人和继任者不能是同一个人");
        }
        User from = people.resolve(fromUsername.strip())
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + fromUsername));
        User to = people.resolve(toUsername.strip())
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + toUsername));
        if (from.id().equals(to.id())) {
            throw new IllegalArgumentException("离开人和继任者不能是同一个人");
        }
        return new Pair(from, to);
    }

    private Snapshot snapshot(String fromUserId) {
        List<Agent> ownedAgents = agents.owned(fromUserId);
        int supers = (int) ownedAgents.stream().filter(Agent::isSuper).count();

        int running = 0, pending = 0, waiting = 0, needs = 0, terminal = 0;
        for (Task t : taskStore.listTasks()) {
            if (t.status() == TaskStatus.DELETED || !fromUserId.equals(t.createdBy())) {
                continue;
            }
            switch (t.status()) {
                case RUNNING -> running++;
                case PENDING -> pending++;
                case WAITING -> waiting++;
                case NEEDS_CONFIRMATION -> needs++;
                default -> terminal++;
            }
        }

        int automationCount = (int) automationStore.list().stream()
                .filter(a -> fromUserId.equals(a.owner())).count();
        int fileCount = fileStore.listFor(fromUserId, List.of()).size();
        int folderCount = folderStore.list(fromUserId).size();
        int projectCount = (int) projectStore.list().stream()
                .filter(p -> fromUserId.equals(p.owner())).count();
        int personalSkills = skills.countPersonal(fromUserId);
        int openWork = superWorks == null ? 0
                : (int) superWorks.list(fromUserId).stream().filter(SuperAgentWork::open).count();

        return new Snapshot(ownedAgents.size(), supers,
                new TaskCounts(running, pending, waiting, needs, terminal),
                automationCount, fileCount, folderCount, projectCount, personalSkills, openWork);
    }

    private List<String> warnings(String fromUserId, Snapshot snap) {
        List<String> out = new ArrayList<>();
        if (snap.tasks.active() > 0) {
            out.add(snap.tasks.active() + " 个进行中的任务将先停止再移交");
        }
        if (snap.superAgents > 0) {
            out.add("离开人的 SuperAgent 将降级为「工作档案」标准 Agent 后移交；继任者保留自己的 SuperAgent");
        }
        out.add("连接器凭证与飞书/Inbox 绑定不移交，继任者需自行重绑");
        out.add("历史用量记录仍归属离开人，不改写");
        return List.copyOf(out);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return Objects.toString(value);
        }
    }

    private record Pair(User from, User to) {
    }

    private record Snapshot(int agents, int superAgents, TaskCounts tasks, int automations, int files, int folders,
                            int projects, int personalSkills, int openWork) {
    }
}
