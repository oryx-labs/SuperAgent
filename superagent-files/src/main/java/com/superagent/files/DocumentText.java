package com.superagent.files;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The text of a reference document page by page: a deck's slides, a PDF's pages; plain text is one page. */
public final class DocumentText {

    private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");
    private static final Pattern RUN = Pattern.compile("<a:t>([^<]*)</a:t>|</a:p>");
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    private DocumentText() {
    }

    /** One page of a document; {@code number} from 1. */
    public record Page(int number, String text) {
    }

    /** Whether {@link #pages} can read it. */
    public static boolean readable(Path file) {
        return List.of("pptx", "pdf", "txt", "md").contains(extension(file));
    }

    /** Empty for a kind it can't read. */
    public static List<Page> pages(Path file) throws IOException {
        return switch (extension(file)) {
            case "pptx" -> slides(file);
            case "pdf" -> pdf(file);
            case "txt", "md" -> Files.size(file) > MAX_TEXT_BYTES ? List.of()
                    : List.of(new Page(1, Files.readString(file, StandardCharsets.UTF_8)));
            default -> List.of();
        };
    }

    private static List<Page> slides(Path file) throws IOException {
        TreeMap<Integer, String> slides = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                Matcher m = SLIDE.matcher(e.getName());
                if (m.matches()) {
                    slides.put(Integer.parseInt(m.group(1)), text(zip));
                }
            }
        }
        List<Page> out = new ArrayList<>();
        slides.forEach((n, t) -> out.add(new Page(n, t)));
        return out;
    }

    private static String text(InputStream in) throws IOException {
        String xml = new String(in.readNBytes((int) MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        Matcher m = RUN.matcher(xml);
        while (m.find()) {
            if (m.group(1) == null) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            } else {
                sb.append(unescape(m.group(1)));
            }
        }
        return sb.toString().strip();
    }

    private static List<Page> pdf(Path file) throws IOException {
        List<Page> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                out.add(new Page(p, stripper.getText(doc).strip()));
            }
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
