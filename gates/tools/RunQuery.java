import java.sql.*;

/**
 * 通用只读查询工具（替代本机缺失的 mysql 客户端）。
 *
 * 用法：java -cp <mysql-connector.jar> RunQuery.java "SELECT ..." ["SELECT ..." ...]
 * 约定：**只传 SELECT / SHOW**，本工具不做任何写操作，也不对写语句做特殊处理 —— 请自觉遵守。
 * 安全：连接串指向本地 VM，useSSL=false 仅为本地环境。
 */
public class RunQuery {

    static final String HOST = "192.168.189.128";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: java RunQuery.java \"SELECT ...\" [\"SELECT ...\" ...]");
            System.exit(2);
        }
        String url = "jdbc:mysql://" + HOST + ":3306/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=20000";
        try (Connection c = DriverManager.getConnection(url, "root", "123");
             Statement st = c.createStatement()) {
            for (String sql : args) {
                System.out.println("--- " + sql);
                try (ResultSet rs = st.executeQuery(sql)) {
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
                    System.out.println("  [" + rows + " 行]");
                } catch (SQLException e) {
                    System.out.println("  [失败] " + e.getMessage());
                }
                System.out.println();
            }
        }
    }
}
