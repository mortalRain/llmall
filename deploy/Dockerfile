# ============================================================================
# 本地生活到店服务平台 · 全服务镜像（单镜像多服务）
#
# 为什么用"单镜像多 jar"而不是"一个服务一个镜像"：
#   11 个服务各建一次镜像意味着 11 次 Maven 构建 + 11 层依赖下载，本地/小服务器上极其耗时；
#   打成"一个镜像内含全部 jar"，容器只换启动命令即可，构建一次、复用 11 次。
#   （生产上若各服务独立发布节奏不同，再拆成一服务一镜像。）
#
# 构建（在仓库根目录执行，即 D:\dsh\llmall）：
#   docker build -f deploy/Dockerfile -t local-life/llmall:1.0.0 .
#
# 本文件需要 BuildKit（`RUN --mount=type=cache`）：Docker 23 起 `docker build` 默认就是 BuildKit，
# 老版本需 `DOCKER_BUILDKIT=1 docker build ...`，否则报 "unknown flag: mount"。
#
# 运行单个服务：
#   docker run --rm -e NACOS_ADDR=nacos:8848 local-life/llmall:1.0.0 java -jar /app/llmall-gateway.jar
# ============================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /src

# Maven 镜像源：官方镜像里没有镜像配置，而 Central 在多数内网/机房网络里 TLS 握手直接失败，
# 于是"构建卡住"会表现成连接超时。settings.xml 随仓库走，构建不再依赖宿主的 ~/.m2。
# 副作用是**构建阶段**的所有下载都走 Aliyun（运行镜像里没有这个文件）。
COPY deploy/settings.xml /settings.xml

# 先只拷 pom 文件，利用层缓存：依赖不变时不会重复下载
COPY pom.xml ./
COPY llmall-common/pom.xml llmall-common/
COPY llmall-api/pom.xml llmall-api/
COPY llmall-gateway/pom.xml llmall-gateway/
COPY llmall-user-service/pom.xml llmall-user-service/
COPY llmall-shop-service/pom.xml llmall-shop-service/
COPY llmall-item-service/pom.xml llmall-item-service/
COPY llmall-cart-service/pom.xml llmall-cart-service/
COPY llmall-trade-service/pom.xml llmall-trade-service/
COPY llmall-pay-service/pom.xml llmall-pay-service/
COPY llmall-promotion-service/pom.xml llmall-promotion-service/
COPY llmall-content-service/pom.xml llmall-content-service/
COPY llmall-search-service/pom.xml llmall-search-service/

# 【为什么加 --mount=type=cache】只靠层缓存挡不住"改了一行源码就全量重下"：
# 源码一变，`COPY . .` 之后的层全失效，依赖要重新下载。cache mount 把 /root/.m2 放进构建缓存
# （不落进最终镜像层、不增加镜像体积），于是只改 Java 源码的重建可以直接复用已下载的依赖。
# 【为什么去掉 -q】构建日志里必须能看见 `Downloading from aliyun-central: https://maven.aliyun.com/...`，
# 否则"到底走没走镜像源"只能靠猜 —— 走到不可达的源时表现是超时，而不是报错。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /settings.xml -DskipTests dependency:go-offline || true

# 再拷源码并打包（测试跳过：部署流水线里单测应在上游关卡完成）
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s /settings.xml -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:11-jre
WORKDIR /app

# 统一产物名在父 pom 里用 <finalName> 固定为 artifactId，所以路径可预测
COPY --from=build /src/llmall-gateway/target/llmall-gateway.jar          /app/
COPY --from=build /src/llmall-user-service/target/llmall-user-service.jar      /app/
COPY --from=build /src/llmall-shop-service/target/llmall-shop-service.jar      /app/
COPY --from=build /src/llmall-item-service/target/llmall-item-service.jar      /app/
COPY --from=build /src/llmall-cart-service/target/llmall-cart-service.jar      /app/
COPY --from=build /src/llmall-trade-service/target/llmall-trade-service.jar    /app/
COPY --from=build /src/llmall-pay-service/target/llmall-pay-service.jar        /app/
COPY --from=build /src/llmall-promotion-service/target/llmall-promotion-service.jar /app/
COPY --from=build /src/llmall-content-service/target/llmall-content-service.jar     /app/
COPY --from=build /src/llmall-search-service/target/llmall-search-service.jar       /app/

# 低内存运行的默认 JVM 参数（16G 机器 / 4G 云服务器都适用，详见 ../资源受限运行方案.md）
ENV JAVA_OPTS="-Xms64m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dspring.jmx.enabled=false -Dfile.encoding=UTF-8"
ENV TZ=Asia/Shanghai

# 默认启动网关；其他服务在 compose 里覆盖 command
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/llmall-gateway.jar"]
