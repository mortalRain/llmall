import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * 用 JUnit Platform Launcher 直接跑测试类，绕开 `mvn test`。
 *
 * 【为什么需要它】本机 Maven 离线且本地仓库缺 `surefire-junit-platform` provider，`mvn test` 跑不了
 * （见 docs/已知偏差台账-2026-10-15.md 第六节的环境限制）。而 `junit-platform-launcher` 与
 * `junit-jupiter-engine` 都在本地仓库里，于是可以直接用 Launcher API 跑 —— 让"单测通过"有可复现的执行路径。
 *
 * 用法（在仓库根目录，先 `mvn -o -DskipTests package` 产出 target/test-classes）：
 *   java -cp "<test-classes;classes;<test 依赖>;launcher;engine>" RunJUnit <测试类全名...>
 * 退出码 0 = 全绿，1 = 有用例失败。
 */
public final class RunJUnit {

    private RunJUnit() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法: RunJUnit <测试类全名> [更多测试类...]");
            System.exit(2);
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(java.util.Arrays.stream(args)
                        .map(DiscoverySelectors::selectClass)
                        .toArray(org.junit.platform.engine.DiscoverySelector[]::new))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        System.out.println("=== 用例结果 ===");
        summary.printTo(out);
        if (summary.getTotalFailureCount() > 0) {
            summary.printFailuresTo(out);
            System.out.println("结论: 失败 " + summary.getTotalFailureCount() + " 项 / 共 "
                    + summary.getTestsStartedCount() + " 项");
            System.exit(1);
        }
        System.out.println("结论: 全部通过 " + summary.getTestsSucceededCount() + " 项");
    }
}
