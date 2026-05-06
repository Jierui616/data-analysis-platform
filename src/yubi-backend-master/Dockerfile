# 多阶段构建：最终镜像只含 JRE + 可执行 jar（与 pom.xml 中 artifactId/version 一致）
FROM maven:3.9.9-eclipse-temurin-8 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN mvn -e -B package -DskipTests

# 运行阶段（Spring Boot 2.7 + Java 8）
FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

ARG JAR_FILE=target/yubi-backend-0.0.1-SNAPSHOT.jar
COPY --from=builder /app/${JAR_FILE} app.jar

# application-prod.yml 中 server.port 为 8101；若用环境变量 SERVER_PORT 覆盖，请同步调整映射端口
EXPOSE 8101

ENV TZ=Asia/Shanghai

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
CMD ["--spring.profiles.active=prod"]
