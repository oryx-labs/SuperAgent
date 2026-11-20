package com.superagent.task.harness.coding;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drops a {@code mvn -pl X -am <goal>} that another command's {@code -am} chain already runs (web's chain holds
 * runtime, so testing both runs runtime's tests twice). Anything not in that exact shape is left as written.
 */
final class VerifyCommands {

    private VerifyCommands() {
    }

    private static final Pattern MVN = Pattern.compile(
            "^mvn((?:\\s+-[oq])*)\\s+-pl\\s+([\\w.-]+)\\s+-am((?:\\s+-[oq])*)\\s+(test|verify|install)$");

    record Merged(List<String> commands, List<String> dropped) {
    }

    /** {@code root}: the repository whose module poms give the dependencies. */
    static Merged merge(List<String> commands, Path root) {
        Map<String, Set<String>> chains = null;
        Map<Integer, String[]> parsed = new LinkedHashMap<>(); // index -> {module, flags, goal}
        for (int i = 0; i < commands.size(); i++) {
            Matcher m = MVN.matcher(commands.get(i).strip());
            if (m.matches()) {
                parsed.put(i, new String[]{m.group(2), (m.group(1) + m.group(3)).replaceAll("\\s+", " ").strip(), m.group(4)});
            }
        }
        if (parsed.size() < 2) {
            return new Merged(commands, List.of());
        }
        try {
            chains = chains(root);
        } catch (IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            return new Merged(commands, List.of()); // can't read the poms: change nothing
        }
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            String[] a = parsed.get(i);
            String by = null;
            if (a != null && chains.containsKey(a[0])) {
                for (Map.Entry<Integer, String[]> e : parsed.entrySet()) {
                    String[] b = e.getValue();
                    boolean same = e.getKey() != i && b[2].equals(a[2]) && b[1].equals(a[1]) && chains.containsKey(b[0])
                            && chains.get(b[0]).contains(a[0]);
                    // two commands covering each other (same module twice): keep the first
                    boolean twin = same && b[0].equals(a[0]) && e.getKey() > i;
                    if (same && !twin) {
                        by = commands.get(e.getKey());
                        break;
                    }
                }
            }
            if (by == null) {
                kept.add(commands.get(i));
            } else {
                dropped.add("`" + commands.get(i) + "`（已被 `" + by + "` 的 -am 依赖链覆盖）");
            }
        }
        return new Merged(kept, dropped);
    }

    /** Each module with every module it needs, itself included. */
    private static Map<String, Set<String>> chains(Path root)
            throws IOException, javax.xml.parsers.ParserConfigurationException, org.xml.sax.SAXException {
        Map<String, Set<String>> direct = new HashMap<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                Path pom = d.resolve("pom.xml");
                if (Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
                    Document doc = builder().parse(pom.toFile());
                    Element project = doc.getDocumentElement();
                    String id = child(project, "artifactId");
                    Set<String> deps = new HashSet<>();
                    NodeList lists = project.getChildNodes();
                    for (int i = 0; i < lists.getLength(); i++) {
                        if (lists.item(i) instanceof Element e && e.getTagName().equals("dependencies")) {
                            NodeList ds = e.getElementsByTagName("dependency");
                            for (int j = 0; j < ds.getLength(); j++) {
                                String dep = child((Element) ds.item(j), "artifactId");
                                if (dep != null) {
                                    deps.add(dep);
                                }
                            }
                        }
                    }
                    if (id != null) {
                        direct.put(id, deps);
                    }
                }
            }
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (String m : direct.keySet()) {
            Set<String> seen = new HashSet<>(Set.of(m));
            List<String> todo = new ArrayList<>(List.of(m));
            while (!todo.isEmpty()) {
                for (String dep : direct.getOrDefault(todo.remove(todo.size() - 1), Set.of())) {
                    if (direct.containsKey(dep) && seen.add(dep)) {
                        todo.add(dep);
                    }
                }
            }
            out.put(m, seen);
        }
        return out;
    }

    /** The poms come from the repository being worked on, so no DOCTYPE and no external entities. */
    private static DocumentBuilder builder() throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f.newDocumentBuilder();
    }

    private static String child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getTagName().equals(tag)) {
                return c.getTextContent().strip();
            }
        }
        return null;
    }
}
