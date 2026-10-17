FROM eclipse-temurin:17-jre

# 앱 프로세스가 root 권한을 갖지 않도록 전용 사용자로 실행한다.
# 기본 이미지의 ubuntu(1000)와 겹치지 않게 UID를 고정하며, scripts/deploy.sh의 APP_UID와 같아야 한다.
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# 업로드 이미지 임시 저장 경로(ocr.upload-dir=outputs/...)를 앱 사용자가 만들 수 있어야 한다.
RUN chown app:app /app

# 타임존 설정 (Asia/Seoul)
ENV TZ=Asia/Seoul

# 빌드된 JAR 파일 복사
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

USER app

# 기본 포트 노출 및 실행
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
