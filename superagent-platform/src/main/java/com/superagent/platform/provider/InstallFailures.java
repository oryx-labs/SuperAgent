package com.superagent.platform.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a failed install command's output into one line an admin can act on. npm's own output is a stack trace
 * and a log-file path; the common causes (network, permissions, wrong name, Node version, proxy certificate, disk)
 * get a plain explanation, anything else its first few error lines. The full output stays in the install log.
 */
final class InstallFailures {

    private static final Pattern CODE = Pattern.compile("npm (?:error|ERR!) code (\\S+)");
    private static final Pattern REQUEST = Pattern.compile("request to (\\S+) failed");
    private static final Pattern URL = Pattern.compile("https?://[^\\s'\"]+");

    private InstallFailures() {
    }

    static String summarize(String output, int exitCode) {
        Matcher code = CODE.matcher(output);
        String c = code.find() ? code.group(1) : null;
        String hint = c == null ? null : hint(c, output);
        if (hint != null) {
            return hint + "（npm error code " + c + "）";
        }
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            String t = line.replaceFirst("^npm (?:error|ERR!)\\s*", "").strip();
            boolean errorLine = line.startsWith("npm error") || line.startsWith("npm ERR!") || line.contains("Error");
            if (errorLine && !t.isEmpty() && !t.startsWith("at ") && !t.startsWith("A complete log")) {
                lines.add(t);
            }
            if (lines.size() == 3) {
                break;
            }
        }
        return lines.isEmpty() ? "命令失败（退出码 " + exitCode + "），详情见安装日志" : String.join("；", lines);
    }

    private static String hint(String code, String output) {
        return switch (code) {
            case "ECONNREFUSED", "ETIMEDOUT", "ENOTFOUND", "ECONNRESET", "EAI_AGAIN", "ENETUNREACH", "EHOSTUNREACH" ->
                    "连不上 npm 源" + where(output) + "：请检查服务器网络、代理或 npm 镜像设置";
            case "EACCES", "EPERM" -> "没有写入 npm 全局目录的权限" + where(output);
            case "E404" -> "npm 源上找不到这个包" + where(output);
            case "EBADENGINE", "ENOTSUP" -> "Node.js 版本不满足这个包的要求，请先在上方安装新版 Node.js";
            case "ENOSPC" -> "磁盘空间不足";
            case "SELF_SIGNED_CERT_IN_CHAIN", "UNABLE_TO_GET_ISSUER_CERT_LOCALLY", "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
                 "CERT_HAS_EXPIRED", "DEPTH_ZERO_SELF_SIGNED_CERT" ->
                    "HTTPS 证书校验失败（常见于公司代理）：请为 npm 配置代理的根证书（cafile）";
            case "E401", "E403" -> "npm 源拒绝访问" + where(output) + "：请检查 npm 源的登录凭证";
            default -> null;
        };
    }

    /** The URL npm was talking to, when it says. */
    private static String where(String output) {
        Matcher m = REQUEST.matcher(output);
        if (m.find()) {
            return "（" + m.group(1) + "）";
        }
        for (String line : output.split("\n")) {
            if (line.contains("npm error") && (line.contains("path") || line.contains("404"))) {
                Matcher u = URL.matcher(line);
                if (u.find()) {
                    return "（" + u.group() + "）";
                }
                String path = line.replaceFirst(".*\\bpath\\s+", "").strip();
                if (!path.equals(line.strip())) {
                    return "（" + path + "）";
                }
            }
        }
        return "";
    }
}
