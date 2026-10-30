/** Retired launcher. Use the frontend toolchain, explicitly bound to loopback. */
public final class StaticServer {
    private StaticServer() { }
    public static void main(String[] args) {
        throw new UnsupportedOperationException("StaticServer is disabled. In frontend use npm run dev -- --host 127.0.0.1 or npm run preview -- --host 127.0.0.1 after npm run build.");
    }
}
