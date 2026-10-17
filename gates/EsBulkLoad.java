import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/**
 * 把 MySQL 读模型 hm-search.shop_search_doc 灌进 ES 索引 shop_search（走 _bulk HTTP API）。
 *
 * 为什么用这个而不是直接调服务的 POST /search/index/rebuild：
 *   服务那边要先 Maven 构建、起 JVM、连 Nacos/MySQL/MQ —— 链路长。
 *   本地灌数只依赖"MySQL 有数据 + ES 活着"，能把「ES 侧是否配好」单独验证掉：
 *   IK 分词器、geo_point、function_score 打分是否符合预期。
 *
 * 时间字段写 eventTimestamp（epoch 毫秒），与 mapping 的 format=epoch_millis 对齐 ——
 * 用字符串日期会被截断/读回失败（实测踩过）。
 *
 * 用法：java -cp mysql-connector.jar EsBulkLoad.java <dbHost[:port]> <user> <pw> <db> <esUrl> [indexName]
 *
 * dbHost 允许带端口（例如 127.0.0.1:3307）：宿主上已装有原生 MySQL 占着 3306 时，
 * demo.ps1 用 -DbHostPort 把容器 mysql 映射到别的宿主端口，此处按传入值原样使用；不带端口时仍取 3306。
 */
public class EsBulkLoad {

    public static void main(String[] args) throws Exception {
        String dbHost = args[0], user = args[1], pw = args[2], db = args[3];
        String esUrl = args[4].replaceAll("/+$", "");
        String index = args.length > 5 ? args[5] : "shop_search";

        Class.forName("com.mysql.cj.jdbc.Driver");
        String hostPort = dbHost.indexOf(':') >= 0 ? dbHost : (dbHost + ":3306");
        String url = "jdbc:mysql://" + hostPort + "/" + db + "?useUnicode=true&characterEncoding=UTF-8"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=6000";

        StringBuilder ndjson = new StringBuilder();
        int n = 0;
        try (Connection c = DriverManager.getConnection(url, user, pw);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select shop_id, name, type_id, type_name, area, address, x, y, avg_price, score, comments,"
                   + " sold, hot_score, status, UNIX_TIMESTAMP(event_time) * 1000 as event_ts"
                   + " from shop_search_doc order by shop_id")) {
            while (rs.next()) {
                long id = rs.getLong("shop_id");
                StringBuilder doc = new StringBuilder();
                doc.append('{');
                field(doc, "shopId", id);
                field(doc, "name", rs.getString("name"));
                field(doc, "typeId", rs.getObject("type_id"));
                field(doc, "typeName", rs.getString("type_name"));
                field(doc, "area", rs.getString("area"));
                field(doc, "address", rs.getString("address"));
                field(doc, "avgPrice", rs.getObject("avg_price"));
                field(doc, "score", rs.getObject("score"));
                field(doc, "comments", rs.getObject("comments"));
                field(doc, "sold", rs.getObject("sold"));
                field(doc, "hotScore", rs.getObject("hot_score"));
                field(doc, "status", rs.getObject("status"));
                field(doc, "eventTimestamp", rs.getObject("event_ts"));
                Double x = toDouble(rs.getObject("x"));
                Double y = toDouble(rs.getObject("y"));
                if (x != null && y != null) {
                    field(doc, "location", y + "," + x);   // ES geo_point: "lat,lon"
                }
                trim(doc);
                doc.append('}');

                ndjson.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":").append(id).append("}}\n");
                ndjson.append(doc).append('\n');
                n++;
            }
        }
        System.out.println("从 MySQL 读到 " + n + " 行，准备 bulk 写入 " + index);
        if (n == 0) {
            // 空 body 会被 ES 以 400 request body is required 拒掉，那不是数据问题而是时序问题：
            // 读模型表由 /search/view/rebuild 生成，调用方必须等它跑完再灌数。
            System.out.println("读模型 shop_search_doc 是空的 —— 先调 POST /search/view/rebuild 再灌数；本次不发 bulk。");
            System.out.println("RESULT: EMPTY");
            System.exit(0);
        }

        byte[] body = ndjson.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(esUrl + "/_bulk?refresh=true").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-ndjson");
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) { os.write(body); }
        int code = conn.getResponseCode();
        String resp = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        System.out.println("bulk 响应 HTTP " + code);
        System.out.println(resp.length() > 300 ? resp.substring(0, 300) + "..." : resp);
        if (code >= 400 || resp.contains("\"errors\":true")) {
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: OK (" + n + " docs)");
    }

    static Double toDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    static void field(StringBuilder b, String name, Object v) {
        if (v == null) return;
        if (b.charAt(b.length() - 1) != '{') b.append(',');
        b.append('"').append(name).append("\":");
        if (v instanceof Number) b.append(v);
        else b.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }

    static void trim(StringBuilder b) {
        if (b.charAt(b.length() - 1) == ',') b.setLength(b.length() - 1);
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
