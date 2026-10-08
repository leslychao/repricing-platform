disable_mlock = true
ui = false
api_addr = "http://vault:8200"
cluster_addr = "http://vault:8201"
storage "raft" {
  path = "/vault/raft"
  node_id = "repricer"
}
listener "tcp" {
  address = "0.0.0.0:8200"
  tls_disable = true
}
