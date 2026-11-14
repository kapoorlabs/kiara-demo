FROM eclipse-temurin:21-jdk-jammy@sha256:4e61d0b3471137e239397789661f6adeaf8db9adcfae45916b22f628c8a32dc0
WORKDIR /opt/benchmark
COPY target/kiara-lucene-benchmark-1.0-SNAPSHOT.jar benchmark.jar
ENTRYPOINT ["java", "-Xms1g", "-Xmx4g", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=4", "-Duser.timezone=UTC", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "-cp", "benchmark.jar", "com.kapoorlabs.benchmark.SqliteBenchmark"]
