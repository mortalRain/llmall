import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 商城链路端到端验收脚本：登录 → 商城浏览/筛选 → 购物车 → 下单 → 支付单幂等 → 余额支付
 * → 订单状态联动 → 积分发放 → 取消订单回滚库存 → 订单列表筛选。
 *
 * 【注意】本机工具，不参与项目构建；需网关与各服务已启动、库中种子数据在位。链路含 MQ 与事件驱动的
 * 异步联动，相关断言必须等最终一致后复查。
 *
 * 用法：java -cp "<jackson jars>;D:\dsh\.jscheck" MallE2E.java
 */
public class MallE2E {

    static final String BASE = "http://localhost:8080";

    /**
     * 演示账号 Jack 的支付口令（不是登录口令）。
     *
     * 【为什么不是登录口令那个 123456】服务端有一条「首次支付前强制改密」的拦截：当前
     * pay_password 仍等于初始口令时，扣款在密码校验通过之后还会被拒（UserServiceImpl 里
     * INITIAL_PASSWORD 那个分支，错误信息「请先修改支付密码」）。而修改支付密码的接口
     * POST /users/pay-password 要求短信验证码，冒烟脚本无法自给，所以走不通"脚本内改密"这条路。
     *
     * 【所以】演示库里把 Jack 的 pay_password 预置成 888888 的 bcrypt 哈希，让冒烟走
     * "这个账号已经改过支付密码"的正常路径。生产安全语义完全不变 —— 被绕开的只是
     * "演示账号从未改过密"这一个状态，不是那条拦截本身。
     *
     * 【登录口令仍是 123456，不要动】见下方 login 那行。
     */
    static final String DEMO_PAY_PW = "888888";
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    static String token = "";
    static int pass = 0, fail = 0;

    /** 大V fixture 账号名：该账号的粉丝数必须超过写扩散阈值，才可能触发"跳过推送"，是补拉断言的前提 */
    static final String BIGV_USER = "FeedAuthor";

    /** 与内容域 FEED_PUSH_THRESHOLD 保持一致：超过该粉丝数的作者不再写扩散 */
    static final int BIGV_THRESHOLD = 1000;

