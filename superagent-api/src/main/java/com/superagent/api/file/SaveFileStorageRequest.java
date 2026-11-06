package com.superagent.api.file;

import java.util.Map;

/** Switch to (or re-configure) a kind of store. A blank secret keeps the one already saved. */
public record SaveFileStorageRequest(String type, Map<String, String> values) {
}
