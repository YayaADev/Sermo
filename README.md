# Sermo Backend

Language learning API server built with Kotlin and Ktor.

## Prerequisites

- **Java 21+** - Required for Kotlin compilation and runtime
- **Git** - For version control

## Quick Start

```bash
# Clone repository
git clone git@github.com:yourusername/sermo.git
cd sermo

# Verify Java installation
java --version  # Should show version 21+

# Build and run
./gradlew run
```

Server starts at `http://localhost:8080`

**Test endpoints:**
```bash
curl http://localhost:8080/          # "Sermo API is running!"
curl http://localhost:8080/health    # "OK"
```

## Building

### Environment Setup

**Set JAVA_HOME (if not set):**
```bash
# Find Java installation
sudo find /usr -name "java" -type f 2>/dev/null | grep bin

# Set JAVA_HOME (adjust path as needed)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Make permanent
echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64' >> ~/.zshrc
source ~/.zshrc
```

### Build Commands

```bash
# Full build (compile, test, package)
./gradlew build

# Run application
./gradlew run

# Clean build artifacts
./gradlew clean

# Run tests only
./gradlew test

# Show dependency tree
./gradlew dependencies
```

### Troubleshooting

**Build Failures:**

1. **Java path issues:**
   ```bash
   ./gradlew --stop  # Kill daemon
   export JAVA_HOME=/path/to/java-21
   ./gradlew build
   ```

2. **Dependency resolution:**
   ```bash
   ./gradlew build --refresh-dependencies
   ```

3. **Gradle cache corruption:**
   ```bash
   rm -rf ~/.gradle/caches/
   ./gradlew build
   ```

**Common Issues:**

- `JAVA_HOME` must point to JDK (not JRE)
- Gradle wrapper (`./gradlew`) must be executable: `chmod +x gradlew`
- Network issues may require `--offline` flag for cached dependencies

### Build Output

Successful build creates:
- `build/libs/sermo-1.0.0.jar` - Executable JAR
- `build/classes/` - Compiled bytecode
- `build/reports/` - Test and analysis reports

## Development

### Project Structure

```
src/
├── main/
│   ├── kotlin/com/sermo/
│   │   └── Sermo.kt              # Main application
│   └── resources/                # Configuration files
└── test/
    └── kotlin/com/sermo/         # Unit tests
```

### Adding Dependencies

Edit `build.gradle.kts`:

```kotlin
dependencies {
    implementation("group:artifact:version")
    testImplementation("test-group:test-artifact:version")
}
```

Run `./gradlew build` to download new dependencies.

### Configuration

Environment variables:
- `SERVER_PORT` - HTTP port (default: 8080)
- `SERVER_HOST` - Bind address (default: 0.0.0.0)

## Docker Development

### Building and Testing with Docker

**Build Docker image:**
```bash
# Build the Docker image locally for testing
docker build -t test-sermo-backend .

# Build with specific tag
docker build -t sermo-backend:dev .
```

**Run Docker container:**
```bash
# Run in foreground with logs
docker run -p 8080:8080 test-sermo-backend

# Run in background (detached)
docker run -d -p 8080:8080 --name sermo-test test-sermo-backend

# Run with environment variables
docker run -d -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e SERVER_HOST=0.0.0.0 \
  --name sermo-test \
  test-sermo-backend
```

**Monitor Docker container:**
```bash
# Check container status
docker ps

# View container logs
docker logs sermo-test

# Follow logs in real-time
docker logs -f sermo-test

# Check container health
docker inspect sermo-test | grep Health -A 10

# Test endpoints
curl http://localhost:8080/health
curl http://localhost:8080/
```

**Stop and cleanup:**
```bash
# Stop running container
docker stop sermo-test

# Remove container
docker rm sermo-test

# Remove image
docker rmi test-sermo-backend

# Clean up all stopped containers and unused images
docker system prune -f
```

**Debug Docker container:**
```bash
# Execute shell inside running container
docker exec -it sermo-test /bin/bash

# Check container resource usage
docker stats sermo-test

# View container filesystem
docker exec sermo-test ls -la /app
```

### Docker Multi-Stage Build

The Dockerfile uses a multi-stage build:

1. **Stage 1**: Pulls SermoModels from `ghcr.io/yayaadev/sermo-models:latest`
2. **Stage 2**: Builds Sermo backend using Gradle
3. **Stage 3**: Creates minimal runtime image with JRE

## CORS Support

The API includes CORS support for cross-origin requests (e.g., Obsidian plugins):

- **Allowed Origins**: All origins (`*`) for development
- **Allowed Methods**: GET, POST, PUT, DELETE, PATCH, OPTIONS
- **Allowed Headers**: Authorization, Content-Type, and CORS headers
- **Credentials**: Supported

**Test CORS:**
```bash
# Preflight request
curl -X OPTIONS http://localhost:8080/speech/transcribe \
  -H "Origin: app://obsidian.md" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: Content-Type"

# Actual request from Obsidian
curl -X POST http://localhost:8080/speech/transcribe \
  -H "Origin: app://obsidian.md" \
  -H "Content-Type: application/json" \
  -d '{"audio_data": "...", "language": "en-US"}'
```

## Technology Stack

- **Kotlin 1.9.21** - JVM language
- **Ktor 2.3.7** - HTTP server framework  
- **Netty** - Async HTTP server engine
- **Gradle 8.5** - Build system
- **Logback** - Logging framework
- **Docker** - Containerization and deployment