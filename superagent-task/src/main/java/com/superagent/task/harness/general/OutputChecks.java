package com.superagent.task.harness.general;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.task.harness.HarnessHost.Materials;
import com.superagent.task.harness.HarnessHost.Produced;
import com.superagent.task.harness.HarnessState.Deliverable;
import com.superagent.task.harness.HarnessState.PlatformCheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the platform checks of a general task's work by itself, without a model: the files it says it handed over
 * exist and aren't empty, they are of the kind asked for, the materials it was given were read, and files of a fixed
 * format parse. A task with no files to hand over is checked on its final reply.
 */
public final class OutputChecks {

    static final String FILES = "交付的文件存在且不是空文件", TYPES = "文件类型和交付物要求一致",
            MATERIALS = "资料都读到了", FORMATS = "固定格式的文件能被解析", REPLY = "最终回复不为空";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXT = Pattern.compile("[a-z0-9]{1,5}");
    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3}).*");
    /** Extensions that name the same kind of file. */
    private static final Map<String, String> SAME = Map.of("markdown", "md", "jpeg", "jpg", "htm", "html", "yml", "yaml",
            "doc", "docx", "ppt", "pptx", "xls", "xlsx", "text", "txt");

    private OutputChecks() {
    }

    public static List<PlatformCheck> run(List<Deliverable> deliverables, List<Produced> produced, Materials materials,
                                          String reply) {
        List<PlatformCheck> out = new ArrayList<>();
        List<String> wanted = deliverables.stream().map(Deliverable::type).filter(OutputChecks::isFileType)
                .map(OutputChecks::canonical).distinct().toList();
        if (produced.isEmpty() && wanted.isEmpty()) {
            out.add(new PlatformCheck(REPLY, reply != null && !reply.isBlank(),
                    reply == null || reply.isBlank() ? "没有文件要交付，最终回复却是空的" : "没有文件要交付，检查最终回复"));
        } else {
            out.add(files(produced, wanted));
        }
        if (!wanted.isEmpty() && !produced.isEmpty()) {
            out.add(types(produced, wanted));
        }
        if (!materials.given().isEmpty() || !materials.unreadable().isEmpty()) {
            out.add(materials(materials));
        }
        List<String> problems = new ArrayList<>();
        int parsed = 0;
        for (Produced p : produced) {
            String ext = ext(p.name());
            if (p.text() == null || !List.of("srt", "vtt", "json", "csv", "md").contains(ext)) {
                continue;
            }
            parsed++;
            String why = parse(ext, p.text());
            if (why != null) {
                problems.add(p.name() + "：" + why);
            }
        }
        if (parsed > 0) {
            out.add(new PlatformCheck(FORMATS, problems.isEmpty(),
                    problems.isEmpty() ? parsed + " 个文件格式正确" : String.join("；", problems)));
        }
        return out;
    }

    private static PlatformCheck files(List<Produced> produced, List<String> wanted) {
        if (produced.isEmpty()) {
            return new PlatformCheck(FILES, false, "没有交付任何文件（要求：" + String.join("、", wanted) + "）");
        }
        List<String> empty = produced.stream()
                .filter(p -> p.size() <= 0 || p.text() != null && p.text().isBlank()).map(Produced::name).toList();
        return new PlatformCheck(FILES, empty.isEmpty(), empty.isEmpty()
                ? produced.size() + " 个文件，都不是空的" : "空文件：" + String.join("、", empty));
    }

    private static PlatformCheck types(List<Produced> produced, List<String> wanted) {
        List<String> have = produced.stream().map(p -> canonical(ext(p.name()))).distinct().toList();
        List<String> missing = wanted.stream().filter(w -> !have.contains(w)).toList();
        return new PlatformCheck(TYPES, missing.isEmpty(), missing.isEmpty()
                ? "要求的 " + String.join("、", wanted) + " 都有"
                : "要求 " + String.join("、", missing) + "，产出里没有（有：" + String.join("、",
                produced.stream().map(Produced::name).toList()) + "）");
    }

    private static PlatformCheck materials(Materials m) {
        List<String> problems = new ArrayList<>();
        if (!m.unfetched().isEmpty()) {
            problems.add("没有取用的资料：" + String.join("、", m.unfetched()));
        }
        if (!m.unreadable().isEmpty()) {
            problems.add("读不到的链接：" + String.join("、", m.unreadable()));
        }
        return new PlatformCheck(MATERIALS, problems.isEmpty(), problems.isEmpty()
                ? m.given().size() + " 份资料都取用了" : String.join("；", problems));
    }

    /** Why {@code text} isn't a valid {@code ext} file; null when it is. */
    static String parse(String ext, String text) {
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        return switch (ext) {
            case "srt" -> srt(t);
            case "vtt" -> t.stripLeading().startsWith("WEBVTT") && t.contains("-->") ? null : "不是有效的 WebVTT（缺 WEBVTT 头或时间轴）";
            case "json" -> json(t);
            case "csv" -> csv(t);
            default -> t.isBlank() ? "内容是空的" : null;
        };
    }

    private static String srt(String text) {
        String[] blocks = text.replace("\r\n", "\n").strip().split("\n\\s*\n");
        long last = -1;
        int n = 0;
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            if (lines.length < 2 || !lines[0].strip().matches("\\d+")) {
                return "第 " + (n + 1) + " 条缺序号";
            }
            Matcher m = SRT_TIME.matcher(lines[1].strip());
            if (!m.matches()) {
                return "第 " + (n + 1) + " 条时间轴格式不对：" + lines[1].strip();
            }
            long start = millis(m, 1), end = millis(m, 5);
            if (end < start || start < last) {
                return "第 " + (n + 1) + " 条时间轴倒退";
            }
            last = start;
            n++;
        }
        return n == 0 ? "没有字幕条目" : null;
    }

    private static long millis(Matcher m, int from) {
        return ((Long.parseLong(m.group(from)) * 60 + Long.parseLong(m.group(from + 1))) * 60
                + Long.parseLong(m.group(from + 2))) * 1000 + Long.parseLong(m.group(from + 3));
    }

    private static String json(String text) {
        try {
            return JSON.readTree(text) == null || text.isBlank() ? "内容是空的" : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "不是有效的 JSON：" + e.getOriginalMessage();
        } catch (IOException e) {
            return "不是有效的 JSON：" + e.getMessage();
        }
    }

    private static String csv(String text) {
        List<String> rows = text.replace("\r\n", "\n").lines().filter(l -> !l.isBlank()).toList();
        if (rows.isEmpty()) {
            return "内容是空的";
        }
        int columns = columns(rows.get(0));
        for (int i = 1; i < rows.size(); i++) {
            int c = columns(rows.get(i));
            if (c != columns) {
                return "第 " + (i + 1) + " 行有 " + c + " 列，表头有 " + columns + " 列";
            }
        }
        return null;
    }

    /** Fields in one CSV line, quotes respected (a quoted field spanning lines isn't supported). */
    private static int columns(String line) {
        int n = 1;
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                n++;
            }
        }
        return n;
    }

    static boolean isFileType(String type) {
        return type != null && !type.equals("reply") && EXT.matcher(type).matches();
    }

    static String ext(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : canonical(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String canonical(String ext) {
        return SAME.getOrDefault(ext, ext);
    }
}
