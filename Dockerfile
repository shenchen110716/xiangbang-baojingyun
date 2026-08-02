# 三阶段:编译 → 裁 JRE → 运行时。
#
# 为什么不是两阶段:上一版写的是 `COPY xbb-v1/target/xbb-v1-*.jar`,
# 也就是**要求本地先 mvn package**。本地能跑,是因为 jar 早就编好躺在那儿了;
# 而 Render 是从源码构建的,仓库里没有 jar —— 那一版在云上必然失败。
# 这一版自己编。

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 先只拷 pom,让"下载依赖"单独成层。改代码不改依赖时这一层直接命中缓存。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
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

# 免费套餐 512MB,按 384MB 堆压
ENV JAVA_OPTS="-Xms192m -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k"
USER appuser
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
