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

## Technology Stack

- **Kotlin 1.9.21** - JVM language
- **Ktor 2.3.7** - HTTP server framework  
- **Netty** - Async HTTP server engine
- **Gradle 8.5** - Build system
- **Logback** - Logging framework