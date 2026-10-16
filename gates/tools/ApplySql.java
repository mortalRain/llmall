import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行一个 .sql 文件里的语句（本机没有 mysql 客户端时的替代）。
 *
 * 【安全约束（重要）】本工具只接受**纯 ALTER TABLE 语句**——遇到任何非 ALTER TABLE 开头的语句立即中止并退出，
 * 以免误把建表/写数据脚本跑起来。这是为了满足"除 COMMENT 对齐脚本外，任何写库先停下问"的红线。
 *
 * 用法：java -cp <mysql-connector.jar> ApplySql.java <path-to-sql>
 * 退出码：0 = 全部成功；1 = 有语句失败；2 = 参数错误或被安全约束拦下。
 */
public class ApplySql {

    static final String HOST = "192.168.189.128";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("用法: java ApplySql.java <path-to-sql>");
            System.exit(2);
        }

        String raw = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);

        // 剥离 -- 行注释（本脚本的 COMMENT 文本里不含 -- ，安全）
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            int i = line.indexOf("--");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }

        List<String> stmts = new ArrayList<>();
        for (String s : sb.toString().split(";")) {
            String t = s.replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) {
                stmts.add(t);
            }
        }

        System.out.println("文件: " + args[0]);
        System.out.println("解析出语句数: " + stmts.size());

        // 安全闸门：只允许 ALTER TABLE
        for (String s : stmts) {
            if (!s.toUpperCase().startsWith("ALTER TABLE")) {
                System.out.println("[拒绝执行] 出现非 ALTER TABLE 语句，本工具只用于注释对齐脚本：");
                System.out.println("    " + s.substring(0, Math.min(160, s.length())));
                System.exit(2);
            }
        }
        System.out.println("安全闸门: 全部语句均为 ALTER TABLE ✔");
        System.out.println();

        String url = "jdbc:mysql://" + HOST + ":3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=30000";
        int ok = 0;
        int fail = 0;
        try (Connection c = DriverManager.getConnection(url, "root", "123");
             Statement st = c.createStatement()) {
            for (String s : stmts) {
                System.out.println("--- " + s.substring(0, Math.min(150, s.length())));
                try {
                    st.execute(s);
                    System.out.println("    OK");
                    ok++;
                } catch (SQLException e) {
                    System.out.println("    FAIL: " + e.getMessage());
                    fail++;
                }
            }
        }
        System.out.println();
        System.out.println("执行结果: 成功 " + ok + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
