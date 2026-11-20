package com.superagent.api.provider;

/**
 * The server's ffmpeg, which subtitle and transcription work uses to convert and cut audio. {@code version} null when
 * it's there but didn't say; {@code installing} while an install runs; {@code error} the last install failure;
 * {@code step} what a running install is doing; {@code hasLog} whether there's a log of the last install to show.
 */
public record FfmpegDto(boolean installed, String version, String path, boolean installing, String error, String step,
                        boolean hasLog) {
}
