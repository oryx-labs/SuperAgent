package com.superagent.api.file;

/** A folder in the caller's Files; {@code parentId} null is the top level, {@code path} like 项目/Q4. */
public record FileFolderDto(String id, String name, String parentId, String path) {
}
