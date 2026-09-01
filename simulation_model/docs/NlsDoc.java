/*
 * NlsDoc - a small doxygen-style documentation generator for NetLogo sources.
 *
 * It reads every .nls / .nlogo / .nlogox file under a root directory, extracts the
 * documentation comments described in COMMENT_CONVENTION.md, and writes a static
 * HTML site (overview, one page per file, symbol index, coverage report).
 *
 * There is no build step and there are no dependencies: Java 17 can run a single
 * source file directly.
 *
 *   java NlsDoc.java --root .. --out api
 *
 * See nlsdoc.ps1 / nlsdoc.sh for wrappers that locate a JVM automatically.
 */

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NlsDoc {

    public static void main(String[] args) throws IOException {
        Options opt = Options.parse(args);
        if (opt.help) {
            System.out.print(Options.USAGE);
            return;
        }
        if (!Files.isDirectory(opt.root)) {
            System.err.println("nlsdoc: not a directory: " + opt.root);
            System.exit(2);
        }

        Model model = new Parser().parseTree(opt.root, opt.out);
        model.index();
        model.resolveCallGraph();

        if (opt.check) {
            System.exit(Report.check(model, opt));
            return;
        }

        new HtmlWriter(model, opt).write();
        Report.summary(model, opt);
    }
}

/* ------------------------------------------------------------------ options */

final class Options {

    static final String USAGE = String.join("\n",
        "nlsdoc - documentation generator for NetLogo sources",
        "",
        "  java NlsDoc.java [options]",
        "",
        "  --root <dir>          directory to scan (default: the model directory)",
        "  --out <dir>           output directory (default: <root>/docs/api)",
        "  --title <text>        title shown in the generated site",
        "  --check               report documentation coverage, generate nothing",
        "  --min-coverage <pct>  with --check, exit 1 below this percentage",
        "  --quiet               only print errors",
        "  --help                show this message",
        "");

    Path root;
    Path out;
    String title = "ASSOEC model reference";
    boolean check;
    boolean quiet;
    boolean help;
    int minCoverage;

    static Options parse(String[] args) {
        Options o = new Options();
        Path root = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--root": root = Paths.get(need(args, ++i, "--root")); break;
                case "--out": out = Paths.get(need(args, ++i, "--out")); break;
                case "--title": o.title = need(args, ++i, "--title"); break;
                case "--min-coverage": o.minCoverage = Integer.parseInt(need(args, ++i, "--min-coverage")); break;
                case "--check": o.check = true; break;
                case "--quiet": o.quiet = true; break;
                case "--help": case "-h": o.help = true; break;
                default:
                    System.err.println("nlsdoc: unknown option: " + a);
                    System.err.print(USAGE);
                    System.exit(2);
            }
        }
        o.root = (root != null ? root : guessRoot()).toAbsolutePath().normalize();
        o.out = (out != null ? out : o.root.resolve("docs").resolve("api")).toAbsolutePath().normalize();
        return o;
    }

    private static String need(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println("nlsdoc: " + flag + " needs a value");
            System.exit(2);
        }
        return args[i];
    }

    /** Work from the repository root, the model directory, or this tool's own directory. */
    private static Path guessRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("simulation_model"))) {
            return cwd.resolve("simulation_model");
        }
        Path parent = cwd.getParent();
        if ("docs".equals(String.valueOf(cwd.getFileName())) && parent != null) {
            return parent;
        }
        return cwd;
    }
}

/* -------------------------------------------------------------------- model */

/** One `@tag argument text` entry of a documentation comment. */
final class Tag {
    final String name;
    String arg = "";
    String text = "";

    Tag(String name) {
        this.name = name;
    }
}

/** A parsed documentation comment: a summary, free prose, and tags. */
final class Doc {
    String summary = "";
    String body = "";
    final List<Tag> tags = new ArrayList<>();

    boolean isEmpty() {
        return summary.isBlank() && body.isBlank() && tags.isEmpty();
    }

    boolean has(String tag) {
        return !tags(tag).isEmpty();
    }

    List<Tag> tags(String name) {
        List<Tag> found = new ArrayList<>();
        for (Tag t : tags) {
            if (t.name.equals(name)) {
                found.add(t);
            }
        }
        return found;
    }

    String first(String name) {
        List<Tag> found = tags(name);
        return found.isEmpty() ? "" : found.get(0).text.trim();
    }

    /**
     * Turn the raw text of a comment block into a Doc.
     *
     * Everything before the first `@tag` line is prose; its first paragraph is the
     * summary. A tag runs until the next `@tag` line or the end of the block.
     */
    static Doc parse(String raw) {
        Doc doc = new Doc();
        if (raw == null || raw.isBlank()) {
            return doc;
        }
        List<String> prose = new ArrayList<>();
        Tag current = null;
        for (String line : raw.split("\n", -1)) {
            Matcher m = Parser.TAG.matcher(line);
            if (m.find()) {
                current = new Tag(m.group(1).toLowerCase(Locale.ROOT));
                doc.tags.add(current);
                current.text = m.group(2) == null ? "" : m.group(2).trim();
            } else if (current != null) {
                current.text = current.text.isEmpty() ? line.trim() : current.text + "\n" + line;
            } else {
                prose.add(line);
            }
        }
        // Tags whose first word names something: @param x ..., @group X, @file f.nls
        for (Tag t : doc.tags) {
            if (t.name.equals("param") || t.name.equals("group") || t.name.equals("file")
                    || t.name.equals("module") || t.name.equals("context")) {
                Matcher m = Parser.FIRST_WORD.matcher(t.text);
                if (m.find()) {
                    t.arg = m.group(1);
                    t.text = t.text.substring(m.end()).trim();
                }
            }
        }
        String text = String.join("\n", prose).trim();
        int split = text.indexOf("\n\n");
        if (split < 0) {
            doc.summary = text;
        } else {
            doc.summary = text.substring(0, split).trim();
            doc.body = text.substring(split).trim();
        }
        String brief = doc.first("brief");
        if (!brief.isEmpty()) {
            if (!doc.summary.isEmpty()) {
                doc.body = doc.body.isEmpty() ? doc.summary : doc.summary + "\n\n" + doc.body;
            }
            doc.summary = brief;
        }
        return doc;
    }
}

/** A documented definition: a procedure, reporter, breed or variable. */
final class Symbol {
    String name;
    String kind;          // procedure | reporter | global | breed | link-breed | own
    String owner = "";    // for own-variables: turtles, people, gathering-points, ...
    String extra = "";    // for breeds: the singular form
    String group = "";
    final List<String> params = new ArrayList<>();
    final List<String> bodyTokens = new ArrayList<>();
    final Set<String> calls = new TreeSet<>();
    final Set<String> calledBy = new TreeSet<>();
    SourceFile file;
    int line;
    Doc doc = new Doc();

    boolean isCallable() {
        return kind.equals("procedure") || kind.equals("reporter");
    }

    boolean isVariable() {
        return kind.equals("global") || kind.equals("own");
    }

