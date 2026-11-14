FROM eclipse-temurin:11-jre-jammy@sha256:0c567a8bd671331808b89a96976fabb305d57b123dec4fa62a20bfb89f2b32b9
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN mkdir -p /opt/volt/lib /var/volt \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && curl -fsSL https://repo.maven.apache.org/maven2/org/voltdb/voltdb/10.1.1/voltdb-10.1.1.jar -o /opt/volt/lib/voltdb.jar \
    && echo "bf3e82c1b3211006da00a0d59f46dc7300fd44bc534cd4af88f8f3948c53a8ff  /opt/volt/lib/voltdb.jar" | sha256sum -c - \
    && curl -fsSL https://codeload.github.com/DBOS-project/voltdb/tar.gz/663131f448f184b2be52a1b1a4478a63c476b4c6 -o /tmp/volt-source.tar.gz \
    && tar -xzf /tmp/volt-source.tar.gz --strip-components=2 --directory /opt/volt/lib --wildcards --no-wildcards-match-slash '*/lib/*.jar' \
    && rm /opt/volt/lib/log4j-1.2.16.jar /tmp/volt-source.tar.gz \
    && curl -fsSL https://repo.maven.apache.org/maven2/ch/qos/reload4j/reload4j/1.2.26/reload4j-1.2.26.jar -o /opt/volt/lib/logging.jar
COPY deployment.xml /opt/volt/deployment.xml
WORKDIR /var/volt
ENTRYPOINT ["java", "-Xms1g", "-Xmx4g", "-XX:ActiveProcessorCount=4", "-cp", "/opt/volt/lib/*", "org.voltdb.VoltDB", "create", "host", "localhost", "deployment", "/opt/volt/deployment.xml"]
