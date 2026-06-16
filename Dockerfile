# 빌드
FROM amazoncorretto:25 AS build

WORKDIR /workspace

RUN yum install -y findutils && yum clean all

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle

RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src

RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar app.jar

# 런타임
FROM amazoncorretto:25 AS runtime

WORKDIR /app

COPY --from=build --chown=1000:1000 /workspace/app.jar app.jar
USER 1000

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -Djava.net.preferIPv4Stack=true -XX:MaxRAMPercentage=70.0 -jar app.jar"]