    String signature() {
        if (isCallable()) {
            String keyword = kind.equals("reporter") ? "to-report" : "to";
            return params.isEmpty()
                ? keyword + " " + name
                : keyword + " " + name + " [" + String.join(" ", params) + "]";
        }
        if (kind.equals("breed")) {
            return "breed [" + name + " " + extra + "]";
        }
        if (kind.equals("link-breed")) {
            return "link-breed [" + name + " " + extra + "]";
        }
        if (kind.equals("own")) {
            return owner + "-own [ " + name + " ]";
        }
        return "globals [ " + name + " ]";
    }

    String anchor() {
        String prefix = isCallable() ? "p-" : isVariable() ? "v-" : "b-";
        return prefix + Html.slug(owner.isEmpty() ? name : owner + "-" + name);
    }

    String url() {
        return "files/" + file.slug + ".html#" + anchor();
    }
}

/** One source file and everything found in it. */
final class SourceFile {
    Path path;
    String rel;
    String slug;
    String module = "";
    Doc doc = new Doc();
    final List<Symbol> symbols = new ArrayList<>();
    final List<String> includes = new ArrayList<>();
    final List<String> extensions = new ArrayList<>();

    List<Symbol> of(String... kinds) {
        List<String> want = List.of(kinds);
        List<Symbol> found = new ArrayList<>();
        for (Symbol s : symbols) {
            if (want.contains(s.kind)) {
                found.add(s);
            }
        }
        return found;
    }
}

/** All parsed files plus the lookup tables built from them. */
final class Model {
    final Path root;
    final List<SourceFile> files = new ArrayList<>();
    final Map<String, Symbol> byName = new HashMap<>();
    final Map<String, List<SourceFile>> modules = new TreeMap<>();

    Model(Path root) {
        this.root = root;
    }

    void index() {
        for (SourceFile f : files) {
            String module = f.doc.tags("module").isEmpty() ? "" : f.doc.tags("module").get(0).arg;
            if (module.isEmpty()) {
                module = f.doc.first("module");
            }
            if (module.isEmpty()) {
                int slash = f.rel.indexOf('/');
                module = slash < 0 ? "Model root" : f.rel.substring(0, slash);
            }
            f.module = module;
            modules.computeIfAbsent(module, k -> new ArrayList<>()).add(f);
            for (Symbol s : f.symbols) {
                byName.putIfAbsent(s.name.toLowerCase(Locale.ROOT), s);
            }
        }
    }

    void resolveCallGraph() {
        for (SourceFile f : files) {
            for (Symbol s : f.symbols) {
                if (!s.isCallable()) {
                    continue;
                }
                for (String token : s.bodyTokens) {
                    Symbol target = byName.get(token);
                    if (target != null && target.isCallable() && target != s) {
                        s.calls.add(target.name);
                        target.calledBy.add(s.name);
                    }
                }
            }
        }
    }

    List<Symbol> allSymbols() {
        List<Symbol> all = new ArrayList<>();
        for (SourceFile f : files) {
            all.addAll(f.symbols);
        }
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return all;
    }
}

/* ------------------------------------------------------------------- parser */

final class Parser {

    static final String NAME = "[A-Za-z_#][A-Za-z0-9_?!#%.+*/<>=:-]*";
    static final Pattern PROCEDURE = Pattern.compile("(?i)^\\s*(to-report|to)\\s+(" + NAME + ")\\s*(.*)$");
    static final Pattern BREED = Pattern.compile("(?i)^(directed-link-breed|undirected-link-breed|breed)\\s*\\[\\s*("
        + NAME + ")\\s+(" + NAME + ")\\s*\\]");
    static final Pattern OWN = Pattern.compile("(?i)^([A-Za-z_][A-Za-z0-9_?!#%-]*)-own\\s*(\\[.*)?$");
    static final Pattern GLOBALS = Pattern.compile("(?i)^globals\\s*(\\[.*)?$");
    static final Pattern EXTENSIONS = Pattern.compile("(?i)^extensions\\s*(\\[.*)?$");
    static final Pattern INCLUDES = Pattern.compile("(?i)^__includes\\s*(\\[.*)?$");
    private static final String DECORATION = ";=*-_+~#";
    static final Pattern REGION = Pattern.compile("(?i)^;+\\s*#(?:region|endregion)\\b");
    static final Pattern TAG = Pattern.compile("^\\s*@([A-Za-z][A-Za-z-]*)\\b[ \\t]*(.*)$");
    static final Pattern FIRST_WORD = Pattern.compile("^\\s*(\\S+)");
    static final Pattern GROUP_LINE = Pattern.compile("^\\s*@group\\s+(.*)$");
    static final Pattern TOKEN = Pattern.compile(NAME);

