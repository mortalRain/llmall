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
 * 冒烟数据回滚工具：按一份"冒烟前水位线"文件，把冒烟跑完留下的新增行删掉，
 * 并把被冒烟改写的统计列复原，最后输出复核报告。
 *
 * 三种模式：
 *   capture  冒烟开跑前取水位线（每张表的 MAX(id) 与行数）并快照被改写的统计列，落成一份 JSON
 *   rollback 读这份 JSON：DELETE 水位线以上的行 + UPDATE 复原统计列，随后自动跑一遍复核
 *   verify   只做复核，不写库：水位线以上必须 0 行，统计列必须与快照逐值相等
 *
 * 为什么水位线管删除、快照管更新，两者不能互相替代：
 *   冒烟写进库里的东西分两类 —— 一类是纯新增（订单、流水、评价），只能靠"删掉本次新长出来的行"回到
 *   冒烟前的行集合，而这只有在事前记下自增 id 的上界才说得清"哪些是本次长的"；
 *   另一类是就地累加（余额、积分、库存、门店热度），行的 id 没变、只是列值被改写，
 *   删行既删不掉它也不会自己变回去，只能按事前快照写回原值。
 *
 * 连接串带 useAffectedRows=true：只认"真正发生变化的行数"。于是同一份水位数回滚第二遍时
 * UPDATE 命中 0 行，这正是幂等性的判据；不带这个参数则返回的是匹配行数，第二遍会报 1，看起来像没回滚干净。
 *
 * 用法：
 *   java -cp &lt;mysql-connector.jar&gt; SmokeRollback.java capture  主机 用户 口令 水位线文件 [选项]
 *   java -cp &lt;mysql-connector.jar&gt; SmokeRollback.java rollback 主机 用户 口令 水位线文件 [选项]
 *   java -cp &lt;mysql-connector.jar&gt; SmokeRollback.java verify   主机 用户 口令 水位线文件 [选项]
 *
 * 选项：
 *   --user-name 用户名      冒烟账号的用户名，默认 Jack（MallE2E 硬编码的演示账号）
 *   --user-id   用户 id     冒烟账号在 user_info 里的 user_id，默认 1
 *   --items     id 列表     冒烟会动到的商品 id，逗号分隔，默认 900013,900014,900015,900021
 *   --shop      id          冒烟会写评价的门店 id，默认 1
 *   --report    文件        把复核报告另存为 Markdown
 *
 * 退出码：0 = 全部通过；1 = 复核不通过；2 = 参数或连接错误。
 */
public class SmokeRollback {

    /** 水位线文件里 format 字段的值，用来挡住"拿错文件"这种误用。 */
    static final String FORMAT = "smoke-watermark/1";

    /**
     * 冒烟会追加行、且删除全部水位线以上记录即可复原的 12 张表。
     * 与 demo.ps1 里 -NoCleanup 分支记录的那份清单同源（都是 MallE2E 的写路径覆盖到的表）。
     */
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

    /**
     * 冒烟的业务表之外还会长出两类派生行：消费幂等占位（consume_record）与本地消息表（message_log）。
     * 它们不影响业务含义，但留着就会让"库里比冒烟前多了几行"这件事说不清，所以一并按水位线清掉。
     * 单独分一组是因为复核报告要把它们与 12 张业务表分开陈述，免得把"派生行"混进"业务行数"的口径里。
     */
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

    static final Map<String, String> COLUMN_TYPES = new LinkedHashMap<>();
    static final List<String> REPORT = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String host = null, user = null, pw = null, file = null, mode = null;
        String userName = DEFAULT_USER_NAME;
        long userId = DEFAULT_USER_ID;
        String itemsArg = DEFAULT_ITEMS;
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

        if (!"capture".equals(mode) && !"rollback".equals(mode) && !"verify".equals(mode)) {
            usage("未知模式 " + mode);
        }

        String url = "jdbc:mysql://" + host + ":3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
                + "&connectTimeout=8000&socketTimeout=120000&useAffectedRows=true";

