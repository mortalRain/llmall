import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Java 源文件「去注释指纹」校验工具。
 *
 * <p>用途：证明一次改动**只动了注释**，没有碰任何代码逻辑、结构、命名。
 *
 * <p>对每个文件计算两个指纹：
 * <ul>
 *   <li>FP1 —— 词法级 token 流：用状态机扫描，剔除 // 行注释、块注释与 Javadoc（正确跳过字符串/字符
 *       字面量内部的注释符号），保留全部代码字符，再把连续空白压缩为单个空格后取 SHA256。
 *       它对换行与缩进不敏感 —— 指纹相同即代表「代码语义逐 token 未变」。</li>
 *   <li>FP2 —— 代码行序列：剔除注释后逐行 trimEnd、丢弃空行，再取 SHA256。
 *       它对格式敏感 —— 指纹相同即代表「连代码的换行与书写形态都没变」，是比 FP1 更严的约束。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   java CommentFingerprint stats    &lt;root&gt; [out.txt]   # 统计注释占比，可存基线
 *   java CommentFingerprint snapshot &lt;root&gt; &lt;out.txt&gt;   # 写基线指纹文件
 *   java CommentFingerprint verify   &lt;root&gt; &lt;baseline&gt;  # 与基线比对，不一致则退出码 1
 * </pre>
 * 指纹文件格式：相对路径 \t FP1 \t FP2 \t 注释行数 \t 代码行数
 */
public final class CommentFingerprint {

    private static final char NORMAL = 'N';
    private static final char LINE_COMMENT = 'L';
    private static final char BLOCK_COMMENT = 'B';
    private static final char STRING = 'S';
    private static final char CHAR = 'C';

    private CommentFingerprint() {
    }

    /** 扫描结果：剔注释后的代码文本、代码行序列、注释行数。 */
    private static final class Scan {
        final String codeText;
        final List<String> codeLines;
        final int commentOrBlankLines;

        Scan(String codeText, List<String> codeLines, int commentOrBlankLines) {
            this.codeText = codeText;
            this.codeLines = codeLines;
            this.commentOrBlankLines = commentOrBlankLines;
        }
    }