    Model parseTree(Path root, Path out) throws IOException {
        Model model = new Model(root);
        List<Path> found = new ArrayList<>();
        Path outAbs = out.toAbsolutePath().normalize();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = String.valueOf(dir.getFileName());
                if (name.equals(".git") || name.equals("node_modules") || name.equals("api")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (dir.toAbsolutePath().normalize().startsWith(outAbs)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".nls") || name.endsWith(".nlogo") || name.endsWith(".nlogox")
                        || name.endsWith(".nlogo3d")) {
                    found.add(p);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(found);

        // A .nlogo and a .nlogox side by side are the same model; keep the newer format.
        Set<String> nlogox = new HashSet<>();
        for (Path p : found) {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nlogox")) {
                nlogox.add(stripExtension(p.toString()));
            }
        }

        Set<String> slugs = new HashSet<>();
        for (Path p : found) {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nlogo")
                    && nlogox.contains(stripExtension(p.toString()))) {
                continue;
            }
            model.files.add(parseFile(p, root, slugs));
        }
        return model;
    }

    private static String stripExtension(String s) {
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(0, dot);
    }

    SourceFile parseFile(Path path, Path root, Set<String> slugs) {
        SourceFile file = new SourceFile();
        file.path = path;
        file.rel = root.relativize(path).toString().replace('\\', '/');
        String base = Html.slug(stripExtension(file.rel));
        String slug = base;
        for (int n = 2; !slugs.add(slug); n++) {
            slug = base + "-" + n;
        }
        file.slug = slug;

        String text = read(path);
        String lower = file.rel.toLowerCase(Locale.ROOT);
        int offset = 0;
        if (lower.endsWith(".nlogox")) {
            int start = text.indexOf("<code>");
            int end = text.indexOf("</code>");
            if (start >= 0 && end > start) {
                String code = text.substring(start + "<code>".length(), end);
                offset = countLines(text.substring(0, start + "<code>".length()));
                int cdata = code.indexOf("<![CDATA[");
                if (cdata >= 0) {
                    offset += countLines(code.substring(0, cdata + "<![CDATA[".length()));
                    code = code.substring(cdata + "<![CDATA[".length());
                    int close = code.indexOf("]]>");
                    if (close >= 0) {
                        code = code.substring(0, close);
                    }
                } else {
                    code = code.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                }
                text = code;
            } else {
                text = "";
            }
        } else if (lower.endsWith(".nlogo") || lower.endsWith(".nlogo3d")) {
            int marker = text.indexOf("\n@#$#@#$#@");
            if (marker >= 0) {
                text = text.substring(0, marker);
            }
        }

        List<String> lines = List.of(text.split("\r?\n", -1));
        parseLines(file, lines, offset);
        return file;
    }

    private void parseLines(SourceFile file, List<String> lines, int offset) {
        List<String> pending = new ArrayList<>();
        boolean sawDefinition = false;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();

            if (trimmed.startsWith(";")) {
                if (isBanner(trimmed) || REGION.matcher(trimmed).find()) {
                    pending.clear();
                } else if (trimmed.startsWith(";;")) {
                    pending.add(stripMarker(trimmed));
                } else {
                    pending.clear();   // a single `;` is an implementation note, not documentation
                }
                continue;
            }

            if (trimmed.isEmpty()) {
                // A block at the top of the file, separated from the code, documents the file.
                if (!pending.isEmpty() && !sawDefinition && file.doc.isEmpty()) {
                    file.doc = Doc.parse(String.join("\n", pending));
                }
                pending.clear();
                continue;
            }

            String rawCode = stripComment(raw);
            String code = rawCode.trim();
            String pendingText = String.join("\n", pending);
            Doc doc = Doc.parse(pendingText);
            // An explicit @file block always documents the file, never the next definition.
            if (doc.has("file")) {
                file.doc = doc;
                doc = new Doc();
            }
            pending.clear();

            Matcher m = INCLUDES.matcher(code);
            if (m.find()) {
                Bracket b = readBracket(lines, i, code.indexOf('['));
                Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(b.content);
                while (q.find()) {
                    file.includes.add(q.group(1));
                }
                i = b.endLine;
                continue;
            }

            m = EXTENSIONS.matcher(code);
            if (m.find()) {
                Bracket b = readBracket(lines, i, code.indexOf('['));
                for (String token : b.content.trim().split("\\s+")) {
                    if (!token.isBlank()) {
                        file.extensions.add(token);
                    }
                }
                i = b.endLine;
                continue;
            }

            m = PROCEDURE.matcher(rawCode);
            if (m.find()) {
                sawDefinition = true;
                Symbol s = new Symbol();
                s.name = m.group(2);
                s.kind = m.group(1).equalsIgnoreCase("to-report") ? "reporter" : "procedure";
                s.file = file;
                s.line = i + offset + 1;
                s.doc = doc;
                int bodyStart = i;
                int bodyCol = m.end(2);
                // Only a bracket immediately after the name is a parameter list; anything
                // else belongs to the body of a procedure written on a single line.
                if (m.group(3).trim().startsWith("[")) {
                    Bracket b = readBracket(lines, i, rawCode.indexOf('[', bodyCol));
                    for (String p : b.content.trim().split("\\s+")) {
                        if (!p.isBlank()) {
                            s.params.add(p);
                        }
                    }
                    bodyStart = b.endLine;
                    bodyCol = b.endCol + 1;
                }
                int endLine = collectBody(lines, bodyStart, bodyCol, s);
                file.symbols.add(s);
                i = endLine;
                continue;
            }

            m = BREED.matcher(code);
            if (m.find()) {
                sawDefinition = true;
                Symbol s = new Symbol();
                s.name = m.group(2);
                s.extra = m.group(3);
                s.kind = m.group(1).equalsIgnoreCase("breed") ? "breed" : "link-breed";
                s.file = file;
                s.line = i + offset + 1;
                s.doc = doc;
                file.symbols.add(s);
                continue;
            }

            m = GLOBALS.matcher(code);
            if (m.find()) {
                sawDefinition = true;
                i = parseVarBlock(file, lines, i, "globals".length(), "global", "", offset, doc);
                continue;
            }

            m = OWN.matcher(code);
            if (m.find()) {
                sawDefinition = true;
                String owner = m.group(1);
                i = parseVarBlock(file, lines, i, owner.length() + "-own".length(), "own", owner, offset, doc);
                continue;
            }

            sawDefinition = true;
        }
    }

    /**
     * Record the tokens of a procedure body; returns the line holding its `end`.
     *
     * Scanning starts just after the header so that a procedure written entirely on
     * one line (`to-report x report 1 end`) still terminates on its own `end`.
     */
    private int collectBody(List<String> lines, int startLine, int startCol, Symbol s) {
        for (int i = startLine; i < lines.size(); i++) {
            String code = stripStrings(stripComment(lines.get(i)));
            int from = (i == startLine) ? Math.min(Math.max(startCol, 0), code.length()) : 0;
            Matcher m = TOKEN.matcher(code);
            m.region(from, code.length());
            while (m.find()) {
                String token = m.group().toLowerCase(Locale.ROOT);
                if (token.equals("end")) {
                    return i;
                }
                s.bodyTokens.add(token);
            }
        }
        return lines.size() - 1;
    }

    /**
     * Read a `globals [...]` or `<breed>-own [...]` block, documenting each variable
     * from the `;;` lines above it or the trailing comment beside it.
     */
    private int parseVarBlock(SourceFile file, List<String> lines, int startLine, int keywordLength,
                              String kind, String owner, int offset, Doc blockDoc) {
        int depth = 0;
        boolean started = false;
        List<String> pending = new ArrayList<>();
        String group = blockDoc.first("group");
        if (group.isEmpty() && !blockDoc.tags("group").isEmpty()) {
            group = blockDoc.tags("group").get(0).arg;
        }

        for (int i = startLine; i < lines.size(); i++) {
            String raw = lines.get(i);
            String code = stripComment(raw);
            String comment = commentOf(raw);
            String head = code.trim();
            String scan = (i == startLine) ? head.substring(Math.min(keywordLength, head.length())) : code;

            List<String> names = new ArrayList<>();
            StringBuilder token = new StringBuilder();
            boolean closed = false;
            for (int j = 0; j < scan.length(); j++) {
                char c = scan.charAt(j);
                if (c == '[') {
                    flush(token, names, started, depth);
                    depth++;
                    started = true;
                } else if (c == ']') {
                    flush(token, names, started, depth);
                    depth--;
                    if (started && depth <= 0) {
                        closed = true;
                        break;
                    }
                } else if (Character.isWhitespace(c)) {
                    flush(token, names, started, depth);
                } else {
                    token.append(c);
                }
            }
            flush(token, names, started, depth);

            if (names.isEmpty()) {
                if (comment != null) {
                    String trimmed = comment.trim();
                    Matcher g = GROUP_LINE.matcher(stripMarker(trimmed));
                    if (isBanner(trimmed) || REGION.matcher(trimmed).find()) {
                        pending.clear();
                    } else if (g.find()) {
                        group = g.group(1).trim();
                        pending.clear();
                    } else if (trimmed.startsWith(";;")) {
                        pending.add(stripMarker(trimmed));
                    } else {
                        pending.clear();
                    }
                }
                if (closed) {
                    return i;
                }
                continue;
            }

            // Inside a variable block a trailing comment always documents the variable.
            String trailing = "";
            if (comment != null && names.size() == 1) {
                trailing = stripMarker(comment.trim());
                if (trailing.startsWith("<")) {
                    trailing = trailing.substring(1).trim();
                }
            }
            for (int n = 0; n < names.size(); n++) {
                Symbol s = new Symbol();
                s.name = names.get(n);
                s.kind = kind;
                s.owner = owner;
                s.file = file;
                s.line = i + offset + 1;
                s.group = group;
                if (n == 0) {
                    List<String> text = new ArrayList<>(pending);
                    if (!trailing.isEmpty()) {
                        text.add(0, trailing);
                    }
                    s.doc = Doc.parse(String.join("\n", text));
                    String own = s.doc.first("group");
                    if (own.isEmpty() && !s.doc.tags("group").isEmpty()) {
                        own = s.doc.tags("group").get(0).arg;
                    }
                    if (!own.isEmpty()) {
                        s.group = own;
                        group = own;
                    }
                }
                file.symbols.add(s);
            }
            pending.clear();
            if (closed) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    private static void flush(StringBuilder token, List<String> names, boolean started, int depth) {
        if (token.length() > 0) {
            if (started && depth >= 1) {
                names.add(token.toString());
            }
            token.setLength(0);
        }
    }

    /** Read the bracketed list starting at or after `col` on `line`. */
    static Bracket readBracket(List<String> lines, int line, int col) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = line; i < lines.size(); i++) {
            String code = stripComment(lines.get(i));
            int start = (i == line && col > 0) ? Math.min(col, code.length()) : 0;
            for (int j = start; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '[') {
                    depth++;
                    started = true;
                    if (depth == 1) {
                        continue;
                    }
                } else if (c == ']') {
                    depth--;
                    if (started && depth == 0) {
                        return new Bracket(sb.toString(), i, j);
                    }
                }
                if (started && depth >= 1) {
                    sb.append(c);
                }
            }
            if (started) {
                sb.append('\n');
            }
        }
        return new Bracket(sb.toString(), lines.size() - 1, 0);
    }

    /** Drop a trailing `;` comment, ignoring semicolons inside string literals. */
    static String stripComment(String line) {
        int cut = commentStart(line);
        return cut < 0 ? line : line.substring(0, cut);
    }

    /** The comment part of a line, including its leading `;`, or null. */
    static String commentOf(String line) {
        int cut = commentStart(line);
        return cut < 0 ? null : line.substring(cut);
    }

    private static int commentStart(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == ';' && !inString) {
                return i;
            }
        }
        return -1;
    }

    /** Blank out string literals, keeping the line length so columns stay valid. */
    static String stripStrings(String line) {
        char[] chars = line.toCharArray();
        boolean inString = false;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '"' && (i == 0 || chars[i - 1] != '\\')) {
                inString = !inString;
                chars[i] = ' ';
            } else if (inString) {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    /**
     * Whether a comment line is a section banner such as `;;;;;;;;;` or `;; -------`.
     *
     * A bare `;;` is not a banner: it is the blank line of a documentation block.
     */
    static boolean isBanner(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == ';') {
            i++;
        }
        String rest = trimmed.substring(i).trim();
        if (rest.isEmpty()) {
            return i >= 3;
        }
        if (rest.length() < 3) {
            return false;
        }
        for (char c : rest.toCharArray()) {
            if (DECORATION.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Remove the leading semicolons of a comment line, keeping the indentation after them. */
    static String stripMarker(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ';') {
            i++;
        }
        String rest = line.substring(i);
        return rest.startsWith(" ") ? rest.substring(1) : rest;
    }

    static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class Bracket {
        final String content;
        final int endLine;
        final int endCol;

        Bracket(String content, int endLine, int endCol) {
            this.content = content;
            this.endLine = endLine;
            this.endCol = endCol;
        }
    }
}

/* --------------------------------------------------------------------- html */

/** Escaping, slugs, and a small markdown subset for documentation prose. */
final class Html {

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Collapse a wrapped tag value onto one line, dropping the comment indentation. */
    static String flatten(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static String slug(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' ? Character.toLowerCase(c) : '_');
        }
        return sb.toString();
    }

    /** Render a documentation string: paragraphs, lists, fenced code, and inline spans. */
    static String md(String text, Model model, String rootPrefix) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        List<String> items = new ArrayList<>();
        List<String> code = new ArrayList<>();
        boolean inCode = false;

        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    out.append("<pre class=\"code\"><code>").append(esc(String.join("\n", code)))
                       .append("</code></pre>\n");
                    code.clear();
                    inCode = false;
                } else {
                    flushParagraph(out, paragraph, model, rootPrefix);
                    flushList(out, items, model, rootPrefix);
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.add(line);
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(out, paragraph, model, rootPrefix);
                flushList(out, items, model, rootPrefix);
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(out, paragraph, model, rootPrefix);
                items.add(trimmed.substring(2).trim());
                continue;
            }
            if (!items.isEmpty()) {
                items.set(items.size() - 1, items.get(items.size() - 1) + " " + trimmed);
                continue;
            }
            paragraph.add(trimmed);
        }
        if (inCode) {
            out.append("<pre class=\"code\"><code>").append(esc(String.join("\n", code))).append("</code></pre>\n");
        }
        flushParagraph(out, paragraph, model, rootPrefix);
        flushList(out, items, model, rootPrefix);
        return out.toString();
    }

    private static void flushParagraph(StringBuilder out, List<String> lines, Model model, String prefix) {
        if (lines.isEmpty()) {
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (String line : lines) {
            rendered.add(inline(line, model, prefix));
        }
        out.append("<p>").append(String.join("<br>\n", rendered)).append("</p>\n");
        lines.clear();
    }

    private static void flushList(StringBuilder out, List<String> items, Model model, String prefix) {
        if (items.isEmpty()) {
            return;
        }
        out.append("<ul>\n");
        for (String item : items) {
            out.append("<li>").append(inline(item, model, prefix)).append("</li>\n");
        }
        out.append("</ul>\n");
        items.clear();
    }

    /** `code` (linked when it names a symbol), **bold**, *italic*, [text](url). */
    static String inline(String text, Model model, String prefix) {
        String s = esc(text);
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String name = m.group(1);
            Symbol target = model == null ? null : model.byName.get(name.toLowerCase(Locale.ROOT));
            if (target != null) {
                sb.append("<a href=\"").append(prefix).append(target.url()).append("\"><code>")
                  .append(name).append("</code></a>");
            } else {
                sb.append("<code>").append(name).append("</code>");
            }
            last = m.end();
        }
        sb.append(s.substring(last));
        String result = sb.toString();
        result = result.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        result = result.replaceAll("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])", "<em>$1</em>");
        return result;
    }

    /** A link to a symbol by name, or the bare name when it is unknown. */
    static String link(String name, Model model, String prefix) {
        Symbol target = model.byName.get(name.toLowerCase(Locale.ROOT));
        if (target == null) {
            return "<code>" + esc(name) + "</code>";
        }
        return "<a href=\"" + prefix + target.url() + "\"><code>" + esc(name) + "</code></a>";
    }
}

