# Stage 1: Get models.jar from published sero-models image
FROM ghcr.io/yayaadev/sermo-models:latest AS models

# Stage 2: Build backend
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy models.jar from the published models image
COPY --from=models /app/models.jar /app/libs/models.jar

# Copy backend source
COPY . .

RUN gradle build --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

RUN groupadd -r sermo && useradd -r -g sermo sermo

COPY --from=builder /app/build/libs/sermo-1.0.0.jar app.jar

# Create required directories with proper permissions
RUN mkdir -p /app/credentials /app/logs /app/audio-buffers && \
    chown -R sermo:sermo /app

# Set system limits for real-time audio processing
RUN echo "sermo soft nofile 65536" >> /etc/security/limits.conf && \
    echo "sermo hard nofile 65536" >> /etc/security/limits.conf && \
    echo "sermo soft memlock unlimited" >> /etc/security/limits.conf && \
    echo "sermo hard memlock unlimited" >> /etc/security/limits.conf

USER sermo

# Expose WebSocket port for streaming
EXPOSE 8080

# Health check with streaming-aware endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# ===== Final merged & CM3588+ tuned JVM options =====
ENV JAVA_OPTS="\
    -XX:+UnlockExperimentalVMOptions \
    -server \
    -Xms512m \
    -Xmx1536m \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC \
    -XX:NewRatio=1 \
    -XX:MaxGCPauseMillis=20 \
    -XX:+AlwaysPreTouch \
    -XX:+DisableExplicitGC \
    -XX:+UseTransparentHugePages \
    -XX:CompileThreshold=1000 \
    -XX:+TieredCompilation \
    -XX:TieredStopAtLevel=4 \
    -XX:+UseContainerSupport \
    -Djava.net.useSystemProxies=false \
    -Djava.net.preferIPv4Stack=true \
    -Dio.netty.eventLoopThreads=0 \
    -Dio.netty.allocator.type=pooled \
    -Dio.netty.allocator.maxOrder=9 \
    -Dio.netty.allocator.maxCachedBufferCapacity=32768 \
    -Dio.netty.allocator.cacheTrimInterval=600 \
    -Dio.netty.recycler.maxCapacityPerThread=0 \
    -Dio.netty.noUnsafe=false \
    -Dktor.io.useNativeTransport=true \
    -Dcom.sermo.audio.bufferSize=4096 \
    -Dcom.sermo.audio.chunkIntervalMs=50 \
    -Dcom.sermo.audio.maxConcurrentStreams=10 \
    -Dcom.sermo.websocket.maxFrameSize=65536 \
    -Dcom.sermo.websocket.bufferSize=131072 \
    -Dnetworkaddress.cache.ttl=60 \
    -Dfile.encoding=UTF-8 \
    -Djava.awt.headless=true"

# Start application with all optimizations
CMD java $JAVA_OPTS -jar app.jar
