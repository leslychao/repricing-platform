#!/bin/sh
set -eu
umask 077
export VAULT_ADDR=http://127.0.0.1:8200
vault server -config=/vault/config/server.hcl &
server_pid=$!
trap 'kill -TERM "$server_pid" 2>/dev/null || true; wait "$server_pid" || true' TERM INT EXIT
attempt=0
while ! vault status -format=json > /tmp/vault-status.json 2>/dev/null; do
  if grep -q '"initialized"' /tmp/vault-status.json; then break; fi
  attempt=$((attempt + 1))
  [ "$attempt" -lt 60 ] || { echo 'Vault did not start' >&2; exit 1; }
  sleep 1
done
if grep -q '"initialized": false' /tmp/vault-status.json; then
  vault operator init -key-shares=1 -key-threshold=1 > /vault/bootstrap/initialization
  sed -n 's/^Unseal Key 1: //p' /vault/bootstrap/initialization > /vault/bootstrap/unseal-key
  sed -n 's/^Initial Root Token: //p' /vault/bootstrap/initialization > /vault/bootstrap/root-token
  rm /vault/bootstrap/initialization
fi
[ -s /vault/bootstrap/unseal-key ] && [ -s /vault/bootstrap/root-token ] || {
  echo 'Vault initialization material is missing; refusing reinitialization' >&2
  exit 1
}
vault operator unseal "$(cat /vault/bootstrap/unseal-key)" > /dev/null
export VAULT_TOKEN="$(cat /vault/bootstrap/root-token)"
if ! vault secrets list -format=json | grep -q '"secret/"'; then
  vault secrets enable -path=secret kv-v2 > /dev/null
fi
vault policy write repricer - > /dev/null <<'POLICY'
path "secret/data/repricer/*" { capabilities = ["create", "read", "update"] }
path "secret/metadata/repricer/*" { capabilities = ["read", "list"] }
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self" { capabilities = ["update"] }
POLICY
if [ ! -s /vault/runtime/vault-token ] || ! vault token lookup "$(cat /vault/runtime/vault-token)" > /dev/null 2>&1; then
  vault token create -field=token -policy=repricer -no-default-policy -orphan -period=24h > /vault/runtime/vault-token.new
  chmod 444 /vault/runtime/vault-token.new
  mv /vault/runtime/vault-token.new /vault/runtime/vault-token
fi
chmod 755 /vault/runtime
chmod 444 /vault/runtime/vault-token
while kill -0 "$server_pid" 2>/dev/null; do
  vault token renew "$(cat /vault/runtime/vault-token)" > /dev/null
  sleep 3600 &
  wait $! || true
done
wait "$server_pid"