final class HtmlWriter {

    private final Model model;
    private final Options opt;
    private final String generated = LocalDate.now().toString();

    HtmlWriter(Model model, Options opt) {
        this.model = model;
        this.opt = opt;
    }

    void write() throws IOException {
        Files.createDirectories(opt.out.resolve("files"));
        Files.createDirectories(opt.out.resolve("assets"));
        writeText(opt.out.resolve("assets/style.css"), Assets.CSS);
        writeText(opt.out.resolve("assets/nlsdoc.js"), Assets.JS);
        writeText(opt.out.resolve("assets/symbols.js"), symbolData());
        writeText(opt.out.resolve("index.html"), overview());
        writeText(opt.out.resolve("symbols.html"), symbolIndex());
        writeText(opt.out.resolve("coverage.html"), coverage());
        for (SourceFile f : model.files) {
            writeText(opt.out.resolve("files/" + f.slug + ".html"), filePage(f));
        }
    }

    private static void writeText(Path p, String content) throws IOException {
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    /* --------------------------------------------------------------- chrome */

    private String page(String title, String prefix, String active, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(Html.esc(title)).append(" &middot; ").append(Html.esc(opt.title))
          .append("</title>\n");
        sb.append("<link rel=\"stylesheet\" href=\"").append(prefix).append("assets/style.css\">\n");
        sb.append("</head>\n<body data-root=\"").append(prefix).append("\">\n");
        sb.append("<div class=\"layout\">\n");
        sb.append(sidebar(prefix, active));
        sb.append("<main>\n").append(content);
        sb.append("<footer>Generated by nlsdoc on ").append(generated)
          .append(" &middot; ").append(model.files.size()).append(" files, ")
          .append(model.allSymbols().size()).append(" symbols</footer>\n");
        sb.append("</main>\n</div>\n");
        sb.append("<script src=\"").append(prefix).append("assets/symbols.js\"></script>\n");
        sb.append("<script src=\"").append(prefix).append("assets/nlsdoc.js\"></script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private String sidebar(String prefix, String active) {
        StringBuilder sb = new StringBuilder();
        sb.append("<aside class=\"sidebar\">\n");
        sb.append("<a class=\"brand\" href=\"").append(prefix).append("index.html\">")
          .append(Html.esc(opt.title)).append("</a>\n");
        sb.append("<div class=\"search\"><input id=\"q\" type=\"search\" placeholder=\"Search symbols\" ")
          .append("autocomplete=\"off\" spellcheck=\"false\"><ul id=\"results\" hidden></ul></div>\n");
        sb.append("<nav class=\"top\">\n");
        sb.append(navLink(prefix + "index.html", "Overview", active.equals("index")));
        sb.append(navLink(prefix + "symbols.html", "Symbol index", active.equals("symbols")));
        sb.append(navLink(prefix + "coverage.html", "Coverage", active.equals("coverage")));
        sb.append("</nav>\n");
        for (Map.Entry<String, List<SourceFile>> e : model.modules.entrySet()) {
            sb.append("<div class=\"module\">").append(Html.esc(e.getKey())).append("</div>\n<nav>\n");
            for (SourceFile f : e.getValue()) {
                String label = f.rel.substring(f.rel.lastIndexOf('/') + 1);
                sb.append(navLink(prefix + "files/" + f.slug + ".html", label, active.equals(f.slug)));
            }
            sb.append("</nav>\n");
        }
        sb.append("</aside>\n");
        return sb.toString();
    }

    private static String navLink(String href, String label, boolean current) {
        return "<a class=\"" + (current ? "nav current" : "nav") + "\" href=\"" + href + "\">"
            + Html.esc(label) + "</a>\n";
    }

    /* ---------------------------------------------------------------- pages */

    private String overview() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>").append(Html.esc(opt.title)).append("</h1>\n");
        sb.append("<p class=\"lead\">Reference generated from the documentation comments in the NetLogo "
            + "sources. See <code>COMMENT_CONVENTION.md</code> for how to write them.</p>\n");

        Stats total = Stats.of(model.allSymbols());
        sb.append(bar(total));

        for (Map.Entry<String, List<SourceFile>> e : model.modules.entrySet()) {
            sb.append("<h2 id=\"m-").append(Html.slug(e.getKey())).append("\">")
              .append(Html.esc(e.getKey())).append("</h2>\n");
            sb.append("<table class=\"files\">\n<tr><th>File</th><th>Description</th>"
                + "<th class=\"num\">Procedures</th><th class=\"num\">Variables</th></tr>\n");
            for (SourceFile f : e.getValue()) {
                sb.append("<tr><td><a href=\"files/").append(f.slug).append(".html\"><code>")
                  .append(Html.esc(f.rel)).append("</code></a></td><td>")
                  .append(Html.md(f.doc.summary, model, "")).append("</td><td class=\"num\">")
                  .append(f.of("procedure", "reporter").size()).append("</td><td class=\"num\">")
                  .append(f.of("global", "own").size()).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }
        return page("Overview", "", "index", sb.toString());
    }

    private String symbolIndex() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Symbol index</h1>\n");
        Map<String, List<Symbol>> byLetter = new TreeMap<>();
        for (Symbol s : model.allSymbols()) {
            String letter = s.name.substring(0, 1).toUpperCase(Locale.ROOT);
            if (!Character.isLetter(letter.charAt(0))) {
                letter = "#";
            }
            byLetter.computeIfAbsent(letter, k -> new ArrayList<>()).add(s);
        }
        sb.append("<p class=\"letters\">");
        for (String letter : byLetter.keySet()) {
            sb.append("<a href=\"#l-").append(Html.slug(letter)).append("\">").append(letter).append("</a> ");
        }
        sb.append("</p>\n");
        for (Map.Entry<String, List<Symbol>> e : byLetter.entrySet()) {
            sb.append("<h2 id=\"l-").append(Html.slug(e.getKey())).append("\">").append(e.getKey())
              .append("</h2>\n<table class=\"symbols\">\n");
            for (Symbol s : e.getValue()) {
                sb.append("<tr><td><a href=\"").append(s.url()).append("\"><code>")
                  .append(Html.esc(s.name)).append("</code></a></td><td>").append(kindBadge(s))
                  .append("</td><td>").append(Html.md(s.doc.summary, model, ""))
                  .append("</td><td class=\"src\"><code>").append(Html.esc(s.file.rel))
                  .append("</code></td></tr>\n");
            }
            sb.append("</table>\n");
        }
        return page("Symbol index", "", "symbols", sb.toString());
    }

    private String coverage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Documentation coverage</h1>\n");
        sb.append("<p class=\"lead\">Definitions without a <code>;;</code> comment. This is a to-do list, "
            + "not an error report.</p>\n");
        sb.append(bar(Stats.of(model.allSymbols())));
        sb.append("<table class=\"files\">\n<tr><th>File</th><th class=\"num\">Documented</th>"
            + "<th>Undocumented</th></tr>\n");
        for (SourceFile f : model.files) {
            Stats st = Stats.of(f.symbols);
            if (st.total == 0) {
                continue;
            }
            sb.append("<tr><td><a href=\"files/").append(f.slug).append(".html\"><code>")
              .append(Html.esc(f.rel)).append("</code></a></td><td class=\"num\">")
              .append(st.documented).append("/").append(st.total).append("</td><td class=\"missing\">");
            List<String> missing = new ArrayList<>();
            for (Symbol s : f.symbols) {
                if (s.doc.isEmpty()) {
                    missing.add("<a href=\"" + s.url() + "\"><code>" + Html.esc(s.name) + "</code></a>");
                }
            }
            sb.append(missing.isEmpty() ? "<span class=\"ok\">complete</span>" : String.join(", ", missing));
            sb.append("</td></tr>\n");
        }
        sb.append("</table>\n");
        return page("Coverage", "", "coverage", sb.toString());
    }

