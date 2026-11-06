package com.superagent.api.provider;

/**
 * The Node.js the npm-based CLIs install and run on. {@code source} is "private" (installed by SuperAgent
 * under its data dir), "system", or null when there's none; {@code installing} while a private install runs;
 * {@code error} the last install failure.
 */
public record NodeRuntimeDto(String version, String source, String path, boolean installing, String error) {
}
