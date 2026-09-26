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
# REQUIERE LEDGERMIND_API_KEYS_FILE: sin esa variable (o con un archivo inexistente o mal formado) el proceso sale
# con 1 (fail-closed). La imagen no trae ningun archivo de claves, a proposito; montalo en runtime, p. ej.
#   -v /ruta/api-keys.txt:/run/ledgermind/api-keys:ro -e LEDGERMIND_API_KEYS_FILE=/run/ledgermind/api-keys
# (un archivo vacio = solo los cinco endpoints anonimos /api/demo/*; cualquier otro /api da 401).
# Con eso, esta imagen es la demo navegable que anuncia el README: el perfil 'demo' carga el escenario de
# auditor (DemoSupportController) y el Authorization Server embebido. Default explicito y overridable
# en runtime con -e SPRING_PROFILES_ACTIVE=... (un artefacto se valida por su comportamiento, no por compilar).
ENV SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