    private String filePage(SourceFile f) {
        String prefix = "../";
        StringBuilder sb = new StringBuilder();
        sb.append("<h1><code>").append(Html.esc(f.rel)).append("</code></h1>\n");
        if (!f.doc.summary.isBlank()) {
            sb.append("<div class=\"lead\">").append(Html.md(f.doc.summary, model, prefix)).append("</div>\n");
        }
        sb.append(Html.md(f.doc.body, model, prefix));
        sb.append(fileTags(f, prefix));

        if (!f.extensions.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String e : f.extensions) {
                names.add("<code>" + Html.esc(e) + "</code>");
            }
            sb.append("<p class=\"meta\"><span class=\"label\">Extensions</span> ")
              .append(String.join(" ", names)).append("</p>\n");
        }
        if (!f.includes.isEmpty()) {
            List<String> links = new ArrayList<>();
            for (String inc : f.includes) {
                SourceFile target = findByRel(inc);
                links.add(target == null
                    ? "<code>" + Html.esc(inc) + "</code>"
                    : "<a href=\"" + target.slug + ".html\"><code>" + Html.esc(inc) + "</code></a>");
            }
            sb.append("<p class=\"meta\"><span class=\"label\">Includes</span> ")
              .append(String.join(" ", links)).append("</p>\n");
        }

