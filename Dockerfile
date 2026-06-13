# --- Build stage: compila y empaqueta el jar ejecutable ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -B -ntp -DskipTests clean package

# --- Run stage: solo el JRE + el jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/ledgermind-*.jar app.jar
# Un ledger de pagos vive en UTC (coherente con TimeZone.setDefault(UTC) en main()).
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
# Esta imagen ES la demo navegable que anuncia el README: el perfil 'demo' carga el escenario de
# auditor (DemoSupportController) y el Authorization Server embebido. Default explicito y overridable
# en runtime con -e SPRING_PROFILES_ACTIVE=... (un artefacto se valida por su comportamiento, no por compilar).
ENV SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
