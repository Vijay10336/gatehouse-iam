FROM node:24-alpine AS ui
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS java
WORKDIR /src
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=ui /ui/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -q verify

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN groupadd --system gatehouse && useradd --system --gid gatehouse gatehouse
COPY --from=java /src/target/gatehouse-0.1.0.jar app.jar
USER gatehouse
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
