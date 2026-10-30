import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冒烟数据只读快照与核对工具，保留旧类名及 smoke-watermark/1 文件兼容。
 * capture 记录部分表的行数、最大 id 和选定列；verify 报告与快照的差异。
 * 这些比较不能证明全域一致性，也不能识别哪些数据属于某次测试。
 * rollback 已停用：按 id 上界删除、覆盖余额和库存不能安全恢复业务状态。
 * 用法：java -cp <mysql-connector.jar> SmokeRollback.java capture|verify 主机 用户 口令 快照文件 [选项]
 * 选项：--user-name --user-id --items --shop --extra-tables --report
 * 退出码：0=核对通过或快照完成；1=核对有差异；2=参数、连接错误或旧回滚入口被拒绝。
 * 操作说明：docs/test-data-safety.md。连接使用只读模式，建议另配 SELECT 专用数据库账号。
 */
public class SmokeRollback {

    static final String FORMAT = "smoke-watermark/1";

    /** 只读采集的业务表，不代表完整业务影响集。 */
    static final String[] PRIMARY_TABLES = {
            "llmall-cart.cart",
            "llmall-trade.order",
            "llmall-trade.order_detail",
            "llmall-pay.pay_order",
            "llmall-user.balance_flow",
            "llmall-user.points_flow",
            "llmall-item.stock_flow",
            "llmall-content.blog",
            "llmall-content.blog_comments",
            "llmall-content.blog_like",
            "llmall-content.feed_inbox",
            "llmall-search.search_history"
    };

    /** 消费幂等记录与消息日志也是业务证据，只做读取。 */
    static final String[] DERIVED_TABLES = {
            "llmall-shop.consume_record",
            "llmall-search.consume_record",
            "llmall-shop.message_log",
            "llmall-content.message_log"
    };

    /** 保守的默认值：全部来自 MallE2E 脚本里硬编码的演示数据标识。 */
    static final String DEFAULT_USER_NAME = "Jack";
    static final long DEFAULT_USER_ID = 1L;
    static final String DEFAULT_ITEMS = "900013,900014,900015,900021";
    static final long DEFAULT_SHOP_ID = 1L;

    static final List<String> REPORT = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // 在解析凭据、读取快照或连接 JDBC 前拒绝旧回滚命令。
        if (args.length > 0 && "rollback".equals(args[0])) {
            System.err.println("CLEANUP_DISABLED: rollback is retired; see docs/test-data-safety.md");
            System.exit(2);
        }
        String host = null, user = null, pw = null, file = null, mode = null;
        String userName = DEFAULT_USER_NAME;
        long userId = DEFAULT_USER_ID;
        String itemsArg = DEFAULT_ITEMS;
        String extraArg = "";
        long shopId = DEFAULT_SHOP_ID;
        String reportPath = null;

        if (args.length >= 5) {
            mode = args[0];
            host = args[1];
            user = args[2];
            pw = args[3];
            file = args[4];
            for (int i = 5; i < args.length; i++) {
                String key = args[i];
                String val = i + 1 < args.length ? args[i + 1] : null;
                if (val == null) {
                    usage("参数 " + key + " 后面缺值");
                }
                if ("--user-name".equals(key)) {
                    userName = val;
                } else if ("--user-id".equals(key)) {
                    userId = Long.parseLong(val);
                } else if ("--items".equals(key)) {
                    itemsArg = val;
                } else if ("--shop".equals(key)) {
                    shopId = Long.parseLong(val);
                } else if ("--extra-tables".equals(key)) {
                    extraArg = val;
                } else if ("--report".equals(key)) {
                    reportPath = val;
                } else {
                    usage("未知参数 " + key);
                }
                i++;
            }
        } else {
            usage(null);
        }

        if (!"capture".equals(mode) && !"verify".equals(mode)) {
            usage("未知模式 " + mode);
        }

        // 主机可以写成 host:port（与 gates/EsBulkLoad.java 同一口径）：压测/演示用容器库时
        // 宿主端口常常不是 3306（例如 docker compose 把 mysql 映射到 3307），不带端口时仍取 3306。
        String hostPort = host.indexOf(':') >= 0 ? host : (host + ":3306");
        String url = "jdbc:mysql://" + hostPort + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
                + "&connectTimeout=8000&socketTimeout=120000&useAffectedRows=true";

