# 1단계: Gradle로 실행 가능한 jar 빌드 (테스트는 CI의 build-test 워크플로가 맡는다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 소스가 바뀌어도 의존성 레이어가 캐시되도록 먼저 내려받는다.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain')" /workspace/app.jar

# 2단계: 실행 이미지, 비root 사용자로 실행
# Temurin JRE 이미지에는 jdk.random 모듈이 없어 업비트 수집기의 L32X64MixRandom 생성에 실패하므로 JDK 이미지를 쓴다.
FROM eclipse-temurin:21-jdk
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
# EC2 한 대에 DB·Redis·nginx와 함께 올라가므로 힙을 컨테이너 메모리의 일부로 제한한다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["java", "-jar", "app.jar"]
