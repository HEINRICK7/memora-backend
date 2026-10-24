FROM golang:1.24.8-bookworm AS build

ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
RUN go install -trimpath -ldflags="-s -w" github.com/minio/minio@${MINIO_RELEASE}

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 minio \
    && useradd --system --uid 10001 --gid minio --home-dir /home/minio --create-home --shell /usr/sbin/nologin minio \
    && mkdir -p /data \
    && chown -R 10001:10001 /data /home/minio

COPY --from=build /go/bin/minio /usr/local/bin/minio

ENV HOME=/home/minio
VOLUME ["/data"]
EXPOSE 9000 9001
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
  CMD curl -fsS http://127.0.0.1:9000/minio/health/ready || exit 1
