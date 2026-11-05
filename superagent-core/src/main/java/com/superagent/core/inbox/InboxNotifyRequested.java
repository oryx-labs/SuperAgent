package com.superagent.core.inbox;

/** SuperAgent wants a short line pushed to its owner where they'll see it now (their Feishu bot DM, ...). */
public record InboxNotifyRequested(String owner, String text) {
}
