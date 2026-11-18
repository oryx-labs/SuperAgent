package com.superagent.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.RunCredential;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** OpenAI's {@code /audio/transcriptions} (whisper-1, segment timings), at the pool entry's endpoint. */
final class OpenAiTranscriptions implements Transcriber.Api {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Never follow a redirect: it would carry the key to wherever the response points.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Transcriber.Piece transcribe(RunCredential credential, Path file, String language) throws Transcriber.ApiException {
        String base = credential.baseUrl() == null ? "https://api.openai.com/v1" : credential.baseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String boundary = "----sa" + UUID.randomUUID().toString().replace("-", "");
        List<byte[]> parts = new ArrayList<>();
        field(parts, boundary, "model", Transcriber.MODEL);
        field(parts, boundary, "response_format", "verbose_json");
        field(parts, boundary, "timestamp_granularities[]", "segment");
        if (language != null && !language.isBlank()) {
            field(parts, boundary, "language", language.strip());
        }
        try {
            parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + file.getFileName().toString().replace("\"", "") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            parts.add(Files.readAllBytes(file));
            parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions"))
                    .timeout(Duration.ofMinutes(15))
                    .header("Authorization", "Bearer " + credential.apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new Transcriber.ApiException("HTTP " + response.statusCode() + ": " + clip(response.body()));
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new Transcriber.ApiException("connection error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Transcriber.ApiException("interrupted");
        }
    }

    static Transcriber.Piece parse(String body) throws Transcriber.ApiException {
        try {
            JsonNode root = JSON.readTree(body);
            List<Transcriber.Segment> segments = new ArrayList<>();
            root.path("segments").forEach(s -> segments.add(new Transcriber.Segment(s.path("start").asDouble(),
                    s.path("end").asDouble(), s.path("text").asText(""))));
            double seconds = root.path("duration").asDouble(segments.isEmpty() ? 0 : segments.get(segments.size() - 1).end());
            if (segments.isEmpty() && !root.path("text").asText("").isBlank()) {
                segments.add(new Transcriber.Segment(0, seconds, root.path("text").asText()));
            }
            return new Transcriber.Piece(seconds, segments);
        } catch (IOException e) {
            throw new Transcriber.ApiException("unreadable response: " + clip(body));
        }
    }

    private static void field(List<byte[]> parts, String boundary, String name, String value) {
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }
}
