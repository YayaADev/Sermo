FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy entire workspace (includes SermoModels dependency)
COPY . .

# Change to Sermo directory and build
WORKDIR /app/Sermo
RUN gradle build --no-daemon

# Stage 2: Runtime stage
FROM openjdk:21-jdk-slim

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r sermo && useradd -r -g sermo sermo

WORKDIR /app

# Copy JAR from Sermo build
COPY --from=builder /app/Sermo/build/libs/*.jar app.jar

# Create directories and set permissions
RUN mkdir -p /app/credentials /app/logs && chown -R sermo:sermo /app

USER sermo

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

CMD ["java", "-jar", "app.jar"]