        int exit = 0;
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            if ("capture".equals(mode)) {
                capture(c, host, userName, userId, itemsArg, shopId, file);
            } else if ("rollback".equals(mode)) {
                rollback(c, host, file);
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
            title.add("# 冒烟数据回滚报告");
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
        System.out.println("  java -cp <mysql-connector.jar> SmokeRollback.java rollback 主机 用户 口令 水位线文件 [选项]");
        System.out.println("  java -cp <mysql-connector.jar> SmokeRollback.java verify   主机 用户 口令 水位线文件 [选项]");
        System.out.println("选项：--user-name --user-id --items --shop --report");
        System.exit(2);
    }

    // capture：取水位线并快照被改写的统计列

    static void capture(Connection c, String host, String userName, long userId,
                        String itemsArg, long shopId, String file) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("createdAt", now());
        doc.put("host", host);
        doc.put("note", "冒烟开跑前的水位线与统计列快照；rollback 只删 id 大于这里的行、只把这些列写回这里的值");

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

    // rollback：删行 + 复原统计列 + 自检

    static void rollback(Connection c, String host, String file) throws Exception {
        Path p = Paths.get(file);
        if (!Files.exists(p)) {
            System.out.println("水位线文件不存在：" + file);
            System.exit(2);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new Json(new String(
                Files.readAllBytes(p), StandardCharsets.UTF_8)).parseRoot();
        checkFormat(doc);
        String docHost = str(doc.get("host"));
        if (docHost != null && !docHost.isEmpty() && !docHost.equals(host)) {
            System.out.println("水位线文件记录的库主机是 " + docHost + "，而本次连的是 " + host + " —— 停下。");
            System.exit(2);
        }

        REPORT.add("### 删除：水位线以上的新增行");
        REPORT.add("");
        REPORT.add("| 表 | 水位线 id | 本次删除 |");
        REPORT.add("|---|---|---|");
        long totalDeleted = 0;

        Map<String, Object> all = new LinkedHashMap<>();
        Map<String, Object> tables = map(doc.get("tables"));
        Map<String, Object> derived = map(doc.get("derivedTables"));
        all.putAll(tables);
        all.putAll(derived);

        c.setAutoCommit(false);
        try {
            for (Map.Entry<String, Object> e : all.entrySet()) {
                String[] st = split(e.getKey());
                if (!tableExists(c, e.getKey())) {
                    REPORT.add("| " + e.getKey() + " | - | 跳过（表不存在） |");
                    continue;
                }
                Map<String, Object> w = map(e.getValue());
                long maxId = num(w.get("maxId"));
                long n = deleteAbove(c, st[0], st[1], maxId);
                totalDeleted += n;
                REPORT.add("| " + e.getKey() + " | " + maxId + " | " + n + " |");
            }

            REPORT.add("");
            REPORT.add("### 更新：复原被冒烟改写的统计列");
            REPORT.add("");
            REPORT.add("| 行 | 列 | 写回值 | 实际变化行数 |");
            REPORT.add("|---|---|---|---|");
            long totalUpdated = 0;
            Map<String, List<String>> wantCols = new LinkedHashMap<>();
            for (Object o : list(doc.get("snapshots"))) {
                Map<String, Object> snap = map(o);
                String k = str(snap.get("db")) + "." + str(snap.get("table"));
                List<String> cols = wantCols.computeIfAbsent(k, x -> new ArrayList<>());
                cols.addAll(map(snap.get("values")).keySet());
            }
            for (Map.Entry<String, List<String>> e : wantCols.entrySet()) {
                String[] st = split(e.getKey());
                loadColumnTypes(c, st[0], st[1], e.getValue());
            }
            for (Object o : list(doc.get("snapshots"))) {
                Map<String, Object> snap = map(o);
                Object[] r = restore(c, snap);
                totalUpdated += (Long) r[1];
                REPORT.add("| " + r[0] + " | " + r[2] + " | " + r[3] + " | " + r[1] + " |");
            }
            c.commit();
            REPORT.add("");
            REPORT.add("合计：删除 " + totalDeleted + " 行，更新 " + totalUpdated + " 行（更新数按真实变化的行计）。");
        } catch (Exception ex) {
            c.rollback();
            System.out.println("回滚执行失败，已整体回滚：" + ex.getMessage());
            System.exit(1);
        } finally {
            c.setAutoCommit(true);
        }

        REPORT.add("");
        boolean ok = verify(c, host, file);
        REPORT.add("");
        REPORT.add(ok ? "结论：复核通过（水位线以上 0 行，统计列与快照逐值相等）"
                : "结论：复核不通过，见上方 FAIL 行");
        if (!ok) {
            System.exit(1);
        }
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
        for (Map.Entry<String, Object> e : all.entrySet()) {
            String[] st = split(e.getKey());
            if (!tableExists(c, e.getKey())) {
                REPORT.add("| " + e.getKey() + " | - | - | - | 跳过（表不存在） |");
                continue;
            }
            Map<String, Object> w = map(e.getValue());
            long maxId = num(w.get("maxId"));
            long above = count(c, st[0], st[1], "id > " + maxId);
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

    static Map<String, Object> tableWatermark(Connection c, String spec) throws SQLException {
        String[] st = split(spec);
        Map<String, Object> w = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*), IFNULL(MAX(id),0) FROM "
                     + quote(st[0]) + "." + quote(st[1]))) {
            rs.next();
            w.put("rows", rs.getLong(1));
            w.put("maxId", rs.getLong(2));
        }
        return w;
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

    static Object[] restore(Connection c, Map<String, Object> snap) throws SQLException {
        String db = str(snap.get("db"));
        String tb = str(snap.get("table"));
        String where = str(snap.get("where"));
        Map<String, Object> values = map(snap.get("values"));
        StringBuilder set = new StringBuilder();
        List<String> cols = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String col = e.getKey();
            cols.add(col);
            String lit = literal(db, tb, col, e.getValue() == null ? null : str(e.getValue()));
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append(quote(col)).append(" = ").append(lit);
        }
        String sql = "UPDATE " + quote(db) + "." + quote(tb) + " SET " + set + " WHERE " + where;
        int n;
        try (Statement s = c.createStatement()) {
            n = s.executeUpdate(sql);
        }
        StringBuilder shown = new StringBuilder();
        for (String col : cols) {
            if (shown.length() > 0) {
                shown.append(", ");
            }
            shown.append(col).append('=').append(values.get(col) == null ? "NULL" : values.get(col));
        }
        return new Object[]{db + "." + tb + " " + where, (long) n, cols.size() + " 列", shown.toString()};
    }

