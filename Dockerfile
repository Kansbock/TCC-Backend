# Build multi-stage: o estágio "build" carrega o JDK completo e o Gradle; a
# imagem final leva apenas o JRE e o jar, reduzindo superfície de ataque e
# tamanho. Java 25 é exigido pelo toolchain em build.gradle — nenhuma
# plataforma gerenciada da AWS (Lambda, Beanstalk, App Runner runtime) oferece
# esse runtime hoje, por isso o deploy é obrigatoriamente via container.
FROM eclipse-temurin:25-jdk-jammy AS build

WORKDIR /workspace

# Copiar primeiro apenas os arquivos de build permite que o Docker reaproveite
# a camada de download de dependências quando só o código-fonte muda.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

# O diretório build/libs contém dois jars: o executável e o "-plain.jar" (sem
# dependências, não executável). Selecionar explicitamente evita copiar o
# errado, que falharia em runtime com "no main manifest attribute".
RUN cp build/libs/*-SNAPSHOT.jar /workspace/app.jar


FROM eclipse-temurin:25-jre-jammy AS runtime

# Executar como usuário não-root: se a aplicação for comprometida, o atacante
# não recebe privilégios administrativos dentro do contêiner.
RUN groupadd --system spring && useradd --system --gid spring spring

WORKDIR /app

# O adapter implementa a Lambda Runtime API para que o Spring Boot continue
# recebendo HTTP normalmente, sem handler Java específico para Lambda.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.1 /lambda-adapter /opt/extensions/lambda-adapter
COPY --from=build --chown=spring:spring /workspace/app.jar app.jar

USER spring

EXPOSE 8080

# MaxRAMPercentage ajusta a heap ao limite de memória do contêiner: sem isso a
# JVM dimensiona a heap pela RAM total do host e pode ser morta pelo OOM killer
# ao ultrapassar o limite da tarefa.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PATH=/health \
    AWS_LWA_ASYNC_INIT=true

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
