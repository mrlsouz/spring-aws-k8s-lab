# Multi-stage build: o estágio "build" tem JDK + Maven e fica para trás;
# a imagem final só leva JRE + o jar (menor e com menos superfície de ataque).

# ---- Estágio 1: compilar ----
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
# Copia primeiro só o que define dependências: enquanto o pom não mudar,
# o Docker reaproveita esta camada e não baixa tudo de novo.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Testes rodam no CI (precisam de Docker para o Testcontainers), não no build da imagem.
RUN ./mvnw -B -q package -DskipTests

# ---- Estágio 2: rodar ----
FROM eclipse-temurin:25-jre-noble
# hadolint ignore=DL3008 # Keep versions unpinned so rebuilds can receive future security fixes.
RUN apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 install --only-upgrade --no-install-recommends -y apt libapt-pkg6.0t64 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Usuário sem privilégios: se a aplicação for comprometida, não é root no container.
RUN useradd --system --uid 10001 app
COPY --from=build /src/target/notes-api-*.jar app.jar
USER 10001
EXPOSE 8080
# MaxRAMPercentage: a JVM dimensiona o heap pelo limite de memória do container
# (importante no Kubernetes, onde o pod tem limits).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
