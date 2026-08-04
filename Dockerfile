# 三阶段:编译 → 裁 JRE → 运行时。
#
# 为什么不是两阶段:上一版写的是 `COPY xbb-v1/target/xbb-v1-*.jar`,
# 也就是**要求本地先 mvn package**。本地能跑,是因为 jar 早就编好躺在那儿了;
# 而 Render 是从源码构建的,仓库里没有 jar —— 那一版在云上必然失败。
# 这一版自己编。

# 前端。产物是 Spring 的静态资源,所以必须排在 maven 之前。
# 打进同一个 jar、由同一个进程伺服:免费档只有一个 Web 服务,
# 拆两个容器既多花一份内存,又得处理跨域和两套部署。
FROM node:22-alpine AS web
WORKDIR /app
COPY web/package.json web/package-lock.json ./web/
RUN cd web && npm ci --no-audit --no-fund
COPY web/ ./web/
# vite 的 outDir 是 ../src/main/resources/static,从 /app/web 出发正好落到 /app/src/...
RUN cd web && npm run build

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 先只拷 pom,让"下载依赖"单独成层。改代码不改依赖时这一层直接命中缓存。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# 覆盖在 COPY src 之后:静态资源是**构建产物**,不进版本库,
# 以本地残留为准会让"我这儿好好的"和线上不一致。
COPY --from=web /app/src/main/resources/static ./src/main/resources/static
# 跳过测试:390 个测试里有相当一部分要 Testcontainers(需要 Docker daemon),
# 构建环境里没有。测试在本地与 CI 跑,不在镜像构建时跑 —— 这是取舍,不是省事:
# 构建阶段跑不了的测试,硬塞进来只会变成"构建时被跳过但看不出来"。
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jdk-alpine AS jre-builder
# 只保留实际用到的模块。全量 JRE 镜像 624MB,裁完 348MB。
# 注意别加 jdk.random:曾因 RandomGenerator.getDefault() 依赖它而在精简 JRE 上崩,
# 现在代码改用 java.util.Random,这里就不需要它了。
RUN jlink \
      --add-modules java.base,java.logging,java.naming,java.sql,java.desktop,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,jdk.crypto.ec,java.xml,java.compiler,jdk.jfr \
      --strip-debug --no-man-pages --no-header-files --compress=2 \
      --output /slim-jre

FROM alpine:3.20
# postgresql-client:entrypoint 要用 psql 引导 20 个 schema 与最小权限用户。
# 托管库没有 initdb 钩子,这一步只能在应用侧做。
RUN apk add --no-cache libstdc++ postgresql-client \
 && adduser -D -u 10001 appuser
COPY --from=jre-builder /slim-jre /opt/jre
ENV PATH="/opt/jre/bin:$PATH"

WORKDIR /app
# 日志路径写死在 logback-spring.xml 里,不建就启动失败
RUN mkdir -p /opt/linghuo/logs && chown -R appuser /opt/linghuo
COPY --from=builder /build/target/xbb-v1-*.jar /app/app.jar
COPY db/init-schemas.sql /app/db/init-schemas.sql
COPY docker-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh && chown -R appuser /app

# 免费档 512MB / 约 0.1 核。**CPU 才是瓶颈,不是内存。**
#
# -XX:TieredStopAtLevel=1 关掉 C2 即时编译:Spring 启动是"大量类只跑一次"的负载,
# C2 的优化来不及回本,却要抢本来就稀缺的 CPU。实测 0.25 核下
# 启动 290 秒 → 131 秒(砍 55%)。代价是稳态吞吐略降,测试环境完全划算。
#
# 这条是被线上打出来的:只测过 512MB 内存、**从没测过 CPU 受限**,
# 于是本机 15 秒起来的应用在 Render 上超时、部署失败。
ENV JAVA_OPTS="-Xms192m -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k -XX:TieredStopAtLevel=1 -Dspring.jmx.enabled=false"
USER appuser
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