        int exit = 0;
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            c.setReadOnly(true);
            if ("capture".equals(mode)) {
                capture(c, host, userName, userId, itemsArg, shopId, extraArg, file);

            } else {
                boolean ok = verify(c, host, file);
                if (!ok) {
                    exit = 1;
                }
            }
        } catch (SQLException e) {
            System.out.println("连库失败：" + e.getMessage());
            System.exit(2);
        }

        for (String line : REPORT) {
            System.out.println(line);
        }
        if (reportPath != null && !REPORT.isEmpty()) {
            List<String> title = new ArrayList<>();
            title.add("# 冒烟数据只读核对报告");
            title.add("");
            title.addAll(REPORT);
            Files.write(Paths.get(reportPath), title, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("报告已写入：" + reportPath);
        }
        System.exit(exit);
    }

    static void usage(String problem) {
        if (problem != null) {
            System.out.println("参数错误：" + problem);
        }
        System.out.println("用法：");
        System.out.println("  java -cp <mysql-connector.jar> SmokeRollback.java capture  主机 用户 口令 水位线文件 [选项]");
        System.out.println("rollback 已停用；参见 docs/test-data-safety.md");
        System.out.println("  java -cp <mysql-connector.jar> SmokeRollback.java verify   主机 用户 口令 水位线文件 [选项]");
        System.out.println("选项：--user-name --user-id --items --shop --extra-tables --report");
        System.exit(2);
    }

    // capture：取水位线并快照被改写的统计列

    static void capture(Connection c, String host, String userName, long userId,
                        String itemsArg, long shopId, String extraArg, String file) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("createdAt", now());
        doc.put("host", host);
        doc.put("note", "只读采集的部分表与列快照；仅用于比较，不可用作完整业务回滚凭据");

        Map<String, Object> tables = new LinkedHashMap<>();
        for (String t : PRIMARY_TABLES) {
            tables.put(t, tableWatermark(c, t));
        }
        doc.put("tables", tables);

        Map<String, Object> derived = new LinkedHashMap<>();
        for (String t : DERIVED_TABLES) {
            if (tableExists(c, t)) {
                derived.put(t, tableWatermark(c, t));
            }
        }
        doc.put("derivedTables", derived);

        // 额外表（例如压测专用账号所在的 llmall-user.user / user_info / address）：
        // 额外表仅参与只读快照与比较。
        Map<String, Object> extra = new LinkedHashMap<>();
        if (extraArg != null && !extraArg.trim().isEmpty()) {
            for (String t : extraArg.split(",")) {
                String spec = t.trim();
                if (spec.isEmpty()) {
                    continue;
                }
                if (tableExists(c, spec.split(":")[0])) {
                    extra.put(spec, tableWatermark(c, spec));
                }
                else {
                    REPORT.add("额外表不存在，跳过：" + spec);
                }
            }
        }
        doc.put("extraTables", extra);

        List<Object> snaps = new ArrayList<>();
        snaps.add(snapshot(c, "llmall-user", "user", "username = " + q(userName)));
        snaps.add(snapshot(c, "llmall-user", "user_info", "user_id = " + userId));
        for (String s : itemsArg.split(",")) {
            long itemId = Long.parseLong(s.trim());
            if (itemId > 0) {
                snaps.add(snapshot(c, "llmall-item", "item", "id = " + itemId));
            }
        }
        snaps.add(snapshot(c, "llmall-shop", "shop", "id = " + shopId));
        doc.put("snapshots", snaps);

        Files.write(Paths.get(file), toJson(doc).getBytes(StandardCharsets.UTF_8));
        REPORT.add("水位线已写入：" + file);
        REPORT.add("");
        REPORT.add("| 表 | 行数 | 最大 id |");
        REPORT.add("|---|---|---|");
        for (Map.Entry<String, Object> e : tables.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> w = (Map<String, Object>) e.getValue();
            REPORT.add("| " + e.getKey() + " | " + w.get("rows") + " | " + w.get("maxId") + " |");
        }
        for (Map.Entry<String, Object> e : derived.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> w = (Map<String, Object>) e.getValue();
            REPORT.add("| " + e.getKey() + "（派生） | " + w.get("rows") + " | " + w.get("maxId") + " |");
        }
    }

    /** 保留旧方法签名，但任何直接调用同样拒绝，且不访问连接或文件。 */
    static void rollback(Connection c, String host, String file) throws Exception {
        throw new UnsupportedOperationException("CLEANUP_DISABLED: see docs/test-data-safety.md");
    }
    // verify：只读复核

    static boolean verify(Connection c, String host, String file) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new Json(new String(
                Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)).parseRoot();
        checkFormat(doc);

        boolean ok = true;
        REPORT.add("### 复核：行集合");
        REPORT.add("");
        REPORT.add("| 表 | 水位线 id | 水位线以上残留 | 当前行数 | 判定 |");
        REPORT.add("|---|---|---|---|---|");
        Map<String, Object> all = new LinkedHashMap<>();
        all.putAll(map(doc.get("tables")));
        all.putAll(map(doc.get("derivedTables")));
        all.putAll(map(doc.get("extraTables")));
        for (Map.Entry<String, Object> e : all.entrySet()) {
            String[] st = split(e.getKey().split(":")[0]);
            if (!tableExists(c, e.getKey().split(":")[0])) {
                REPORT.add("| " + e.getKey() + " | - | - | - | 跳过（表不存在） |");
                continue;
            }
            Map<String, Object> w = map(e.getValue());
            long maxId = num(w.get("maxId"));
            long above = count(c, st[0], st[1], quote(keyColumnOf(w)) + " > " + maxId);
            long rows = count(c, st[0], st[1], null);
            String verdict;
            if (above > 0) {
                verdict = "FAIL（仍有新增行）";
                ok = false;
            } else if (w.containsKey("rows") && num(w.get("rows")) != rows) {
                verdict = "FAIL（行数与水位线记录值不符）";
                ok = false;
            } else {
                verdict = "通过";
            }
            REPORT.add("| " + e.getKey() + " | " + maxId + " | " + above + " | " + rows + " | " + verdict + " |");
        }

        REPORT.add("");
        REPORT.add("### 复核：统计列");
        REPORT.add("");
        REPORT.add("| 行 | 列 | 期望 | 实际 | 判定 |");
        REPORT.add("|---|---|---|---|---|");
        for (Object o : list(doc.get("snapshots"))) {
            Map<String, Object> snap = map(o);
            String db = str(snap.get("db"));
            String tb = str(snap.get("table"));
            String where = str(snap.get("where"));
            Map<String, Object> values = map(snap.get("values"));
            Map<String, String> actual = readRow(c, db, tb, where, new ArrayList<>(values.keySet()));
            for (Map.Entry<String, Object> v : values.entrySet()) {
                String expect = v.getValue() == null ? null : str(v.getValue());
                String got = actual.get(v.getKey());
                boolean same = expect == null ? got == null : expect.equals(got);
                if (!same) {
                    ok = false;
                }
                REPORT.add("| " + db + "." + tb + " " + where + " | " + v.getKey() + " | "
                        + (expect == null ? "NULL" : expect) + " | " + (got == null ? "NULL" : got)
                        + " | " + (same ? "通过" : "FAIL") + " |");
            }
        }
        return ok;
    }

    // SQL 辅助方法

    /**
     * 取一张表的水位线。表名可以写成 {@code 库.表} 或 {@code 库.表:键列}：
     * 不是每张表都有 id（例如 llmall-user.user_info 的主键是 user_id），
     * 键列缺省为 id，写成 user_info:user_id 即可按 user_id 记水位线。
     */
    static Map<String, Object> tableWatermark(Connection c, String spec) throws SQLException {
        String key = keyColumnOf(spec);
        String[] st = split(spec.split(":")[0]);
        Map<String, Object> w = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*), IFNULL(MAX(" + quote(key) + "),0) FROM "
                     + quote(st[0]) + "." + quote(st[1]))) {
            rs.next();
            w.put("rows", rs.getLong(1));
            w.put("maxId", rs.getLong(2));
            if (!"id".equals(key)) {
                w.put("key", key);
            }
        }
        return w;
    }

    /** 从 "库.表:键列" 里取键列，缺省 id */
    static String keyColumnOf(String spec) {
        int i = spec.indexOf(':');
        return i < 0 ? "id" : spec.substring(i + 1).trim();
    }

    /** 取水位线条目里记录的键列（老水位线文件没有 key 字段，按 id 处理） */
    static String keyColumnOf(Map<String, Object> w) {
        Object k = w.get("key");
        return k == null ? "id" : String.valueOf(k);
    }

    static Map<String, Object> snapshot(Connection c, String db, String tb, String where) throws SQLException {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("db", db);
        snap.put("table", tb);
        snap.put("where", where);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String col : SNAPSHOT_COLUMNS(db, tb)) {
            values.put(col, null);
        }
        Map<String, String> row = readRow(c, db, tb, where, new ArrayList<>(values.keySet()));
        for (String col : values.keySet()) {
            values.put(col, row.get(col));
        }
        snap.put("values", values);
        return snap;
    }

    /**
     * 每类行要快照哪些列。只取冒烟的写路径会改动的那几列：
     * 余额（下单支付扣款）、积分（order.paid 发放）、库存与销量（下单扣、取消补）、
     * 门店评价数 / 评分 / 热度 / 最近评价时间 / 销量（评价事件与支付事件各改一部分）。
     */
    static List<String> SNAPSHOT_COLUMNS(String db, String tb) {
        List<String> cols = new ArrayList<>();
        if ("llmall-user".equals(db) && "user".equals(tb)) {
            cols.add("balance");
        } else if ("llmall-user".equals(db) && "user_info".equals(tb)) {
            cols.add("credits");
        } else if ("llmall-item".equals(db) && "item".equals(tb)) {
            cols.add("stock");
            cols.add("sold");
        } else if ("llmall-shop".equals(db) && "shop".equals(tb)) {
            cols.add("comments");
            cols.add("score");
            cols.add("sold");
            cols.add("hot_score");
            cols.add("last_review_time");
            cols.add("hot_update_time");
        }
        return cols;
    }

    static Map<String, String> readRow(Connection c, String db, String tb, String where, List<String> cols)
            throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(cols.get(i)));
        }
        sb.append(" FROM ").append(quote(db)).append('.').append(quote(tb)).append(" WHERE ").append(where);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sb.toString())) {
            if (!rs.next()) {
                return out;
            }
            for (int i = 0; i < cols.size(); i++) {
                out.put(cols.get(i), rs.getString(i + 1));
            }
        }
        return out;
    }

    static long count(Connection c, String db, String tb, String where) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + quote(db) + "." + quote(tb)
                + (where == null ? "" : " WHERE " + where);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static boolean tableExists(Connection c, String spec) throws SQLException {
        String[] st = split(spec);
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '"
                + st[0] + "' AND TABLE_NAME = '" + st[1] + "'";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    static String now() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    static String quote(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    static String q(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static String[] split(String spec) {
        int i = spec.indexOf('.');
        if (i < 0) {
            throw new IllegalArgumentException("表名要写成 库.表：" + spec);
        }
        return new String[]{spec.substring(0, i), spec.substring(i + 1)};
    }

    static void checkFormat(Map<String, Object> doc) {
        Object f = doc.get("format");
        if (f == null || !FORMAT.equals(str(f))) {
            System.out.println("水位线文件的 format 不是 " + FORMAT + "（读到：" + f + "），停下。");
            System.exit(2);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o == null ? new LinkedHashMap<>() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o == null ? new ArrayList<>() : (List<Object>) o;
    }

    static long num(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return Long.parseLong(str(o));
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // 极简 JSON（只服务于本工具的水位线文件）

    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(o, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void writeJson(Object o, StringBuilder sb, int indent) {
        String pad = spaces(indent);
        String pad2 = spaces(indent + 2);
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(pad2).append(esc(e.getKey())).append(": ");
                writeJson(e.getValue(), sb, indent + 2);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append(pad).append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                sb.append(pad2);
                writeJson(l.get(i), sb, indent + 2);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append(pad).append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            sb.append(esc(String.valueOf(o)));
        }
    }

    static String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }

    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parseRoot() {
            Object v = value();
            ws();
            if (i != s.length()) {
                throw new IllegalArgumentException("JSON 尾部有多余内容，位置 " + i);
            }
            return v;
        }

        private Object value() {
            ws();
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char ch = s.charAt(i);
            if (ch == '{') {
                return object();
            }
            if (ch == '[') {
                return array();
            }
            if (ch == '"') {
                return string();
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char ch = s.charAt(i++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch != '\\') {
                    sb.append(ch);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        sb.append(e);
                }
            }
            throw new IllegalArgumentException("字符串没有闭合");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) {
                throw new IllegalArgumentException("JSON 位置 " + start + " 处不是合法值");
            }
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException ignored) {
                    return Double.parseDouble(t);
                }
            }
            return Double.parseDouble(t);
        }

        private void expect(char ch) {
            ws();
            if (i >= s.length() || s.charAt(i) != ch) {
                throw new IllegalArgumentException("JSON 期望 '" + ch + "'，位置 " + i);
            }
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
