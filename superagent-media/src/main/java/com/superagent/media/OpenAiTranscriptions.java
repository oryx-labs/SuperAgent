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

/**
 * OpenAI's {@code /audio/transcriptions} at the pool entry's endpoint. whisper-1 answers in {@code verbose_json} with
 * segment timings; the gpt-4o transcribe models only take {@code json} (text, no timings), and take a prompt, as
 * whisper-1 does.
 */
final class OpenAiTranscriptions implements Transcriber.Api {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Never follow a redirect: it would carry the key to wherever the response points.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Transcriber.Piece transcribe(RunCredential credential, Path file, Transcriber.Request request)
            throws Transcriber.ApiException {
        try {
            return send(credential, file.getFileName().toString(), Files.readAllBytes(file), request, Duration.ofMinutes(15));
        } catch (IOException e) {
            throw new Transcriber.ApiException("connection error: " + e.getMessage());
        }
    }

    /** Sends a second of silence: whether {@code model} answers on this key at all. */
    void probe(RunCredential credential, String model) throws Transcriber.ApiException {
        send(credential, "probe.wav", silence(), new Transcriber.Request(model, null, null), Duration.ofSeconds(60));
    }

    private Transcriber.Piece send(RunCredential credential, String name, byte[] audio, Transcriber.Request request,
                                   Duration timeout) throws Transcriber.ApiException {
        String base = credential.baseUrl() == null ? "https://api.openai.com/v1" : credential.baseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        boolean timed = Transcriber.timed(request.model());
        String boundary = "----sa" + UUID.randomUUID().toString().replace("-", "");
        List<byte[]> parts = new ArrayList<>();
        field(parts, boundary, "model", request.model());
        field(parts, boundary, "response_format", timed ? "verbose_json" : "json");
        if (timed) {
            field(parts, boundary, "timestamp_granularities[]", "segment");
        }
        if (request.language() != null && !request.language().isBlank()) {
            field(parts, boundary, "language", request.language().strip());
        }
        if (request.prompt() != null && !request.prompt().isBlank()) {
            field(parts, boundary, "prompt", request.prompt().strip());
        }
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + name.replace("\"", "") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        parts.add(audio);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(base + "/audio/transcriptions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + credential.apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(parts)).build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    /**
     * Either shape: {@code segments} with timings (verbose_json), or only {@code text} (json), when the length comes
     * from {@code usage.seconds} if the API gives it (else unknown: 0).
     */
    static Transcriber.Piece parse(String body) throws Transcriber.ApiException {
        try {
            JsonNode root = JSON.readTree(body);
            List<Transcriber.Segment> segments = new ArrayList<>();
            root.path("segments").forEach(s -> segments.add(new Transcriber.Segment(s.path("start").asDouble(),
                    s.path("end").asDouble(), s.path("text").asText(""))));
            double seconds = root.path("duration").asDouble(root.path("usage").path("seconds")
                    .asDouble(segments.isEmpty() ? 0 : segments.get(segments.size() - 1).end()));
            if (segments.isEmpty() && !root.path("text").asText("").isBlank()) {
                segments.add(new Transcriber.Segment(0, seconds, root.path("text").asText()));
            }
            return new Transcriber.Piece(seconds, segments);
        } catch (IOException e) {
            throw new Transcriber.ApiException("unreadable response: " + clip(body));
        }
    }

    /** One second of 16 kHz mono 16-bit silence as WAV. */
    static byte[] silence() {
        int samples = 16000, data = samples * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        return b.array();
    }

    private static void field(List<byte[]> parts, String boundary, String name, String value) {
        parts.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static String clip(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }
}
