package com.superagent.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.usage.UsageContext;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/** Lets an agent turn an audio or video file in its workspace into subtitles or a transcript. */
@Component
public class TranscribeMcpTools implements McpToolProvider {

    private static final int PREVIEW_LINES = 24;

    private final Transcriber transcriber;
    private final McpGrants grants;

    public TranscribeMcpTools(Transcriber transcriber, McpGrants grants) {
        this.transcriber = transcriber;
        this.grants = grants;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(tool("audio_transcribe",
                "把工作区里的音频或视频转成文字：带时间码的 SRT 字幕，或纯文本逐字稿。要做字幕、会议纪要、听录音内容时用它，"
                        + "不要自己找或安装语音识别工具。Files 里的文件先用 file_get 取到工作区。长音频要几分钟，按时长计费（约每分钟 $0.006）。"
                        + "结果写在原文件旁边，返回路径和开头几段；转写是机器识别，专有名词可能有错，要结合资料校对后再交付。",
                props(req("file", "string", "工作区里的音频或视频路径（相对工作区，如 uploads/第02课.mp3）"),
                        opt("format", "string", "srt = 带时间码的字幕（默认）；text = 纯文本逐字稿"),
                        opt("language", "string", "音频的语言，ISO 639-1 代码，如 zh、en；不填自动识别（知道的话填上更准）")),
                "file"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        if (!"audio_transcribe".equals(name)) {
            throw new McpToolException("Unknown tool: " + name);
        }
        String owner = requireOwner(grant, "转写");
        boolean srt = !"text".equalsIgnoreCase(String.valueOf(strOrNull(args, "format")).strip());
        try {
            Path source = grants.workspaceFile(grant, require(args, "file"));
            Transcriber.Result r = transcriber.transcribe(source, language(strOrNull(args, "language")),
                    new UsageContext(owner, UsageContext.SOURCE_TRANSCRIBE, grant.agentId(), grant.taskId(),
                            grant.sessionId(), Transcriber.PROVIDER));
            String content = srt ? r.srt() : r.text();
            if (content.isBlank()) {
                return McpToolResult.ok("转写完成，但没有识别出任何语音（时长 " + minutes(r.seconds()) + "）。可能是静音、纯音乐或语言没认出来。");
            }
            String file = source.getFileName().toString();
            int dot = file.lastIndexOf('.');
            Path out = source.resolveSibling((dot < 0 ? file : file.substring(0, dot)) + (srt ? ".srt" : ".txt"));
            Files.writeString(out, content, StandardCharsets.UTF_8);
            Path workspace = grant.workspace().toRealPath();
            List<String> lines = content.lines().limit(PREVIEW_LINES).toList();
            return McpToolResult.ok("已转写 " + minutes(r.seconds()) + "（" + r.segments().size() + " 段），写到了工作区 "
                    + workspace.relativize(out.toRealPath()) + String.format(Locale.ROOT, "，费用约 $%.3f。", r.usd())
                    + "完整内容请读这个文件；要交付给用户用 file_save。开头几段：\n" + String.join("\n", lines));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new McpToolException(e.getMessage());
        } catch (IOException e) {
            throw new McpToolException("转写时读写文件出错：" + e.getMessage());
        }
    }

    private static String language(String given) {
        if (given == null || given.isBlank()) {
            return null;
        }
        String l = given.strip().toLowerCase(Locale.ROOT);
        return switch (l) {
            case "中文", "chinese", "zh-cn", "zh-hans", "cn" -> "zh";
            case "英文", "英语", "english", "en-us" -> "en";
            default -> l.matches("[a-z]{2}") ? l : null; // anything else: let it detect
        };
    }

    private static String minutes(double seconds) {
        long s = Math.round(seconds);
        return s >= 60 ? s / 60 + " 分 " + s % 60 + " 秒" : s + " 秒";
    }
}