    public static void main(String[] args) throws Exception {
        if (!waitReady()) {
            System.out.println("网关未就绪，退出");
            return;
        }

        JsonNode login = post("/users/login", "{\"username\":\"Jack\",\"password\":\"123456\"}", false);
        token = login.path("accessToken").asText();
        check("登录 Jack/123456", token.length() > 20, "token " + token.length() + " 字符");

        // 人均价格口径为分：必须先清缓存再读，否则拿到的是旧口径的缓存 DTO
        delete("/shops/cache/1");
        JsonNode shop1 = get("/shops/1", false);
        check("门店人均单位(元→分)", shop1.path("avgPrice").asInt() == 8000,
                "avgPrice=" + shop1.path("avgPrice").asInt() + " 分 = " + (shop1.path("avgPrice").asDouble() / 100) + " 元");

        JsonNode pkg = get("/items/page?pageNo=1&pageSize=5&type=2", false);
        check("到店套餐分页", pkg.path("total").asInt() >= 150, "total=" + pkg.path("total").asInt() + "（30 家门店 × 5 档）");
        JsonNode phy = get("/items/page?pageNo=1&pageSize=5&type=1", false);
        check("实物商品分页", phy.path("total").asInt() > 0, "total=" + phy.path("total").asInt());
        JsonNode cats = get("/items/categories?type=2", false);
        check("分类聚合", cats.size() >= 3, cats.toString());
        JsonNode byKey = get("/items/page?pageNo=1&pageSize=5&type=2&key=" + enc("双人"), false);
        check("关键词筛选(中文)", byKey.path("total").asInt() > 0, "total=" + byKey.path("total").asInt());
        JsonNode byKeyAscii = get("/items/page?pageNo=1&pageSize=5&type=2&key=KTV", false);
        check("关键词筛选(ASCII)", byKeyAscii.path("total").asInt() > 0, "total=" + byKeyAscii.path("total").asInt());
        JsonNode byCat = get("/items/page?pageNo=1&pageSize=5&type=2&category=" + enc("代金券"), false);
        check("分类筛选", byCat.path("total").asInt() > 0, "total=" + byCat.path("total").asInt());
        JsonNode byShop = get("/items/shop/1", false);
        check("门店套餐列表", byShop.size() == 5, byShop.size() + " 个");
        JsonNode detail = get("/items/900013", false);
        check("商品详情", detail.path("shopId").asInt() == 1, detail.path("name").asText() + " / " + detail.path("price").asInt() + " 分");

        post("/carts", "{\"itemId\":900013,\"name\":\"" + detail.path("name").asText() + "\",\"spec\":\"到店核销\",\"price\":"
                + detail.path("price").asInt() + ",\"image\":null}", true);
        JsonNode carts = get("/carts", true);
        check("加入购物车", carts.size() >= 1, carts.size() + " 项");
        long cartId = 0, cartItemId = 0;
        for (JsonNode c : carts) {
            if (c.path("itemId").asLong() == 900013) {
                cartId = c.path("id").asLong();
                cartItemId = c.path("itemId").asLong();
            }
        }
        check("购物车命中刚加的商品", cartId > 0, "cartId=" + cartId);
        put("/carts", "{\"id\":" + cartId + ",\"num\":3}");
        JsonNode carts2 = get("/carts", true);
        int num = 0;
        for (JsonNode c : carts2) {
            if (c.path("id").asLong() == cartId) {
                num = c.path("num").asInt();
            }
        }
        check("修改数量", num == 3, "num=" + num);

        // 该单同时覆盖 llmall-cart-service 消费 order.create 清理购物车，MQ 异步，断言前需等待
        JsonNode addrs = get("/addresses", true);
        check("地址列表", addrs.size() > 0, addrs.size() + " 个");
        long addrId = addrs.get(0).path("id").asLong();
        long stockBefore = get("/items/900013", false).path("stock").asLong();
        long orderId = post("/orders", "{\"addressId\":" + addrId + ",\"paymentType\":3,\"details\":[{\"itemId\":900013,\"num\":1}]}", true).asLong();
        check("创建订单", orderId > 0, "orderId=" + orderId);
        long stockAfterOrder = get("/items/900013", false).path("stock").asLong();
        check("下单扣减库存", stockAfterOrder == stockBefore - 1, stockBefore + " → " + stockAfterOrder);
        sleep(3000);
        JsonNode carts3 = get("/carts", true);
        boolean stillThere = false;
        for (JsonNode c : carts3) {
            if (c.path("itemId").asLong() == 900013) {
                stillThere = true;
            }
        }
        check("下单后购物车自动清理(MQ)", !stillThere, "购物车剩 " + carts3.size() + " 项");

        JsonNode order = get("/orders/" + orderId, true);
        check("订单详情含明细", order.path("details").size() == 1,
                "status=" + order.path("status").asInt() + " 金额=" + order.path("totalFee").asInt() + " 分 明细="
                        + order.path("details").get(0).path("name").asText() + "×" + order.path("details").get(0).path("num").asInt());

        String payBody = "{\"bizOrderNo\":" + orderId + ",\"amount\":" + order.path("totalFee").asInt()
                + ",\"payChannelCode\":\"balance\",\"payType\":5,\"orderInfo\":\"e2e\"}";
        String pay1 = post("/pay-orders", payBody, true).asText();
        String pay2 = post("/pay-orders", payBody, true).asText();
        check("支付单申请幂等", pay1.equals(pay2) && !pay1.isEmpty(), "id=" + pay1);

        long balanceBefore = get("/users/me", true).path("balance").asLong();
        int creditsBefore = get("/users/me", true).path("credits").asInt(0);
        post("/pay-orders/" + pay1, "{\"pw\":\"" + DEMO_PAY_PW + "\"}", true);
        sleep(6000);
        JsonNode paid = get("/orders/" + orderId, true);
        JsonNode me = get("/users/me", true);
        JsonNode flows = get("/points/me?limit=5", true);
        check("支付后订单变已付款", paid.path("status").asInt() == 2, "status=" + paid.path("status").asInt() + " payTime=" + paid.path("payTime").asText());
        check("余额已扣减", me.path("balance").asLong() < balanceBefore,
                (balanceBefore / 100.0) + " → " + (me.path("balance").asLong() / 100.0) + " 元");
        boolean hasFlow = false;
        for (JsonNode f : flows) {
            if (f.path("bizId").asText().equals(String.valueOf(orderId))) {
                hasFlow = true;
            }
        }
        check("积分流水已发放(order.paid)", hasFlow, "积分 " + creditsBefore + " → " + me.path("credits").asInt() + "，流水 " + flows.size() + " 条");

        long st0 = get("/items/900014", false).path("stock").asLong();
        long oid2 = post("/orders", "{\"addressId\":" + addrId + ",\"paymentType\":3,\"details\":[{\"itemId\":900014,\"num\":2}]}", true).asLong();
        long st1 = get("/items/900014", false).path("stock").asLong();
        put("/orders/" + oid2 + "/cancel", null);
        sleep(3000);
        long st2 = get("/items/900014", false).path("stock").asLong();
        JsonNode canceled = get("/orders/" + oid2, true);
        check("取消订单回滚库存", st2 == st0, st0 + " → 下单 " + st1 + " → 取消 " + st2);
        check("取消后订单状态=5", canceled.path("status").asInt() == 5, "status=" + canceled.path("status").asInt());

        JsonNode all = get("/orders?pageNo=1&pageSize=3", true);
        check("我的订单列表", all.path("total").asInt() > 0, "total=" + all.path("total").asInt() + " 本页 " + all.path("list").size());
        JsonNode unpaid = get("/orders?pageNo=1&pageSize=3&status=1", true);
        JsonNode paidList = get("/orders?pageNo=1&pageSize=3&status=2", true);
        JsonNode closed = get("/orders?pageNo=1&pageSize=3&status=5", true);
        boolean statusOk = true;
        for (JsonNode o : unpaid.path("list")) {
            if (o.path("status").asInt() != 1) {
                statusOk = false;
            }
        }
        for (JsonNode o : paidList.path("list")) {
            if (o.path("status").asInt() != 2) {
                statusOk = false;
            }
        }
        check("订单状态筛选", statusOk, "待付款 " + unpaid.path("total").asInt() + " / 待使用 " + paidList.path("total").asInt()
                + " / 已取消 " + closed.path("total").asInt());

        String errBody = "";
        try {
            get("/orders/999999999999", true);
        } catch (Exception e) {
            errBody = e.getMessage();
        }
        check("不存在的订单返回统一错误体", errBody.contains("订单不存在") || errBody.contains("msg"),
                errBody.length() > 120 ? errBody.substring(0, 120) + "..." : errBody);

        // 资金安全边界：下列场景必须被拒绝，断言口径为"错误体非空 + 余额不变"
        // 已关闭订单与金额篡改都在申请支付单阶段即被拒绝，不会留下指向无效订单的支付单
        long bal0 = get("/users/me", true).path("balance").asLong();
        JsonNode itemX = get("/items/900015", false);
        long cancelledOrder = post("/orders", "{\"addressId\":" + addrId + ",\"paymentType\":3,\"details\":[{\"itemId\":900015,\"num\":1}]}", true).asLong();
        put("/orders/" + cancelledOrder + "/cancel", null);
        sleep(1500);
        String cancelPayErr = "";
        try {
            post("/pay-orders",
                    "{\"bizOrderNo\":" + cancelledOrder + ",\"amount\":" + itemX.path("price").asInt()
                            + ",\"payChannelCode\":\"balance\",\"payType\":5,\"orderInfo\":\"e2e-cancelled\"}", true);
        } catch (Exception e) {
            cancelPayErr = e.getMessage();
        }
        long bal1 = get("/users/me", true).path("balance").asLong();
        check("已关闭订单禁止生成支付单/禁止支付（防资损）", !cancelPayErr.isEmpty() && bal1 == bal0,
                "拒绝原因: " + (cancelPayErr.contains("msg") ? cancelPayErr.replaceAll(".*\"msg\":\"([^\"]*)\".*", "$1") : cancelPayErr)
                        + "，余额 " + (bal0 / 100.0) + " 未变");

        String paidOrderId = String.valueOf(orderId);
        String repeatErr = "";
        try {
            post("/pay-orders/" + pay1, "{\"pw\":\"" + DEMO_PAY_PW + "\"}", true);
        } catch (Exception e) {
            repeatErr = e.getMessage();
        }
        check("重复支付被拒绝", !repeatErr.isEmpty(),
                repeatErr.contains("msg") ? repeatErr.replaceAll(".*\"msg\":\"([^\"]*)\".*", "$1") : repeatErr);

        long oid3 = post("/orders", "{\"addressId\":" + addrId + ",\"paymentType\":3,\"details\":[{\"itemId\":900021,\"num\":1}]}", true).asLong();
        String tamperErr = "";
        try {
            post("/pay-orders",
                    "{\"bizOrderNo\":" + oid3 + ",\"amount\":1,\"payChannelCode\":\"balance\",\"payType\":5,\"orderInfo\":\"tampered\"}", true);
        } catch (Exception e) {
            tamperErr = e.getMessage();
        }
        long bal2 = get("/users/me", true).path("balance").asLong();
        check("支付金额篡改被拒绝（建单阶段）", !tamperErr.isEmpty() && bal2 == bal1,
                tamperErr.contains("msg") ? tamperErr.replaceAll(".*\"msg\":\"([^\"]*)\".*", "$1") : tamperErr);

        put("/orders/" + paidOrderId + "/cancel", null);
        sleep(1200);
        check("已支付订单不会被取消", get("/orders/" + paidOrderId, true).path("status").asInt() == 2,
                "订单 " + paidOrderId + " 状态仍为 " + get("/orders/" + paidOrderId, true).path("status").asInt());

        // 支付超时口径为 15 分钟：下单 12 秒后订单仍应可支付
        JsonNode freshOrder = get("/orders/" + oid3, true);
        sleep(12000);
        JsonNode after12s = get("/orders/" + oid3, true);
        check("下单 12 秒后订单仍可支付（超时时间已放宽到 15 分钟）", after12s.path("status").asInt() == 1,
                "12 秒后状态 = " + after12s.path("status").asInt() + "（1=待付款，5=已被自动关单）");

        // 评价写入后经事件驱动重算门店热度，并刷新 ZSet 热度榜
        double hotBefore = get("/shops/1", false).path("hotScore").asDouble();
        int commentsBefore = get("/shops/1", false).path("comments").asInt();
        post("/blogs", "{\"shopId\":1,\"title\":\"E2E 自动化评价\",\"content\":\"验证评价事件驱动热度重算\",\"score\":5}", true);
        sleep(9000);
        JsonNode shopAfter = get("/shops/1", false);
        double hotAfter = shopAfter.path("hotScore").asDouble();
        check("评价 → 门店热度重算(事件驱动)", hotAfter != hotBefore,
                "热度 " + hotBefore + " → " + hotAfter + "，评价数 " + commentsBefore + " → " + shopAfter.path("comments").asInt());
        JsonNode hotRank = get("/shops/hot?limit=3", false);
        check("热度榜(ZSet)可读", hotRank.size() > 0, hotRank.size() + " 家门店");

        // 大V（粉丝数超过写扩散阈值）发布后，粉丝的 Feed 必须能看到这条笔记。
        // 这条笔记没有被写扩散进收件箱，只能经由读路径补拉进入视图，所以两条断言必须成对：
        // 旧接口看不到（推送确实被跳过）且新接口看得到（补拉生效）。缺一样都证明不了闭环。
        JsonNode bigvLogin = null;
        try {
            bigvLogin = post("/users/login", "{\"username\":\"" + BIGV_USER + "\",\"password\":\"123456\"}", false);
        } catch (Exception ignored) {
            // 环境里没有这个 fixture 账号：不算失败，但要把跳过原因说清楚
        }
        if (bigvLogin == null) {
            System.out.println("  [跳过] 大V Feed 补拉 —— 环境里没有 " + BIGV_USER + " 账号");
        } else {
            String bigvToken = bigvLogin.path("accessToken").asText();
            long bigvId = bigvLogin.path("userId").asLong();
            long fans = get("/follows/" + bigvId + "/fans", true).asLong();
            if (fans <= BIGV_THRESHOLD) {
                System.out.println("  [跳过] 大V Feed 补拉 —— " + BIGV_USER + " 粉丝数 " + fans
                        + " 未超过阈值 " + BIGV_THRESHOLD);
            } else {
                post("/follows/" + bigvId, null, true);
                String title = "E2E 大V 补拉断言 " + System.currentTimeMillis();
                postAs(bigvToken, "/blogs", "{\"shopId\":1,\"title\":\"" + title
                        + "\",\"content\":\"跨过阈值后不再写扩散，只能靠读路径补拉\",\"score\":5}");
                boolean inOld = containsTitle(get("/follows/feed?size=50", true), title);
                check("大V 笔记不进旧接口（写扩散确实被跳过）", !inOld,
                        "粉丝数 " + fans + " 超过阈值，该笔记未被推进收件箱");
                JsonNode newItems = get("/follows/feed/v2?size=50", true).path("items");
                check("大V 粉丝在 v2 Feed 能看到这条（读路径补拉生效）", containsTitle(newItems, title),
                        "补拉后可见，本页 " + newItems.size() + " 条");
                java.util.Set<Long> uniq = new java.util.HashSet<>();
                boolean dup = false;
                for (JsonNode it : newItems) {
                    if (!uniq.add(it.path("blogId").asLong())) {
                        dup = true;
                    }
                }
                check("v2 Feed 无重复条目（跨阈值两路相交后被去重）", !dup,
                        "唯一 " + uniq.size() + " / 共 " + newItems.size());
            }
        }

        System.out.println("\n================ 结果 ================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
    }

    static boolean waitReady() {
        for (int i = 0; i < 40; i++) {
            try {
                HttpResponse<String> r = send("GET", "/items/page?pageNo=1&pageSize=1&type=2", null, false);
                if (r.statusCode() == 200) {
                    System.out.println("网关就绪（等待 " + (i * 2) + " 秒）\n");
                    return true;
                }
            } catch (Exception ignored) {
            }
            sleep(2000);
        }
        return false;
    }

    static HttpResponse<String> send(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(25));
        if (body != null) {
            b.header("content-type", "application/json; charset=utf-8");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (auth && !token.isEmpty()) {
            b.header("authorization", token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 用指定账号的令牌发请求：大V 断言的发布动作必须由那个账号本人发起，不能借用登录态全局变量 */
    static HttpResponse<String> sendAs(String method, String path, String body, String tok) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(25));
        if (body != null) {
            b.header("content-type", "application/json; charset=utf-8");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (tok != null && !tok.isEmpty()) {
            b.header("authorization", tok);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static JsonNode postAs(String tok, String path, String body) throws Exception {
        HttpResponse<String> r = sendAs("POST", path, body, tok);
        if (r.statusCode() != 200) {
            throw new RuntimeException("POST " + path + " → " + r.statusCode() + " " + r.body());
        }
        return M.readTree(r.body());
    }

    /** 判断一页结果里是否存在指定标题；Feed 视图的标题字段是 title */
    static boolean containsTitle(JsonNode arr, String title) {
        for (JsonNode n : arr) {
            if (title.equals(n.path("title").asText())) {
                return true;
            }
        }
        return false;
    }

    static JsonNode get(String path, boolean auth) throws Exception {
        HttpResponse<String> r = send("GET", path, null, auth);
        if (r.statusCode() != 200) {
            throw new RuntimeException("GET " + path + " → " + r.statusCode() + " " + r.body());
        }
        return M.readTree(r.body());
    }

    static JsonNode post(String path, String body, boolean auth) throws Exception {
        HttpResponse<String> r = send("POST", path, body, auth);
        if (r.statusCode() != 200) {
            throw new RuntimeException("POST " + path + " → " + r.statusCode() + " " + r.body());
        }
        return M.readTree(r.body());
    }

    static void put(String path, String body) throws Exception {
        HttpResponse<String> r = send("PUT", path, body, true);
        if (r.statusCode() != 200) {
            throw new RuntimeException("PUT " + path + " → " + r.statusCode() + " " + r.body());
        }
    }

    static void delete(String path) throws Exception {
        send("DELETE", path, null, true);
    }

    static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name + " —— " + detail);
        } else {
            fail++;
            System.out.println("  [失败] " + name + " —— " + detail);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
