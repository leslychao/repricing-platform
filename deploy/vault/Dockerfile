FROM hashicorp/vault:1.20.1@sha256:50e1ecbe385742293c4a7e18c90f55d4dcc39724a05c6ab35933305fe7622cf3
COPY deploy/vault/server.hcl /vault/config/server.hcl
COPY deploy/vault/start.sh /usr/local/bin/repricer-vault
RUN mkdir -p /vault/bootstrap /vault/raft /vault/runtime && chown -R vault:vault /vault/bootstrap /vault/raft /vault/runtime
USER vault
ENTRYPOINT ["/bin/sh", "/usr/local/bin/repricer-vault"]
