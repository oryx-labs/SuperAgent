package com.superagent.core.link;

import com.superagent.core.error.ConflictException;

import java.util.List;

/** A task was not created: some of the links in its materials can't be read. The message lists each with its fix. */
public class UnreadableMaterialsException extends ConflictException {

    private final List<UnreadableLinkException> unreadable;

    public UnreadableMaterialsException(List<UnreadableLinkException> unreadable) {
        super(describe(unreadable));
        this.unreadable = List.copyOf(unreadable);
    }

    public List<UnreadableLinkException> unreadable() {
        return unreadable;
    }

    private static String describe(List<UnreadableLinkException> list) {
        StringBuilder sb = new StringBuilder("任务没有创建：有 " + list.size() + " 份资料读不到，先解决再建任务。\n");
        for (UnreadableLinkException e : list) {
            sb.append("- ").append(e.url()).append("：").append(e.getMessage());
            if (e.hint() != null && !e.hint().isBlank()) {
                sb.append("（").append(e.hint()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }
}
