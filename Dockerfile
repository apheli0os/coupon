# 多阶段构建：builder 编译打包，runtime 只保留 JRE + fat jar
# 用法（在服务模块目录执行，或多模块根目录用 build-images.sh）：
#   docker build --build-arg MODULE=mall-coupon-service -t mall/coupon-service:1.0.0 .

FROM maven:3.9.9-eclipse-temurin-17 AS builder
ARG MODULE
WORKDIR /build
COPY . .
RUN mvn -q -DskipTests -pl ${MODULE} -am package

FROM eclipse-temurin:17-jre-jammy
ARG MODULE
WORKDIR /app
COPY --from=builder /build/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/app.jar
# Sentinel 1.8.6 启动写 ${user.home}/logs/csp，非 root 目录不可写会 NPE；
# 以 root 运行保证本地/生产可启动。生产可改为预建可写日志目录 + JAVA_OPTS 指定 -Duser.home。
EXPOSE 8080 8081 8082 8083
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
