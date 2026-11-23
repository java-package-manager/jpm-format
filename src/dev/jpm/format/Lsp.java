package dev.jpm.format;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.Fix;
import dev.jpm.format.api.Lint;
import dev.jpm.format.api.Severity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code jpm format lsp} ({@code jpm-format lsp}): a language server over stdio for VS Code, Zed, Neovim and
 * other editors. It publishes findings as diagnostics while you type, formats documents and selections, and
 * offers each finding's fix as a quick fix.
 */
final class Lsp {
    private final InputStream in;
    private final OutputStream out;
    private final Map<String, String> docs = new ConcurrentHashMap<>();
    private final Map<String, List<Finding>> findings = new ConcurrentHashMap<>();
    private Workspace ws;
    private Path root;
    private boolean shutdown;

    Lsp(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    int run() {
        try {
            while (true) {
                Map<String, Object> msg = read();
                if (msg == null) return shutdown ? 0 : 1;
                Object id = msg.get("id");
                String method = (String) msg.get("method");
                if (method == null) continue;
                try {
                    Object result = handle(method, params(msg));
                    if (method.equals("exit")) return shutdown ? 0 : 1;
                    if (id != null) respond(id, result, null);
                } catch (FormatException | IllegalArgumentException e) {
                    if (id != null) respond(id, null, error(-32603, e.getMessage()));
                    else log(e.getMessage());
                } catch (UnsupportedOperationException e) {
                    if (id != null) respond(id, null, error(-32601, "Unknown method " + method));
                }
            }
        } catch (IOException e) {
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> msg) {
        Object p = msg.get("params");
        return p instanceof Map ? (Map<String, Object>) p : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Object handle(String method, Map<String, Object> p) {
        switch (method) {
            case "initialize" -> {
                Object uri = p.get("rootUri");
                root = uri instanceof String s
                        ? Path.of(URI.create(s))
                        : Path.of("").toAbsolutePath();
                root = Project.findRoot(root);
                reload();
                Map<String, Object> caps = new LinkedHashMap<>();
                caps.put(
                        "textDocumentSync",
                        Map.of("openClose", true, "change", 1, "save", Map.of("includeText", false)));
                caps.put("documentFormattingProvider", true);
                caps.put("documentRangeFormattingProvider", true);
                caps.put("codeActionProvider", Map.of("codeActionKinds", List.of("quickfix", "source.fixAll")));
                return Map.of(
                        "capabilities", caps, "serverInfo", Map.of("name", "jpm-format", "version", Main.VERSION));
            }
            case "initialized", "$/cancelRequest", "$/setTrace", "workspace/didChangeConfiguration" -> {
                return null;
            }
            case "shutdown" -> {
                shutdown = true;
                return null;
            }
            case "exit" -> {
                return null;
            }
            case "textDocument/didOpen" -> {
                Map<String, Object> td = (Map<String, Object>) p.get("textDocument");
                docs.put((String) td.get("uri"), (String) td.get("text"));
                lint((String) td.get("uri"));
                return null;
            }
            case "textDocument/didChange" -> {
                String uri = uri(p);
                List<Object> changes = (List<Object>) p.get("contentChanges");
                Map<String, Object> last = (Map<String, Object>) changes.get(changes.size() - 1);
                docs.put(uri, (String) last.get("text"));
                lint(uri);
                return null;
            }
            case "textDocument/didSave" -> {
                String uri = uri(p);
                String name = Path.of(URI.create(uri)).getFileName().toString();
                if (name.equals("project.jsonc") || name.equals(Config.FILE) || uri.contains("/rules/")) {
                    reload();
                    for (String u : docs.keySet()) lint(u);
                }
                return null;
            }
            case "textDocument/didClose" -> {
                String uri = uri(p);
                docs.remove(uri);
                findings.remove(uri);
                publish(uri, List.of(), "");
                return null;
            }
            case "textDocument/formatting" -> {
                String uri = uri(p);
                String text = docs.get(uri);
                if (text == null || !isJava(uri)) return List.of();
                Engine.Result r = ws.engine.run(rel(uri), text, Engine.Mode.FORMAT);
                return r.changed() ? List.of(edit(text, 0, text.length(), r.output())) : List.of();
            }
            case "textDocument/rangeFormatting" -> {
                String uri = uri(p);
                String text = docs.get(uri);
                if (text == null || !isJava(uri)) return List.of();
                Map<String, Object> range = (Map<String, Object>) p.get("range");
                Source src = new Source(rel(uri), text);
                int s = offset(src, (Map<String, Object>) range.get("start")),
                        e = offset(src, (Map<String, Object>) range.get("end"));
                String outText = ws.engine.formatRange(rel(uri), text, s, e);
                return outText.equals(text) ? List.of() : List.of(edit(text, 0, text.length(), outText));
            }
            case "textDocument/codeAction" -> {
                return codeActions(uri(p), (Map<String, Object>) p.get("range"));
            }
            default -> {
                if (method.startsWith("$/")) return null;
                throw new UnsupportedOperationException(method);
            }
        }
    }

    private void reload() {
        ws = Workspace.open(root, false, this::log);
    }

    @SuppressWarnings("unchecked")
    private static String uri(Map<String, Object> p) {
        return (String) ((Map<String, Object>) p.get("textDocument")).get("uri");
    }

    private static boolean isJava(String uri) {
        return uri.endsWith(".java");
    }

    private String rel(String uri) {
        Path p = uri.startsWith("file:") ? Path.of(URI.create(uri)) : Path.of(uri);
        return Config.rel(root, p);
    }

    private void lint(String uri) {
        String text = docs.get(uri);
        if (text == null || !isJava(uri)) return;
        String rel = rel(uri);
        if (ws.config.ignored(rel)) {
            publish(uri, List.of(), text);
            return;
        }
        Engine.Result r = ws.engine.run(rel, text, Engine.Mode.CHECK);
        findings.put(uri, r.findings());
        publish(uri, r.findings(), text);
    }

    private void publish(String uri, List<Finding> fs, String text) {
        Source src = new Source(uri, text);
        List<Object> diags = new ArrayList<>();
        for (Finding f : fs) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("range", range(src, f.start(), f.end()));
            d.put("severity", f.severity() == Severity.ERROR ? 1 : 2);
            d.put("source", "jpm-format");
            d.put("code", f.category() + "/" + f.rule());
            Lint rule = ws.registry.rules.get(f.rule());
            if (rule != null && rule.docs() != null) d.put("codeDescription", Map.of("href", rule.docs()));
            d.put("message", f.message());
            diags.add(d);
        }
        notify("textDocument/publishDiagnostics", Map.of("uri", uri, "diagnostics", diags));
    }

    private List<Object> codeActions(String uri, Map<String, Object> range) {
        String text = docs.get(uri);
        List<Object> out = new ArrayList<>();
        if (text == null) return out;
        Source src = new Source(uri, text);
        int s = offset(src, cast(range.get("start"))), e = offset(src, cast(range.get("end")));
        for (Finding f : findings.getOrDefault(uri, List.of())) {
            if (f.fix() == null || f.end() < s || f.start() > e) continue;
            List<Object> edits = new ArrayList<>();
            for (Fix.Edit ed : f.fix().edits()) edits.add(edit(text, ed.start(), ed.end(), ed.replacement()));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("title", f.fix().description() + " (" + f.rule() + ")");
            a.put("kind", "quickfix");
            a.put("edit", Map.of("changes", Map.of(uri, edits)));
            out.add(a);
        }
        Engine.Result r = ws.engine.run(rel(uri), text, Engine.Mode.FORMAT);
        if (r.changed()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("title", "Format and apply safe fixes (jpm format)");
            a.put("kind", "source.fixAll");
            a.put("edit", Map.of("changes", Map.of(uri, List.of(edit(text, 0, text.length(), r.output())))));
            out.add(a);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> edit(String text, int s, int e, String replacement) {
        return Map.of("range", range(new Source("", text), s, e), "newText", replacement);
    }

    private static Map<String, Object> range(Source src, int s, int e) {
        return Map.of("start", pos(src, s), "end", pos(src, e));
    }

    /**
     * LSP positions count UTF-16 units, as Java strings do.
     */
    private static Map<String, Object> pos(Source src, int offset) {
        int line = src.line(offset);
        return Map.of("line", line - 1, "character", offset - src.lineStart(line));
    }

    private static int offset(Source src, Map<String, Object> pos) {
        int line = ((Number) pos.get("line")).intValue() + 1;
        int ch = ((Number) pos.get("character")).intValue();
        return Math.min(src.text.length(), src.lineStart(line) + ch);
    }

    private void log(String msg) {
        notify("window/logMessage", Map.of("type", 2, "message", msg));
    }

    private static Map<String, Object> error(int code, String msg) {
        return Map.of("code", code, "message", msg == null ? "error" : msg);
    }

    private Map<String, Object> read() throws IOException {
        int length = -1;
        while (true) {
            String line = readLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(line.substring(c + 1).trim());
            }
        }
        if (length < 0) throw new IOException("message without Content-Length");
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return Json.object(new String(body, StandardCharsets.UTF_8), "LSP message");
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.US_ASCII);
    }

    private void respond(Object id, Object result, Map<String, Object> error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        if (error != null) m.put("error", error);
        else m.put("result", result);
        send(m);
    }

    private void notify(String method, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.put("params", params);
        send(m);
    }

    private synchronized void send(Map<String, Object> m) {
        byte[] body = Json.write(m).getBytes(StandardCharsets.UTF_8);
        try {
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
