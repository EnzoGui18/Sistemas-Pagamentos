FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S fluxo && adduser -S fluxo -G fluxo
COPY --from=build /workspace/target/fluxo-0.0.1-SNAPSHOT.jar app.jar
USER fluxo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
