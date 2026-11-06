package com.superagent.core.file;

import java.util.Locale;

/** What a work file is, for filtering and picking a preview. Decided from the file name. */
public enum WorkFileKind {
    DOCUMENT, IMAGE, VIDEO, OTHER;

    public static WorkFileKind of(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "md", "markdown", "txt", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv", "json", "html",
                 "htm" -> DOCUMENT;
            case "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> IMAGE;
            case "mp4", "webm", "mov", "m4v" -> VIDEO;
            default -> OTHER;
        };
    }

    public static WorkFileKind parse(String value) {
        try {
            return value == null ? OTHER : valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
