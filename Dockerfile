# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primeiro copia e resolve as dependências para aproveitar o cache de camadas.
COPY pom.xml .
COPY axonbase-common/pom.xml axonbase-common/
COPY axonbase-value/pom.xml axonbase-value/
COPY axonbase-parser/pom.xml axonbase-parser/
COPY axonbase-core/pom.xml axonbase-core/
COPY axonbase-server/pom.xml axonbase-server/
COPY axonbase-sdk-java/pom.xml axonbase-sdk-java/
COPY axonbase-spring-data/pom.xml axonbase-spring-data/
COPY axonbase-cli/pom.xml axonbase-cli/
COPY axonbase-jdbc/pom.xml axonbase-jdbc/
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -pl axonbase-core,axonbase-server -am dependency:resolve -Dgpg.skip=true -Dmaven.javadoc.skip=true || true

COPY axonbase-common/src axonbase-common/src
COPY axonbase-value/src axonbase-value/src
COPY axonbase-parser/src axonbase-parser/src
COPY axonbase-core/src axonbase-core/src
COPY axonbase-server/src axonbase-server/src
COPY axonbase-sdk-java/src axonbase-sdk-java/src
COPY axonbase-spring-data/src axonbase-spring-data/src
COPY axonbase-cli/src axonbase-cli/src
COPY axonbase-jdbc/src axonbase-jdbc/src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -pl axonbase-server -am install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true -T 1C \
 && mvn -q -pl axonbase-server dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/deps -Dgpg.skip=true -Dmaven.javadoc.skip=true

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/axonbase-common/target/classes /app/classes/axonbase-common
COPY --from=build /build/axonbase-value/target/classes /app/classes/axonbase-value
COPY --from=build /build/axonbase-parser/target/classes /app/classes/axonbase-parser
COPY --from=build /build/axonbase-core/target/classes /app/classes/axonbase-core
COPY --from=build /build/axonbase-server/target/classes /app/classes/axonbase-server
COPY --from=build /build/deps /app/deps
EXPOSE 8000
ENTRYPOINT ["sh", "-c", "java -cp '/app/classes/axonbase-common:/app/classes/axonbase-value:/app/classes/axonbase-parser:/app/classes/axonbase-core:/app/classes/axonbase-server:/app/deps/*' com.axonbase.server.Main \"$@\"", "--"]
CMD ["start", "--path", "/data", "--port", "8000", "--bind", "0.0.0.0"]
