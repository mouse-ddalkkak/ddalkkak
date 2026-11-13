# 빌드 컨텍스트는 저장소 루트 (docker-compose.yml 참고)
# 1) 화면(web) 빌드 → 2) 화면을 static 으로 넣어 Spring jar 빌드 → 3) 실행
# 빌드 단계는 빌드하는 기계의 플랫폼에서 돈다(dist · jar 는 플랫폼과 상관없음) — arm64 이미지도 에뮬레이션 없이 빌드된다
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# 가짜 모드(fake.ts)가 ../contracts 를 읽어 tsc 타입 검사에 필요하다. 배포 번들에는 들어가지 않는다 (docs/web.md §6)
COPY contracts/ /contracts/
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS server
WORKDIR /server
COPY server/ ./
COPY --from=web /web/dist ./src/main/resources/static
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# 샘플 order-api — 설치기 --sample 이 이 이미지로 띄운다(start.sh, -227). 스타터킷은 composite build 라 같이 넣는다
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS sample
WORKDIR /src
COPY starter-kit/ starter-kit/
COPY examples/order-api/ examples/order-api/
RUN cd examples/order-api && chmod +x gradlew ../../starter-kit/gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
# 코드 가져오기 (docs/run-flow.md §5): git clone · jar 에서 코드 복원(Vineflower)
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
ADD --checksum=sha256:1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d \
    https://repo1.maven.org/maven2/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar /opt/vineflower.jar
# 검사 도구를 형제 컨테이너로 띄우는 docker CLI (docs/scan.md §2). 소켓은 compose 가 붙인다
COPY --from=docker:29.8.1-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
COPY --from=server /server/build/libs/*.jar app.jar
COPY --from=sample /src/examples/order-api/build/libs/order-api-*[0-9].jar order-api.jar
# 비밀번호 분실: docker exec ddalkkak ddalkkak reset-password (README Compose 예시는 docker compose exec ddalkkak …, 소스 빌드 compose 는 … exec server …)
RUN printf '#!/bin/sh\nexec java -jar /app/app.jar "$@"\n' > /usr/local/bin/ddalkkak && chmod +x /usr/local/bin/ddalkkak
COPY server/start.sh /app/start.sh
RUN chmod +x /app/start.sh
# 출시 이미지(.github/workflows/release.yml)에만 버전이 들어간다. start.sh 가 같은 버전의 ai 이미지를 띄운다. 소스 빌드는 비어 있다
ARG DDALKKAK_VERSION=
ENV DDALKKAK_VERSION=$DDALKKAK_VERSION
EXPOSE 7777
ENTRYPOINT ["/app/start.sh"]
