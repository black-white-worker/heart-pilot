FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /workspace

# 阿里云 Maven 镜像：只镜像 central，parent/依赖都从这里拉
COPY settings.xml /root/.m2/settings.xml

# 依赖层单独一层，配合 cache mount：改代码不会重新下载依赖
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -B -DskipTests package

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN addgroup -S heartpilot && adduser -S heartpilot -G heartpilot && mkdir -p /app/data/files && chown -R heartpilot:heartpilot /app
COPY --from=build /workspace/target/heart-pilot-backend-0.0.1-SNAPSHOT.jar app.jar
USER heartpilot
EXPOSE 8123
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar","--spring.profiles.active=prod"]
