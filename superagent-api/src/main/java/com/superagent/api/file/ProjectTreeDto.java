package com.superagent.api.file;

import java.util.List;
import java.util.Map;

/**
 * One project's shared folder tree as a member sees it: its folders and where each mounted file sits
 * ({@code placements}: fileId → folderId, "" = the project's root). {@code writable} is false once archived.
 */
public record ProjectTreeDto(String projectId, String name, boolean archived, boolean writable, List<FileFolderDto> folders,
                             Map<String, String> placements) {
}
