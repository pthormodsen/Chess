# Node build stage for frontend
FROM node:20 AS node-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
COPY frontend/vite.config.js ./
COPY frontend/index.html ./
COPY frontend/src ./src
RUN npm ci && npm run build

# Maven build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# Copy frontend build output into Spring Boot static resources.
COPY --from=node-build /workspace/frontend/dist ./src/main/resources/static
RUN mvn -DskipTests package -q

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends stockfish \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/*.jar app.jar
ENV STOCKFISH_PATH=/usr/games/stockfish
ENV SERVER_PORT=8081
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