    static long deleteAbove(Connection c, String db, String tb, long maxId) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate("DELETE FROM " + quote(db) + "." + quote(tb) + " WHERE id > " + maxId);
        }
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

    static boolean isNumeric(String db, String tb, String col) throws SQLException {
        String key = db + "." + tb + "." + col;
        String type = COLUMN_TYPES.get(key);
        if (type == null) {
            throw new SQLException("取不到列类型：" + key);
        }
        return type.matches("(?i)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|float|double|real|bit|year)");
    }

    static void loadColumnTypes(Connection c, String db, String tb, List<String> cols) throws SQLException {
        StringBuilder in = new StringBuilder();
        for (String col : cols) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append('\'').append(col).append('\'');
        }
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '"
                + db + "' AND TABLE_NAME = '" + tb + "' AND COLUMN_NAME IN (" + in + ")";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                COLUMN_TYPES.put(db + "." + tb + "." + rs.getString(1), rs.getString(2));
            }
        }
    }

    static String literal(String db, String tb, String col, String value) throws SQLException {
        String key = db + "." + tb + "." + col;
        if (!COLUMN_TYPES.containsKey(key)) {
            throw new SQLException("未知列类型：" + key + "（快照里的库表列与当前库对不上）");
        }
        if (value == null) {
            return "NULL";
        }
        if (isNumeric(db, tb, col)) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
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
