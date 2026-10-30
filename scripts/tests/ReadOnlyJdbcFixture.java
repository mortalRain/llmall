import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/** Offline JDBC probe. No sockets, databases, credentials or business writes. */
public class ReadOnlyJdbcFixture implements Driver {
    static {
        try { DriverManager.registerDriver(new ReadOnlyJdbcFixture()); }
        catch (SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void record(String value) throws Exception {
        Files.writeString(Path.of(System.getenv("SMOKE_JDBC_PROBE")), value + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        try { record("CONNECT"); } catch (Exception e) { throw new SQLException(e); }
        return proxy(Connection.class, (p, m, a) -> {
            if (m.getName().equals("setReadOnly") && Boolean.TRUE.equals(a[0])) {
                record("READ_ONLY"); return null;
            }
            if (m.getName().equals("close")) return null;
            if (m.getName().equals("createStatement")) return statement();
            throw new AssertionError("Unexpected connection operation: " + m.getName());
        });
    }

    private Statement statement() {
        return proxy(Statement.class, (p, m, a) -> {
            if (m.getName().equals("close")) return null;
            if (!m.getName().equals("executeQuery")) {
                throw new AssertionError("Non-read operation: " + m.getName());
            }
            String sql = (String) a[0];
            if (!sql.startsWith("SELECT ") || sql.contains(";")) {
                throw new AssertionError("Non-read SQL: " + sql);
            }
            record("SELECT");
            boolean[] first = {true};
            return proxy(ResultSet.class, (rp, rm, ra) -> {
                switch (rm.getName()) {
                    case "close": return null;
                    case "next": boolean next = first[0]; first[0] = false; return next;
                    case "getLong": return sql.contains("information_schema") ? 1L : 0L;
                    case "getString": return "1".equals(System.getenv("SMOKE_FIXTURE_DRIFT")) ? "1" : "0";
                    default: throw new AssertionError("Unexpected result operation: " + rm.getName());
                }
            });
        });
    }

    public boolean acceptsURL(String url) { return url.startsWith("jdbc:mysql://"); }
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    public int getMajorVersion() { return 1; }
    public int getMinorVersion() { return 0; }
    public boolean jdbcCompliant() { return false; }
    public Logger getParentLogger() { return Logger.getGlobal(); }
}
