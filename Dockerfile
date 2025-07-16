# Stage 1: Get SermoModels
FROM ghcr.io/yayaadev/sermo-models:latest AS models

# Stage 2: Build Sermo
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy SermoModels JAR from previous stage
COPY --from=models /app/models.jar /app/libs/models.jar

# Copy Sermo source
COPY . .

# Build Sermo
RUN gradle build --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r sermo && useradd -r -g sermo sermo

WORKDIR /app

# Copy built Sermo JAR
COPY --from=builder /app/build/libs/sermo-1.0.0.jar app.jar

RUN mkdir -p /app/credentials /app/logs && chown -R sermo:sermo /app

USER sermo
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

CMD ["java", "-jar", "app.jar"]