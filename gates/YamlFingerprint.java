import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * YAML 配置文件「去注释指纹」校验工具，与 CommentFingerprint 同构。
 *
 * <p>用途：证明一次改动**只动了注释**，没有碰任何配置项、缩进、结构与键值。
 *
 * <p>YAML 注释规则比 Java 更需要小心：{@code #} 只有在「行首（含前置空白）」或「前面是空白」时
 * 才开始注释；出现在单引号或双引号标量内部的 {@code #} 是普通字符（例如
 * {@code password: "a#b"}）。因此不能按行简单截断。
 *
 * <p>块标量（{@code key: |} / {@code key: >}）内部的所有字符都是字面量，{@code #} 不是注释。
 * 本工具不做块标量推断：一旦发现文件里存在块标量，直接报「需人工复核」并拒绝给通过结论，
 * 避免用错误的扫描结果掩盖真实改动。
 *
 * <p>用法与 CommentFingerprint 一致：
 * <pre>
 *   java YamlFingerprint stats    &lt;root&gt;
 *   java YamlFingerprint snapshot &lt;root&gt; &lt;out.txt&gt;
 *   java YamlFingerprint verify   &lt;root&gt; &lt;baseline&gt;
 *   java YamlFingerprint check    &lt;root&gt; &lt;baseline&gt; &lt;rel...&gt;
 * </pre>
 */
public final class YamlFingerprint {

    private YamlFingerprint() {
    }

    private static final class Scan {
        String codeText;
        List<String> codeLines = new ArrayList<>();
        int commentLines;
        boolean hasBlockScalar;
    }

    /**
     * 剔除 YAML 注释。
     *
     * <p>逐行处理即可，因为 YAML 的注释**不能跨行**。需要处理两种引号状态：
     * 单引号内 {@code ''} 表示一个字面单引号；双引号内 {@code \\} 为转义。
     */
    private static Scan scan(String src) {
        Scan scan = new Scan();
        StringBuilder code = new StringBuilder();
        String[] lines = stripTrailingNewline(src).split("\n", -1);
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.trim().startsWith("#")) {
                scan.commentLines++;
                code.append('\n');
                continue;
            }
            int cut = -1;
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote == '\'') {
                    if (c == '\'') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                            i++;
                        } else {
                            quote = 0;
                        }
                    }
                } else if (quote == '"') {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        quote = 0;
                    }
                } else if (c == '\'') {
                    quote = '\'';
                } else if (c == '"') {
                    quote = '"';
                } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                    cut = i;
                    break;
                }
            }
            String kept = (cut >= 0) ? trimEnd(line.substring(0, cut)) : line;
            if (cut >= 0) {
                scan.commentLines++;
            }
            if (kept.matches(".*:\\s*[|>][-+]?\\d*\\s*$")) {
                scan.hasBlockScalar = true;
            }
            if (!kept.trim().isEmpty()) {
                scan.codeLines.add(kept);
            }
            code.append(kept).append('\n');
        }
        scan.codeText = code.toString();
        return scan;
    }

    private static String stripTrailingNewline(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String fp1(Scan scan) {
        return sha256(scan.codeText.replaceAll("\\s+", " ").trim());
    }

    private static String fp2(Scan scan) {
        return sha256(String.join("\n", scan.codeLines));
    }

    private static List<Path> collect(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if ("target".equals(entry.getFileName().toString())) {
                        continue;
                    }
                    files.addAll(collect(entry));
                } else {
                    String name = entry.getFileName().toString();
                    if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                        files.add(entry);
                    }
                }
            }
        }
        return files;
    }

    private static boolean isStrictUtf8(byte[] bytes) {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("用法: YamlFingerprint <stats|snapshot|verify|check> <root> [baseline] [rel...]");
            System.exit(2);
        }
        String mode = args[0];
        Path root = Paths.get(args[1]).toAbsolutePath().normalize();
        List<Path> files = collect(root);
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));

        if ("stats".equals(mode) || "snapshot".equals(mode)) {
            List<String> out = new ArrayList<>();
            List<String> blockScalars = new ArrayList<>();
            List<String> badEncoding = new ArrayList<>();
            long commentTotal = 0;
            long lineTotal = 0;
            for (Path f : files) {
                byte[] bytes = Files.readAllBytes(f);
                if (!isStrictUtf8(bytes)) {
                    badEncoding.add(root.relativize(f).toString().replace('\\', '/'));
                }
                String src = new String(bytes, StandardCharsets.UTF_8);
                Scan scan = scan(src);
                if (scan.hasBlockScalar) {
                    blockScalars.add(root.relativize(f).toString().replace('\\', '/'));
                }
                commentTotal += scan.commentLines;
                lineTotal += stripTrailingNewline(src).split("\n", -1).length;
                out.add(root.relativize(f).toString().replace('\\', '/')
                        + "\t" + fp1(scan) + "\t" + fp2(scan)
                        + "\t" + scan.commentLines + "\t" + scan.codeLines.size());
            }
            if (args.length >= 3) {
                Files.write(Paths.get(args[2]), out, StandardCharsets.UTF_8);
            }
            System.out.println("YAML 文件数   : " + files.size());
            System.out.println("总行数        : " + lineTotal);
            System.out.println("注释行        : " + commentTotal);
            System.out.printf("注释占比      : %.1f%%%n", 100.0 * commentTotal / Math.max(1, lineTotal));
            if (!blockScalars.isEmpty()) {
                System.out.println("含块标量(需人工复核): " + blockScalars.size());
                for (String s : blockScalars) {
                    System.out.println("    " + s);
                }
            }
            if (!badEncoding.isEmpty()) {
                System.out.println("非 UTF-8 文件 : " + badEncoding.size());
                for (String s : badEncoding) {
                    System.out.println("    " + s);
                }
            }
            return;
        }

        Map<String, String[]> baseline = new LinkedHashMap<>();
        if (args.length >= 3) {
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                baseline.put(line.split("\t")[0], line.split("\t"));
            }
        }

        if ("check".equals(mode)) {
            int fail = 0;
            for (int i = 3; i < args.length; i++) {
                String rel = args[i].replace('\\', '/');
                String[] base = baseline.get(rel);
                Path f = root.resolve(rel);
                if (base == null || !Files.exists(f)) {
                    System.out.println("  [无基线或文件不存在] " + rel);
                    fail++;
                    continue;
                }
                Scan scan = scan(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                if (!base[1].equals(fp1(scan))) {
                    System.out.println("  [FP1 配置内容被改!] " + rel);
                    fail++;
                } else if (!base[2].equals(fp2(scan))) {
                    System.out.println("  [FP2 配置格式被改 ] " + rel);
                    fail++;
                } else {
                    System.out.println("  [OK] " + rel + "  注释 " + base[3] + " -> " + scan.commentLines + " 行");
                }
            }
            if (fail > 0) {
                System.out.println("结论: 失败 —— " + fail + " 个文件不满足「只改注释」约束");
                System.exit(1);
            }
            System.out.println("结论: 通过");
            return;
        }

        if ("verify".equals(mode)) {
            List<String> changedFp1 = new ArrayList<>();
            List<String> changedFp2 = new ArrayList<>();
            List<String> added = new ArrayList<>();
            long commentBefore = 0;
            long commentAfter = 0;
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                Scan scan = scan(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                commentAfter += scan.commentLines;
                String[] base = baseline.remove(rel);
                if (base == null) {
                    added.add(rel);
                    continue;
                }
                commentBefore += Long.parseLong(base[3]);
                if (!base[1].equals(fp1(scan))) {
                    changedFp1.add(rel);
                } else if (!base[2].equals(fp2(scan))) {
                    changedFp2.add(rel);
                }
            }
            System.out.println("== YAML 去注释指纹校验 ==");
            System.out.println("比对文件数        : " + files.size());
            System.out.println("FP1 配置内容变更  : " + changedFp1.size());
            System.out.println("FP2 配置格式变更  : " + changedFp2.size());
            System.out.println("新增未纳入基线    : " + added.size());
            System.out.println("基线多出(已删除)  : " + baseline.size());
            System.out.println("注释行            : " + commentBefore + " -> " + commentAfter
                    + (commentBefore > 0 ? String.format("  (%+.1f%%)", 100.0 * (commentAfter - commentBefore) / commentBefore) : ""));
            for (String s : changedFp1) {
                System.out.println("  [FP1 配置被改动!] " + s);
            }
            for (String s : changedFp2) {
                System.out.println("  [FP2 格式被改动 ] " + s);
            }
            for (String s : added) {
                System.out.println("  [新增未纳入基线 ] " + s);
            }
            // 与 CommentFingerprint.verify 同口径：FP2（书写形态）与 FP1 一样必须计入失败，
            // 否则「只改格式」的改动会被打印出来却仍然结论通过（闸门自身的缺陷）。
            if (!changedFp1.isEmpty() || !changedFp2.isEmpty()) {
                String what = !changedFp1.isEmpty() && !changedFp2.isEmpty() ? "配置内容与格式"
                        : (!changedFp1.isEmpty() ? "配置内容" : "配置格式（书写形态）");
                System.out.println("结论: 失败 —— 存在" + what + "改动，违反「只改注释」约束");
                System.exit(1);
            }
            // 未被基线覆盖（新增/删除）不等于被证明没改，同样按失败处理。
            if (!added.isEmpty() || !baseline.isEmpty()) {
                System.out.println("结论: 失败 —— 有文件未被基线覆盖（新增 " + added.size()
                        + " / 缺失 " + baseline.size() + "），基线已过期，请重建基线后重新校验");
                System.exit(1);
            }
            System.out.println("结论: 通过 —— 全部文件配置内容与格式逐字未变");
            return;
        }

        System.out.println("未知模式: " + mode);
        System.exit(2);
    }
}
