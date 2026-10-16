import java.sql.*;

/**
 * 只读探测：这台 MySQL 上到底有哪些库/表，有没有"未被 52 号处理过"的支付与订单数据。
 * 用途：判断 52a 清点能否在有意义的数据上执行（而不是只能在本地演示库上下结论）。
 *
 * 用法：java -cp <mysql-connector.jar> Probe.java
 * 安全：仅 SHOW / SELECT，不做任何修改。
 */
public class Probe {

    static final String HOST = "192.168.189.128";

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://" + HOST + ":3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=20000";
        try (Connection c = DriverManager.getConnection(url, "root", "123")) {

            System.out.println("== 全部库 ==");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1));
                }
            }

            System.out.println();
            System.out.println("== 任何库里出现过的 pay_order / order 表（判断是否存在未被处理过的同源数据）==");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_ROWS, CREATE_TIME "
                                 + "FROM information_schema.TABLES "
                                 + "WHERE TABLE_NAME IN ('pay_order','order') "
                                 + "  AND TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema') "
                                 + "ORDER BY TABLE_SCHEMA, TABLE_NAME")) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("  %-22s %-12s 估算行数=%-8s 建于 %s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                }
                if (!any) {
                    System.out.println("  (无)");
                }
            }

            System.out.println();
            System.out.println("== 非 llmall 前缀的业务库概览 ==");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT TABLE_SCHEMA, COUNT(*) AS tables_cnt, SUM(TABLE_ROWS) AS rows_est "
                                 + "FROM information_schema.TABLES "
                                 + "WHERE TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema') "
                                 + "  AND TABLE_SCHEMA NOT LIKE 'llmall%' "
                                 + "GROUP BY TABLE_SCHEMA ORDER BY TABLE_SCHEMA")) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("  %-22s 表=%s  估算总行数=%s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3));
                }
                if (!any) {
                    System.out.println("  (除 llmall-* 外无其它业务库)");
                }
            }
        }
    }
}
