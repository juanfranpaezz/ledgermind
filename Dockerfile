# --- Build stage: compiles and packages the executable jar ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -B -ntp -DskipTests clean package

# --- Run stage: only the JRE + the jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/ledgermind-*.jar app.jar
# A payments ledger lives in UTC (consistent with TimeZone.setDefault(UTC) in main()).
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
# REQUIRES LEDGERMIND_API_KEYS_FILE: without that variable (or with a missing or malformed file) the process exits
# with 1 (fail-closed). The image ships no key file, on purpose; mount it at runtime, e.g.
#   -v /path/api-keys.txt:/run/ledgermind/api-keys:ro -e LEDGERMIND_API_KEYS_FILE=/run/ledgermind/api-keys
# (an empty file = only the five anonymous /api/demo/* endpoints; any other /api call gets 401).
# With that, this image is the browsable demo the README announces: the 'demo' profile loads the auditor
# scenario (DemoSupportController) and the embedded Authorization Server. Explicit default, overridable
# at runtime with -e SPRING_PROFILES_ACTIVE=... (an artifact is validated by its behaviour, not by compiling).
ENV SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
