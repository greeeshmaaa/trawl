# ---- Stage 1: build the uber-jar with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q clean package

# ---- Stage 2: slim runtime image ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/trawl.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
