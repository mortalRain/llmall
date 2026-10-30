import java.security.Permission;

/** Run with JDK17. Prevents the old implementation from binding a socket during regression. */
@SuppressWarnings("removal")
public class StaticServerDisabledCheck {
    public static void main(String[] args) throws Exception {
        SecurityManager previous = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
            @Override public void checkPermission(Permission permission) { }
            @Override public void checkListen(int port) {
                throw new AssertionError("Obsolete static server attempted to listen on " + port);
            }
        });
        try {
            StaticServer.main(new String[0]);
            throw new AssertionError("Disabled launcher must exit unsuccessfully");
        } catch (UnsupportedOperationException expected) {
            if (!expected.getMessage().contains("npm")) throw new AssertionError("Missing supported alternative");
        } finally {
            System.setSecurityManager(previous);
        }
        System.out.println("Disabled launcher cannot open a listener.");
    }
}
