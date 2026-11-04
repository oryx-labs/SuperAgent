package com.superagent.api.task;

import com.superagent.api.provider.ProviderModelDto;

import java.util.List;

/**
 * An agent CLI a task can be created with, and the models offered for it. {@code models}
 * is the list fetched on the Models admin page; it is empty when that CLI hasn't been
 * added there (the task then runs on the CLI's default model).
 */
public record TaskAgentOptionDto(String key, String name, List<ProviderModelDto> models) {
}
