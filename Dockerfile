# Stage 1: Get SermoModels
FROM ghcr.io/yayaadev/sermo-models:latest AS models

# Stage 2: Build Sermo
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy SermoModels JAR from previous stage
COPY --from=models /app/models.jar /app/libs/models.jar

# Copy Sermo source
COPY . .

# Build Sermo with optimizations for streaming
RUN gradle build --no-daemon --parallel

# Stage 3: Runtime with Real-Time Audio Streaming Optimizations
FROM eclipse-temurin:21-jre

# Install system dependencies for audio processing and monitoring
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create sermo user and group
RUN groupadd -r sermo && useradd -r -g sermo sermo

WORKDIR /app

# Copy built Sermo JAR
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

# Configure JVM for real-time audio streaming performance
ENV JAVA_OPTS="\
    -server \
    -Xms512m -Xmx2g \
    -XX:NewRatio=1 \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=20 \
    -XX:G1HeapRegionSize=16m \
    -XX:G1NewSizePercent=30 \
    -XX:G1MaxNewSizePercent=40 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+AlwaysPreTouch \
    -XX:+DisableExplicitGC \
    -XX:+UseTransparentHugePages \
    -Dnetworkaddress.cache.ttl=60 \
    -Djava.net.preferIPv4Stack=true \
    -Dio.netty.allocator.maxCachedBufferCapacity=32768 \
    -Dio.netty.allocator.cacheTrimInterval=600 \
    -Dio.netty.recycler.maxCapacityPerThread=0 \
    -Dio.netty.noUnsafe=false \
    -Dktor.io.useNativeTransport=true \
    -Dfile.encoding=UTF-8 \
    -Djava.awt.headless=true"

# Network buffer optimizations for WebSocket streaming
ENV NET_OPTS="\
    -Djava.net.useSystemProxies=false \
    -Dio.netty.eventLoopThreads=4 \
    -Dio.netty.allocator.type=pooled \
    -Dio.netty.allocator.maxOrder=9"

# Audio processing optimizations
ENV AUDIO_OPTS="\
    -Dcom.sermo.audio.bufferSize=4096 \
    -Dcom.sermo.audio.chunkIntervalMs=100 \
    -Dcom.sermo.audio.maxConcurrentStreams=10 \
    -Dcom.sermo.websocket.maxFrameSize=65536 \
    -Dcom.sermo.websocket.bufferSize=131072"

# SBC-specific optimizations for CM3588+
ENV SBC_OPTS="\
    -XX:+UseLargePages \
    -XX:LargePageSizeInBytes=2m \
    -XX:+OptimizeStringConcat \
    -XX:+UseStringDeduplication \
    -XX:CompileThreshold=1500 \
    -XX:+TieredCompilation \
    -XX:TieredStopAtLevel=4"

# Health check with streaming-aware endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Start application with all optimizations
CMD java $JAVA_OPTS $NET_OPTS $AUDIO_OPTS $SBC_OPTS -jar app.jar
