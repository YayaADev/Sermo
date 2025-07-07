# Multi-stage build for optimal image size and security

# Stage 1: Build stage
FROM gradle:8.5-jdk21 AS builder

# Set working directory
WORKDIR /app

# Copy gradle configuration files first (for layer caching)
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Download dependencies (cached if gradle files unchanged)
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Build the application
RUN gradle build --no-daemon

# Stage 2: Runtime stage
FROM openjdk:21-jdk-slim

# Install necessary tools
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r sermo && useradd -r -g sermo sermo

# Set working directory
WORKDIR /app

# Copy built JAR from builder stage - FIX: Use correct filename
COPY --from=builder /app/build/libs/sermo-1.0.0.jar app.jar

# Create directories for credentials and logs
RUN mkdir -p /app/credentials /app/logs
RUN chown -R sermo:sermo /app

# Switch to non-root user
USER sermo

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Run the application
CMD ["java", "-jar", "app.jar"]