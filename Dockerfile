## Node build stage for frontend
#FROM node:20 AS node-build
#WORKDIR /workspace/frontend
#COPY frontend/package.json frontend/package-lock.json ./
#COPY frontend/vite.config.js ./
#COPY frontend/index.html ./
#COPY frontend/src ./src
#RUN npm ci && npm run build
#
## Maven build stage
#FROM maven:3.9.9-eclipse-temurin-17 AS build
#WORKDIR /workspace
#COPY pom.xml .
#COPY src ./src
## Copy frontend build output into Spring Boot static resources.
#COPY --from=node-build /workspace/frontend/dist ./src/main/resources/static
#RUN mvn -DskipTests package -q
#
## Run stage
#FROM eclipse-temurin:17-jre
#WORKDIR /app
#RUN apt-get update \
#    && apt-get install -y --no-install-recommends stockfish \
#    && rm -rf /var/lib/apt/lists/*
#COPY --from=build /workspace/target/*.jar app.jar
#ENV STOCKFISH_PATH=/usr/games/stockfish
#ENV SERVER_PORT=8081
#EXPOSE 8081
#ENTRYPOINT ["java","-jar","/app/app.jar"]


# Node build stage
FROM node:20 AS node-build
WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/vite.config.js ./
COPY frontend/index.html ./
COPY frontend/src ./src

RUN npm run build


# Maven build stage
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .

RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY src ./src

COPY --from=node-build /workspace/frontend/dist ./src/main/resources/static

RUN --mount=type=cache,target=/root/.m2 \
    mvn -DskipTests package -q


# Stockfish stage: a pinned Stockfish 18, identical on every architecture.
# - amd64 (production): the official x86-64-avx2 release binary.
# - arm64 (local dev on Apple Silicon): there is no official Linux ARM binary, so the same release's
#   source is compiled statically, with the NNUE networks the official binaries embed.
#   It is built with clang: GCC 15 builds of this release do not reproduce the official bench.
# Every download is checked against a pinned sha256, and the build fails unless `bench` reproduces the
# official sf_18 node count, i.e. the engine searches exactly like the release (same evaluations).
# (Ubuntu 24.04 here: this stage only fetches or compiles; 26.04's tar fails under amd64 emulation on Apple
#  Silicon, which would make the production build impossible to try locally.)
FROM ubuntu:24.04 AS stockfish
ARG TARGETARCH
ARG SF_TAG=sf_18
ARG SF_ASSET=stockfish-ubuntu-x86-64-avx2
ARG SF_SHA256=536c0c2c0cf06450df0bfb5e876ef0d3119950703a8f143627f990c7b5417964
ARG SF_NET_BIG=nn-c288c895ea92.nnue
ARG SF_NET_BIG_SHA256=c288c895ea924429ea9092e3f36b2b3c1f00f2a3a4c759ff7e57e79e3b43e4a7
ARG SF_NET_SMALL=nn-37f18f62d772.nnue
ARG SF_NET_SMALL_SHA256=37f18f62d772f3107e1d6aaca3898c130c3c86f2ab63e6555fbbca20635a899d
ARG SF_BENCH_NODES=2050811
WORKDIR /sf

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && if [ "${TARGETARCH:-$(dpkg --print-architecture)}" = "arm64" ]; then apt-get install -y --no-install-recommends clang g++ make; fi \
    && rm -rf /var/lib/apt/lists/*

# The release archive holds the binary and the source tree it was built from.
RUN curl -fsSL -o sf.tar "https://github.com/official-stockfish/Stockfish/releases/download/${SF_TAG}/${SF_ASSET}.tar" \
    && echo "${SF_SHA256}  sf.tar" | sha256sum -c - \
    && tar -xf sf.tar \
    && rm sf.tar

RUN set -eu; \
    arch="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    case "$arch" in \
      amd64) \
        cp "stockfish/${SF_ASSET}" /usr/local/bin/stockfish ;; \
      arm64) \
        cd stockfish/src; \
        curl -fsSL -o "$SF_NET_BIG" "https://github.com/official-stockfish/networks/raw/master/$SF_NET_BIG"; \
        curl -fsSL -o "$SF_NET_SMALL" "https://github.com/official-stockfish/networks/raw/master/$SF_NET_SMALL"; \
        printf '%s  %s\n%s  %s\n' "$SF_NET_BIG_SHA256" "$SF_NET_BIG" "$SF_NET_SMALL_SHA256" "$SF_NET_SMALL" | sha256sum -c -; \
        make -j"$(nproc)" build ARCH=armv8 COMP=clang EXTRALDFLAGS=-static; \
        cp stockfish /usr/local/bin/stockfish ;; \
      *) \
        echo "Unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    chmod 755 /usr/local/bin/stockfish

RUN /usr/local/bin/stockfish bench 2>&1 | tee /tmp/bench.txt | grep -E '^Nodes searched' \
    && grep -qE "^Nodes searched *: *${SF_BENCH_NODES}$" /tmp/bench.txt \
    || { echo "Stockfish bench does not match the official ${SF_TAG} signature (${SF_BENCH_NODES} nodes)" >&2; exit 1; }


# Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=stockfish /usr/local/bin/stockfish /usr/games/stockfish
# The official amd64 binary is dynamically linked (libstdc++): fail the build if it cannot start here.
RUN printf 'uci\nquit\n' | /usr/games/stockfish | grep -q '^uciok'
COPY --from=build /workspace/target/*.jar app.jar

ENV STOCKFISH_PATH=/usr/games/stockfish
ENV SERVER_PORT=8081

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]