package com.superagent.core.media;

/** A speech-to-text model that can run now: on engine {@code engine}, with timings ({@code timed}) or text only. */
public record SpeechModel(String engine, String model, boolean timed) {
}
