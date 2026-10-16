import java.sql.*;

/**
 * 52a 补充审计：孤儿单的月度分布、时间范围、用户与状态构成。
 * 只读（仅 SELECT）。用法：java -cp <mysql-connector.jar> AuditQuery2.java
 */
public class AuditQuery2 {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://192.168.189.128:3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=20000";
        try (Connection c = DriverManager.getConnection(url, "root", "123")) {
            q(c, "SELECT DATE_FORMAT(p.create_time,'%Y-%m') AS ym, COUNT(*) AS orphan_rows, "
                    + "IFNULL(SUM(p.amount),0) AS amount_cents "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL GROUP BY ym ORDER BY ym",
                    "孤儿单 · 月度分布");
            q(c, "SELECT COUNT(*) AS orphan_rows, MIN(p.create_time) AS earliest, MAX(p.create_time) AS latest, "
                    + "MIN(p.id) AS min_id, MAX(p.id) AS max_id "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL",
                    "孤儿单 · 时间范围与 id 范围");
            q(c, "SELECT p.biz_user_id, p.status, COUNT(*) AS rows_cnt, IFNULL(SUM(p.amount),0) AS amount_cents "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL GROUP BY p.biz_user_id, p.status ORDER BY p.biz_user_id, p.status",
                    "孤儿单 · 用户 × 状态构成");
            q(c, "SELECT IFNULL(LEFT(p.result_msg,40),'(null)') AS result_msg_prefix, COUNT(*) AS rows_cnt "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL GROUP BY result_msg_prefix ORDER BY rows_cnt DESC",
                    "孤儿单 · result_msg 前缀（判断是否已被 52 号处理过）");
            q(c, "SELECT COUNT(*) AS paid_orphans_any_user, IFNULL(SUM(p.amount),0) AS amount_cents "
                    + "FROM `llmall-pay`.`pay_order` p LEFT JOIN `llmall-trade`.`order` o ON o.id = p.biz_order_no "
                    + "WHERE o.id IS NULL AND p.status = 3",
                    "孤儿单 · 全部用户的已支付孤儿单（52 号退款段的完整口径）");
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