        List<Symbol> breeds = f.of("breed", "link-breed");
        if (!breeds.isEmpty()) {
            sb.append("<h2 id=\"breeds\">Breeds</h2>\n");
            for (Symbol s : breeds) {
                sb.append(symbolCard(s, prefix));
            }
        }

        List<Symbol> variables = f.of("global", "own");
        if (!variables.isEmpty()) {
            sb.append("<h2 id=\"variables\">Variables</h2>\n");
            Map<String, List<Symbol>> buckets = new LinkedHashMap<>();
            for (Symbol s : variables) {
                String owner = s.kind.equals("global") ? "globals" : s.owner + "-own";
                String label = s.group.isEmpty() ? owner : owner + " &mdash; " + Html.esc(s.group);
                buckets.computeIfAbsent(label, k -> new ArrayList<>()).add(s);
            }
            for (Map.Entry<String, List<Symbol>> e : buckets.entrySet()) {
                sb.append("<h3 class=\"block\"><code>").append(e.getKey()).append("</code></h3>\n");
                sb.append("<table class=\"vars\">\n");
                for (Symbol s : e.getValue()) {
                    sb.append("<tr id=\"").append(s.anchor()).append("\"><td><code>")
                      .append(Html.esc(s.name)).append("</code></td><td>")
                      .append(docCell(s, prefix)).append("</td><td class=\"src\">")
                      .append(sourceLink(s)).append("</td></tr>\n");
                }
                sb.append("</table>\n");
            }
        }

