import java.sql.*;

/**
 * 只读审计查询工具（JDK 11 单文件源码执行）。
 *
 * 用途：在没有 mysql 客户端的环境里跑 52a 的只读清点，并采集「库身份」四要素
 *       （哪套数据、关键表行数、最早/最近一条时间、月度分布）。
 *
 * 用法：java -cp <mysql-connector.jar> AuditQuery.java
 * 安全：**只执行 SELECT**，不包含任何写操作；连接串带 useSSL=false 仅为本地 VM。
 */
public class AuditQuery {

    static final String HOST = "192.168.189.128";
    static final String USER = "root";
    static final String PW = "123";

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://" + HOST + ":3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=20000";
        try (Connection c = DriverManager.getConnection(url, USER, PW)) {
            System.out.println("== 连接成功: " + HOST + " (只读审计) ==\n");

            q(c, "SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA "
                    + "WHERE SCHEMA_NAME LIKE 'llmall%' ORDER BY SCHEMA_NAME", "1. llmall-* 库清单");

            q(c, "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS, CREATE_TIME FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA IN ('llmall-pay','llmall-trade') AND TABLE_NAME IN ('pay_order','order')"
                    + " ORDER BY TABLE_SCHEMA, TABLE_NAME", "2. 关键表存在性与估算行数(information_schema)");

            q(c, "SELECT COUNT(*) AS pay_order_rows, MIN(create_time) AS earliest, MAX(create_time) AS latest "
                    + "FROM `llmall-pay`.`pay_order`", "3. pay_order 真实行数与时间范围（库身份要素）");

            q(c, "SELECT COUNT(*) AS order_rows, MIN(create_time) AS earliest, MAX(create_time) AS latest "
                    + "FROM `llmall-trade`.`order`", "4. order 真实行数与时间范围");

            q(c, "SELECT p.status, COUNT(*) AS rows_cnt FROM `llmall-pay`.`pay_order` p "
                    + "GROUP BY p.status ORDER BY p.status", "5. pay_order 状态分布");

            q(c, "SELECT DATE_FORMAT(p.create_time,'%Y-%m') AS ym, COUNT(*) AS rows_cnt "
                    + "FROM `llmall-pay`.`pay_order` p GROUP BY ym ORDER BY ym", "6. pay_order 月度分布");

            // ---- 52a 的三条清点查询（与 52a 文件内容一致）----
            q(c, "SELECT CASE WHEN p.biz_user_id = 1 THEN 'user1' ELSE 'other_users' END AS user_scope, "
                    + "CASE WHEN p.status = 3 THEN 'paid(3)' ELSE CONCAT('status=', p.status) END AS pay_state, "
                    + "COUNT(*) AS orders, IFNULL(SUM(p.amount),0) AS amount_cents "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL GROUP BY user_scope, pay_state ORDER BY user_scope, pay_state",
                    "7. [52a-查询1] 孤儿单规模（按用户范围 × 支付状态）");

            q(c, "SELECT p.id, p.biz_order_no, p.biz_user_id, p.status, p.amount, p.result_msg, p.create_time "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL ORDER BY p.biz_user_id, p.id LIMIT 50",
                    "8. [52a-查询2] 孤儿单明细（最多 50 行）");

            q(c, "SELECT SUM(CASE WHEN p.status = 3 AND p.biz_user_id <> 1 THEN 1 ELSE 0 END) AS non_user1_paid_orphans, "
                    + "IFNULL(SUM(CASE WHEN p.status = 3 AND p.biz_user_id <> 1 THEN p.amount ELSE 0 END),0) AS non_user1_paid_cents "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL", "9. [52a-查询3] verdict 判据");

            q(c, "SELECT COUNT(*) AS total_pay_orders, "
                    + "SUM(CASE WHEN o.id IS NULL THEN 1 ELSE 0 END) AS orphan_rows "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no",
                    "10. 孤儿单占比（分母佐证数据规模）");
        }
    }

    static void q(Connection c, String sql, String title) {
        System.out.println("--- " + title + " ---");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            int n = m.getColumnCount();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= n; i++) { head.append(m.getColumnLabel(i)).append(i < n ? " | " : ""); }
            System.out.println("  " + head);
            int rows = 0;
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) { line.append(rs.getString(i)).append(i < n ? " | " : ""); }
                System.out.println("  " + line);
                rows++;
            }
            if (rows == 0) { System.out.println("  (无行)"); }
            System.out.println("  [行数: " + rows + "]\n");
        } catch (SQLException e) {
            System.out.println("  [查询失败] " + e.getMessage() + "\n");
        }
    }
}
