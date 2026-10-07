FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

# Tests need Docker (Testcontainers), which is not available inside the image build.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre

ARG MAINTAINER
ARG REPO_URL=https://github.com/secvisogram/cms-db-migration-tool
LABEL org.opencontainers.image.authors="${MAINTAINER}"
LABEL org.opencontainers.image.source="${REPO_URL}"
LABEL org.opencontainers.image.description="One-time CouchDB to PostgreSQL data migration for csaf-cms-backend"

WORKDIR /app
COPY --from=build /build/target/csaf-couchdb-postgres-migrator.jar migrator.jar

# Arguments (--couchdb-url=..., --pg-url=..., --dry-run, ...) are appended to this command.
ENTRYPOINT ["java", "-jar", "/app/migrator.jar"]