        List<Symbol> procedures = f.of("procedure");
        List<Symbol> reporters = f.of("reporter");
        if (!procedures.isEmpty()) {
            sb.append("<h2 id=\"procedures\">Procedures</h2>\n");
            for (Symbol s : procedures) {
                sb.append(symbolCard(s, prefix));
            }
        }
        if (!reporters.isEmpty()) {
            sb.append("<h2 id=\"reporters\">Reporters</h2>\n");
            for (Symbol s : reporters) {
                sb.append(symbolCard(s, prefix));
            }
        }
        return page(f.rel, prefix, f.slug, sb.toString());
    }

    private String fileTags(SourceFile f, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (Tag t : f.doc.tags) {
            if (t.name.equals("author") || t.name.equals("since") || t.name.equals("see")) {
                sb.append("<p class=\"meta\"><span class=\"label\">")
                  .append(Character.toUpperCase(t.name.charAt(0))).append(t.name.substring(1))
                  .append("</span> ").append(Html.inline(t.text, model, prefix)).append("</p>\n");
            }
        }
        return sb.toString();
    }

    private String docCell(Symbol s, String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(Html.md(s.doc.summary, model, prefix));
        sb.append(Html.md(s.doc.body, model, prefix));
        String unit = s.doc.first("unit");
        if (!unit.isEmpty()) {
            sb.append("<p class=\"meta\"><span class=\"label\">Unit</span> ")
              .append(Html.inline(unit, model, prefix)).append("</p>\n");
        }
        sb.append(tagBlock(s, "note", "Note", prefix));
        sb.append(tagBlock(s, "see", "See also", prefix));
        sb.append(tagBlock(s, "todo", "To do", prefix));
        sb.append(unknownTags(s.doc, prefix));
        if (sb.length() == 0) {
            sb.append("<span class=\"todo\">undocumented</span>");
        }
        return sb.toString();
    }

    private String symbolCard(Symbol s, String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"symbol\" id=\"").append(s.anchor()).append("\">\n");
        sb.append("<h3><a class=\"self\" href=\"#").append(s.anchor()).append("\"><code>")
          .append(Html.esc(s.name)).append("</code></a> ").append(kindBadge(s));
        if (s.doc.has("deprecated")) {
            sb.append(" <span class=\"badge warn\">deprecated</span>");
        }
        if (s.doc.has("internal")) {
            sb.append(" <span class=\"badge muted\">internal</span>");
        }
        sb.append(" <span class=\"src\">").append(sourceLink(s)).append("</span></h3>\n");
        sb.append("<pre class=\"code sig\"><code>").append(Html.esc(s.signature())).append("</code></pre>\n");

        if (s.doc.isEmpty()) {
            sb.append("<p class=\"todo\">undocumented</p>\n");
        } else {
            sb.append(Html.md(s.doc.summary, model, prefix));
            sb.append(Html.md(s.doc.body, model, prefix));
        }

        List<Tag> params = s.doc.tags("param");
        if (!params.isEmpty()) {
            sb.append("<h4>Parameters</h4>\n<table class=\"params\">\n");
            for (Tag t : params) {
                boolean known = s.params.isEmpty() || s.params.stream().anyMatch(p -> p.equalsIgnoreCase(t.arg));
                sb.append("<tr><td><code>").append(Html.esc(t.arg)).append("</code>")
                  .append(known ? "" : " <span class=\"badge warn\">not a parameter</span>")
                  .append("</td><td>").append(Html.inline(Html.flatten(t.text), model, prefix))
                  .append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }
        for (String p : s.params) {
            boolean documented = params.stream().anyMatch(t -> t.arg.equalsIgnoreCase(p));
            if (!documented && !params.isEmpty()) {
                sb.append("<p class=\"todo\">Undocumented parameter <code>").append(Html.esc(p))
                  .append("</code></p>\n");
            }
        }

        sb.append(tagBlock(s, "return", "Returns", prefix));
        sb.append(tagBlock(s, "returns", "Returns", prefix));
        sb.append(tagBlock(s, "context", "Agent context", prefix));
        sb.append(tagBlock(s, "side-effects", "Side effects", prefix));
        sb.append(tagBlock(s, "note", "Note", prefix));
        sb.append(tagBlock(s, "since", "Since", prefix));
        sb.append(tagBlock(s, "deprecated", "Deprecated", prefix));
        sb.append(tagBlock(s, "todo", "To do", prefix));
        sb.append(unknownTags(s.doc, prefix));

        for (Tag t : s.doc.tags("example")) {
            sb.append("<h4>Example</h4>\n").append(Html.md(exampleText(t.text), model, prefix));
        }

        List<Tag> see = s.doc.tags("see");
        if (!see.isEmpty()) {
            List<String> links = new ArrayList<>();
            for (Tag t : see) {
                for (String name : (t.arg + " " + t.text).trim().split("[\\s,]+")) {
                    if (!name.isBlank()) {
                        links.add(Html.link(name, model, prefix));
                    }
                }
            }
            sb.append("<p class=\"meta\"><span class=\"label\">See also</span> ")
              .append(String.join(", ", links)).append("</p>\n");
        }

        if (!s.calls.isEmpty()) {
            sb.append("<p class=\"meta\"><span class=\"label\">Calls</span> ")
              .append(linkList(s.calls, prefix)).append("</p>\n");
        }
        if (!s.calledBy.isEmpty()) {
            sb.append("<p class=\"meta\"><span class=\"label\">Called by</span> ")
              .append(linkList(s.calledBy, prefix)).append("</p>\n");
        }
        sb.append("</section>\n");
        return sb.toString();
    }

    /** An example is shown as code unless the author already fenced or prosed it. */
    private static String exampleText(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            return trimmed;
        }
        return "```\n" + trimmed + "\n```";
    }

    private static final Set<String> KNOWN_TAGS = Set.of(
        "brief", "param", "return", "returns", "context", "side-effects", "note", "since",
        "deprecated", "todo", "example", "see", "internal", "file", "module", "group", "unit",
        "author");

    /** Show tags nlsdoc does not know about rather than dropping a mistyped one. */
    private String unknownTags(Doc doc, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (Tag t : doc.tags) {
            if (KNOWN_TAGS.contains(t.name)) {
                continue;
            }
            sb.append("<p class=\"meta\"><span class=\"label\">@").append(Html.esc(t.name))
              .append("</span> ").append(Html.inline(Html.flatten(t.arg + " " + t.text),
                  model, prefix)).append("</p>\n");
        }
        return sb.toString();
    }

    private String tagBlock(Symbol s, String tag, String label, String prefix) {
        List<Tag> found = s.doc.tags(tag);
        if (found.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Tag t : found) {
            String text = (tag.equals("context") ? (t.arg + " " + t.text) : t.text).trim();
            sb.append("<p class=\"meta\"><span class=\"label\">").append(label).append("</span> ")
              .append(Html.inline(Html.flatten(text), model, prefix)).append("</p>\n");
        }
        return sb.toString();
    }

    private String linkList(Set<String> names, String prefix) {
        List<String> links = new ArrayList<>();
        for (String n : names) {
            links.add(Html.link(n, model, prefix));
        }
        return String.join(", ", links);
    }

    private static String kindBadge(Symbol s) {
        String label = s.kind.equals("own") ? s.owner + "-own" : s.kind;
        return "<span class=\"badge kind-" + Html.slug(s.kind) + "\">" + Html.esc(label) + "</span>";
    }

    private String sourceLink(Symbol s) {
        Path page = opt.out.resolve("files");
        String href = page.relativize(s.file.path).toString().replace('\\', '/');
        return "<a class=\"source\" href=\"" + Html.esc(href) + "\">" + Html.esc(shortName(s.file.rel))
            + ":" + s.line + "</a>";
    }

    private static String shortName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    private SourceFile findByRel(String include) {
        String normalised = include.replace('\\', '/');
        for (SourceFile f : model.files) {
            if (f.rel.equals(normalised) || f.rel.endsWith("/" + normalised)) {
                return f;
            }
        }
        return null;
    }

    private String bar(Stats st) {
        int pct = st.percent();
        return "<div class=\"coverage\"><div class=\"bar\"><span style=\"width:" + pct + "%\"></span></div>"
            + "<div class=\"pct\">" + pct + "% documented <small>(" + st.documented + " of " + st.total
            + " definitions)</small></div></div>\n";
    }

    private String symbolData() {
        StringBuilder sb = new StringBuilder("window.NLSDOC_SYMBOLS = [\n");
        for (Symbol s : model.allSymbols()) {
            sb.append("{n:").append(json(s.name))
              .append(",k:").append(json(s.kind.equals("own") ? s.owner + "-own" : s.kind))
              .append(",u:").append(json(s.url()))
              .append(",b:").append(json(oneLine(s.doc.summary)))
              .append("},\n");
        }
        sb.append("];\n");
        return sb.toString();
    }

    private static String oneLine(String s) {
        String t = Html.flatten(s.replace("`", ""));
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '<': sb.append("\\u003c"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}

/* ------------------------------------------------------------------ reports */

final class Stats {
    int total;
    int documented;

    static Stats of(List<Symbol> symbols) {
        Stats st = new Stats();
        for (Symbol s : symbols) {
            st.total++;
            if (!s.doc.isEmpty()) {
                st.documented++;
            }
        }
        return st;
    }

    int percent() {
        return total == 0 ? 100 : (int) Math.round(100.0 * documented / total);
    }
}

final class Report {

    static void summary(Model model, Options opt) {
        if (opt.quiet) {
            return;
        }
        Stats st = Stats.of(model.allSymbols());
        System.out.println("nlsdoc: " + model.files.size() + " files, " + st.total + " definitions, "
            + st.percent() + "% documented");
        System.out.println("nlsdoc: wrote " + opt.out.resolve("index.html"));
    }

    static int check(Model model, Options opt) {
        Stats total = Stats.of(model.allSymbols());
        for (SourceFile f : model.files) {
            Stats st = Stats.of(f.symbols);
            if (st.total == 0 || st.documented == st.total) {
                continue;
            }
            System.out.println(f.rel + ": " + st.documented + "/" + st.total + " documented");
            if (!opt.quiet) {
                for (Symbol s : f.symbols) {
                    if (s.doc.isEmpty()) {
                        System.out.println("    " + s.line + ": " + s.kind + " " + s.name);
                    }
                }
            }
        }
        System.out.println("total: " + total.documented + "/" + total.total + " (" + total.percent() + "%)");
        if (total.percent() < opt.minCoverage) {
            System.out.println("nlsdoc: coverage below --min-coverage " + opt.minCoverage);
            return 1;
        }
        return 0;
    }
}

/* ------------------------------------------------------------------- assets */

final class Assets {

    static final String CSS = """
        :root {
          --bg: #ffffff; --panel: #f6f7f9; --line: #e2e5ea; --text: #1d2127;
          --muted: #5f6672; --accent: #1a5fb4; --code-bg: #f2f4f7; --warn: #a33a1c;
          --ok: #1c7a4a;
        }
        @media (prefers-color-scheme: dark) {
          :root {
            --bg: #16181d; --panel: #1d2026; --line: #2c313a; --text: #e6e8ec;
            --muted: #9aa2b1; --accent: #7fb0ff; --code-bg: #22262e; --warn: #f0a48a;
            --ok: #7fd3a6;
          }
        }
        * { box-sizing: border-box; }
        body {
          margin: 0; background: var(--bg); color: var(--text);
          font: 15px/1.6 -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }
        code, pre, .src { font-family: "Cascadia Mono", Consolas, "SF Mono", Menlo, monospace; }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        .layout { display: flex; align-items: flex-start; }
        .sidebar {
          position: sticky; top: 0; width: 270px; flex: 0 0 270px; height: 100vh;
          overflow-y: auto; padding: 18px 14px; background: var(--panel);
          border-right: 1px solid var(--line);
        }
        .brand { display: block; font-weight: 600; font-size: 16px; margin-bottom: 12px; color: var(--text); }
        .search { position: relative; margin-bottom: 14px; }
        .search input {
          width: 100%; padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px;
          background: var(--bg); color: var(--text); font-size: 13px;
        }
        #results {
          position: absolute; z-index: 20; left: 0; right: 0; margin: 4px 0 0; padding: 4px;
          list-style: none; background: var(--bg); border: 1px solid var(--line);
          border-radius: 6px; max-height: 320px; overflow-y: auto;
          box-shadow: 0 6px 18px rgba(0,0,0,.14);
        }
        #results li a { display: block; padding: 5px 7px; border-radius: 4px; color: var(--text); }
        #results li a:hover, #results li a:focus { background: var(--panel); text-decoration: none; }
        #results .k { color: var(--muted); font-size: 11px; margin-left: 6px; }
        #results .b { display: block; color: var(--muted); font-size: 12px; }
        .module {
          margin: 16px 0 4px; font-size: 11px; letter-spacing: .08em; text-transform: uppercase;
          color: var(--muted);
        }
        .nav { display: block; padding: 3px 6px; border-radius: 4px; font-size: 13px; color: var(--text); }
        .nav:hover { background: var(--bg); text-decoration: none; }
        .nav.current { background: var(--bg); font-weight: 600; }
        nav.top { border-bottom: 1px solid var(--line); padding-bottom: 10px; }
        main { flex: 1 1 auto; min-width: 0; max-width: 960px; padding: 28px 34px 60px; }
        h1 { font-size: 26px; margin: 0 0 12px; }
        h2 { font-size: 20px; margin: 34px 0 10px; padding-bottom: 5px; border-bottom: 1px solid var(--line); }
        h3 { font-size: 16px; margin: 0 0 8px; }
        h3.block { margin: 20px 0 6px; font-weight: 600; }
        h4 { font-size: 13px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted);
             margin: 14px 0 4px; }
        p { margin: 8px 0; }
        .lead { color: var(--muted); }
        pre.code {
          background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px;
          padding: 10px 12px; overflow-x: auto; font-size: 13px; margin: 8px 0;
        }
        pre.sig { background: transparent; border-style: dashed; }
        p code, li code, td code { background: var(--code-bg); border-radius: 4px; padding: 1px 4px; font-size: 13px; }
        table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 14px; }
        th, td { text-align: left; vertical-align: top; padding: 6px 10px; border-bottom: 1px solid var(--line); }
        th { font-size: 12px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
        td.num, th.num { text-align: right; white-space: nowrap; }
        td.src, .src { font-size: 12px; color: var(--muted); white-space: nowrap; }
        .symbol {
          border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; margin: 12px 0;
          overflow-x: auto;
        }
        .symbol h3 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
        .symbol h3 .src { margin-left: auto; }
        .self { color: var(--text); }
        .badge {
          font-size: 11px; padding: 1px 7px; border-radius: 999px; border: 1px solid var(--line);
          color: var(--muted); text-transform: lowercase; letter-spacing: .03em;
        }
        .badge.warn { color: var(--warn); border-color: var(--warn); }
        .meta { font-size: 13px; }
        .label {
          display: inline-block; min-width: 92px; font-size: 11px; text-transform: uppercase;
          letter-spacing: .05em; color: var(--muted);
        }
        .todo { color: var(--warn); font-size: 13px; }
        .ok { color: var(--ok); }
        .coverage { margin: 16px 0 24px; }
        .bar { height: 8px; background: var(--code-bg); border-radius: 999px; overflow: hidden; }
        .bar span { display: block; height: 100%; background: var(--accent); }
        .pct { margin-top: 6px; font-size: 13px; color: var(--muted); }
        .letters a { margin-right: 8px; font-weight: 600; }
        footer { margin-top: 48px; padding-top: 12px; border-top: 1px solid var(--line);
                 color: var(--muted); font-size: 12px; }
        @media (max-width: 800px) {
          .layout { flex-direction: column; }
          .sidebar { position: static; width: 100%; flex: none; height: auto; }
          main { padding: 20px; }
        }
        """;

    static final String JS = """
        (function () {
          var input = document.getElementById('q');
          var list = document.getElementById('results');
          if (!input || !list || !window.NLSDOC_SYMBOLS) return;
          var root = document.body.getAttribute('data-root') || '';

          function render(matches) {
            list.innerHTML = '';
            if (!matches.length) { list.hidden = true; return; }
            matches.forEach(function (s) {
              var li = document.createElement('li');
              var a = document.createElement('a');
              a.href = root + s.u;
              var name = document.createElement('code');
              name.textContent = s.n;
              a.appendChild(name);
              var kind = document.createElement('span');
              kind.className = 'k';
              kind.textContent = s.k;
              a.appendChild(kind);
              if (s.b) {
                var brief = document.createElement('span');
                brief.className = 'b';
                brief.textContent = s.b;
                a.appendChild(brief);
              }
              li.appendChild(a);
              list.appendChild(li);
            });
            list.hidden = false;
          }

          function search() {
            var q = input.value.trim().toLowerCase();
            if (q.length < 2) { list.hidden = true; return; }
            var starts = [], contains = [];
            for (var i = 0; i < window.NLSDOC_SYMBOLS.length; i++) {
              var s = window.NLSDOC_SYMBOLS[i];
              var n = s.n.toLowerCase();
              if (n.indexOf(q) === 0) starts.push(s);
              else if (n.indexOf(q) > 0) contains.push(s);
              if (starts.length + contains.length > 400) break;
            }
            render(starts.concat(contains).slice(0, 30));
          }

          input.addEventListener('input', search);
          input.addEventListener('focus', search);
          document.addEventListener('click', function (e) {
            if (e.target !== input && !list.contains(e.target)) list.hidden = true;
          });
          input.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') { list.hidden = true; input.blur(); }
            if (e.key === 'Enter') {
              var first = list.querySelector('a');
              if (first && !list.hidden) window.location.href = first.href;
            }
          });
          document.addEventListener('keydown', function (e) {
            if (e.key === '/' && document.activeElement !== input) {
              e.preventDefault();
              input.focus();
              input.select();
            }
          });
        })();
        """;
}
