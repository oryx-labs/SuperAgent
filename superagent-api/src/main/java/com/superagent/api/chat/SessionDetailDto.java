package com.superagent.api.chat;

import java.util.List;

/**
 * A session together with its message history, returned when opening a session.
 */
public record SessionDetailDto(SessionDto session, List<MessageDto> messages) {
}