    /**
     * 用状态机扫描源码，剔除全部注释。
     *
     * <p>关键点：注释符号出现在字符串或字符字面量里时**不是注释**（例如 {@code "http://x"} 与
     * {@code '/'}），因此不能简单地按行正则删除。字符串与字符字面量内需要处理 {@code \\} 转义，
     * 否则 {@code "\""} 会被误判为字符串结束。
     *
     * <p>块注释内部保留换行符，使剔除后的文本行号与原文件保持对齐，便于定位差异。
     */
    private static Scan scan(String src) {
        StringBuilder out = new StringBuilder(src.length());
        char state = NORMAL;
        int n = src.length();
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char next = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL:
                    if (c == '/' && next == '/') {
                        state = LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = BLOCK_COMMENT;
                        i++;
                    } else if (c == '"') {
                        state = STRING;
                        out.append(c);
                    } else if (c == '\'') {
                        state = CHAR;
                        out.append(c);
                    } else {
                        out.append(c);
                    }
                    break;
                case LINE_COMMENT:
                    if (c == '\n') {
                        state = NORMAL;
                        out.append('\n');
                    }
                    break;
                case BLOCK_COMMENT:
                    if (c == '*' && next == '/') {
                        state = NORMAL;
                        i++;
                    } else if (c == '\n') {
                        out.append('\n');
                    }
                    break;
                case STRING:
                case CHAR:
                    out.append(c);
                    if (c == '\\') {
                        // 转义序列：连同下一个字符一起原样吞掉，避免 "\"" 被当成字符串结束
                        if (i + 1 < n) {
                            out.append(src.charAt(i + 1));
                            i++;
                        }
                    } else if ((state == STRING && c == '"') || (state == CHAR && c == '\'')) {
                        state = NORMAL;
                    }
                    break;
                default:
                    break;
            }
        }

        String codeText = out.toString();
        List<String> codeLines = new ArrayList<>();
        int commentOrBlank = 0;
        for (String line : codeText.split("\n", -1)) {
            String trimmed = trimEnd(line);
            if (trimmed.isEmpty()) {
                commentOrBlank++;
            } else {
                codeLines.add(trimmed);
            }
        }
        return new Scan(codeText, codeLines, commentOrBlank);
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 去掉末尾的换行符，使行数统计不把"文件末尾的换行"误算成一行。 */
    private static String stripTrailingNewline(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 统计行数：总行 / 空白行 / 代码行 / 注释行。
     *
     * <p>注释行 = 总行 - 空白行 - 代码行。**不能**用"剔除注释后为空的行"直接当注释行 ——
     * 那样会把本来就是空行的行也算成注释行，得出"一个零注释文件有 11 行注释"这种结论。
     */
    private static final class LineStats {
        final int total;
        final int blank;
        final int code;
        final int comment;

        LineStats(int total, int blank, int code) {
            this.total = total;
            this.blank = blank;
            this.code = code;
            this.comment = total - blank - code;
        }
    }

    private static LineStats lineStats(String src, Scan scan) {
        String[] lines = stripTrailingNewline(src).split("\n", -1);
        int blank = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                blank++;
            }
        }
        return new LineStats(lines.length, blank, scan.codeLines.size());
    }

    /**
     * 校验文件能否严格按 UTF-8 解码。
     *
     * <p>批量改写前必须先过这一关：若某个源文件其实是 GBK，按 UTF-8 读会得到替换字符，
     * 再按 UTF-8 写回就会**静默损坏中文注释**，且指纹校验也发现不了（两边都错）。
     */
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

    /** FP1：全部代码字符，连续空白压缩为单空格后取指纹。 */
    private static String fp1(Scan scan) {
        return sha256(scan.codeText.replaceAll("\\s+", " ").trim());
    }

    /** FP2：代码行序列（注释剔除、行尾空白去除、空行丢弃）取指纹。 */
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
                } else if (entry.getFileName().toString().endsWith(".java")) {
                    files.add(entry);
                }
            }
        }
        return files;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("用法: CommentFingerprint <stats|snapshot|verify> <root> [baseline.txt]");
            System.exit(2);
        }
        String mode = args[0];
        Path root = Paths.get(args[1]).toAbsolutePath().normalize();
        List<Path> files = collect(root);
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));

        if ("snapshot".equals(mode) || "stats".equals(mode)) {
            List<String> out = new ArrayList<>();
            List<String> badEncoding = new ArrayList<>();
            long commentTotal = 0;
            long codeTotal = 0;
            long lineTotal = 0;
            for (Path f : files) {
                byte[] bytes = Files.readAllBytes(f);
                if (!isStrictUtf8(bytes)) {
                    badEncoding.add(root.relativize(f).toString().replace('\\', '/'));
                }
                String src = new String(bytes, StandardCharsets.UTF_8);
                Scan scan = scan(src);
                LineStats st = lineStats(src, scan);
                commentTotal += st.comment;
                codeTotal += st.code;
                lineTotal += st.total;
                out.add(root.relativize(f).toString().replace('\\', '/')
                        + "\t" + fp1(scan) + "\t" + fp2(scan)
                        + "\t" + st.comment + "\t" + st.code);
            }
            if (args.length >= 3) {
                Files.write(Paths.get(args[2]), out, StandardCharsets.UTF_8);
            }
            System.out.println("文件数        : " + files.size());
            System.out.println("总行数        : " + lineTotal);
            System.out.println("注释行        : " + commentTotal);
            System.out.println("代码行        : " + codeTotal);
            System.out.printf("注释占比      : %.1f%%%n", 100.0 * commentTotal / Math.max(1, commentTotal + codeTotal));
            if (!badEncoding.isEmpty()) {
                System.out.println("非 UTF-8 文件 : " + badEncoding.size() + " （改写前必须处理，否则中文会被写坏）");
                for (String s : badEncoding) {
                    System.out.println("    " + s);
                }
            }
            return;
        }

        if ("verify".equals(mode)) {
            if (args.length < 3) {
                System.out.println("verify 需要提供基线文件");
                System.exit(2);
            }
            Map<String, String[]> baseline = new LinkedHashMap<>();
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                baseline.put(parts[0], parts);
            }
            List<String> changedFp1 = new ArrayList<>();
            List<String> changedFp2 = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> added = new ArrayList<>();
            long commentBefore = 0;
            long commentAfter = 0;
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                Scan scan = scan(src);
                LineStats st = lineStats(src, scan);
                commentAfter += st.comment;
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
            missing.addAll(baseline.keySet());

            System.out.println("== 去注释指纹校验 ==");
            System.out.println("比对文件数        : " + files.size());
            System.out.println("FP1 代码语义变更  : " + changedFp1.size());
            System.out.println("FP2 代码格式变更  : " + changedFp2.size());
            System.out.println("基线缺失(新文件)  : " + added.size());
            System.out.println("基线多出(已删除)  : " + missing.size());
            System.out.println("注释行            : " + commentBefore + " -> " + commentAfter
                    + (commentBefore > 0 ? String.format("  (%+.1f%%)", 100.0 * (commentAfter - commentBefore) / commentBefore) : ""));
            for (String s : changedFp1) {
                System.out.println("  [FP1 代码被改动!] " + s);
            }
            for (String s : changedFp2) {
                System.out.println("  [FP2 格式被改动 ] " + s);
            }
            for (String s : added) {
                System.out.println("  [新增未纳入基线 ] " + s);
            }
            for (String s : missing) {
                System.out.println("  [基线文件已删除 ] " + s);
            }
            // 【为什么这里必须同时看 FP1 与 FP2】verify 是**全量验收**（accept.ps1）用的模式。
            // 原实现只判 changedFp1：于是「只改书写形态」（两行合成一行、改缩进、吞掉行尾换行）
            // 会被打印成 [FP2 格式被改动] 却仍然输出「结论: 通过」并 exit 0 ——
            // 而那正是最危险的一类损伤：编译通过、语义可能已经变了，验收却给了绿灯。
            // 这也与 check 模式的口径不一致（check 里两个都计入 fail），属闸门自身的缺陷。
            if (!changedFp1.isEmpty() || !changedFp2.isEmpty()) {
                String what = !changedFp1.isEmpty() && !changedFp2.isEmpty() ? "代码语义与格式"
                        : (!changedFp1.isEmpty() ? "代码语义" : "代码格式（书写形态）");
                System.out.println("结论: 失败 —— 存在" + what + "改动，违反「只改注释」约束");
                System.exit(1);
            }
            // 新增/删除的文件意味着「未被基线覆盖」——**未被覆盖不等于被证明没改**，故同样按失败处理。
            if (!added.isEmpty() || !missing.isEmpty()) {
                System.out.println("结论: 失败 —— 有文件未被基线覆盖（新增 " + added.size()
                        + " / 缺失 " + missing.size() + "），基线已过期，请重建基线后重新校验");
                System.exit(1);
            }
            System.out.println("结论: 通过 —— 全部文件代码语义与格式逐 token 未变");
            return;
        }

        if ("check".equals(mode)) {
            if (args.length < 4) {
                System.out.println("check 需要提供基线文件与至少一个相对路径");
                System.exit(2);
            }
            Map<String, String[]> baseline = new LinkedHashMap<>();
            for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                baseline.put(parts[0], parts);
            }
            int fail = 0;
            for (int i = 3; i < args.length; i++) {
                String rel = args[i].replace('\\', '/');
                String[] base = baseline.get(rel);
                Path f = root.resolve(rel);
                if (base == null) {
                    System.out.println("  [无基线] " + rel);
                    fail++;
                    continue;
                }
                if (!Files.exists(f)) {
                    System.out.println("  [文件不存在] " + rel);
                    fail++;
                    continue;
                }
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                Scan scan = scan(src);
                LineStats st = lineStats(src, scan);
                boolean ok1 = base[1].equals(fp1(scan));
                boolean ok2 = base[2].equals(fp2(scan));
                if (!ok1) {
                    System.out.println("  [FP1 代码语义被改!] " + rel);
                    fail++;
                } else if (!ok2) {
                    System.out.println("  [FP2 代码格式被改 ] " + rel);
                    fail++;
                } else {
                    System.out.println("  [OK] " + rel + "  注释 " + base[3] + " -> " + st.comment
                            + " 行，代码 " + base[4] + " -> " + st.code + " 行");
                }
            }
            if (fail > 0) {
                System.out.println("结论: 失败 —— " + fail + " 个文件不满足「只改注释」约束");
                System.exit(1);
            }
            System.out.println("结论: 通过");
            return;
        }

        System.out.println("未知模式: " + mode);
        System.exit(2);
    }
}
