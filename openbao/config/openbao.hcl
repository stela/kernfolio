ui = true

# Single-node integrated storage (Raft). OpenBao 2.7 removed the `file`
# backend. The data lives in /openbao/file because the image creates that
# directory owned by the `openbao` user (and its entrypoint re-chowns it), so
# a fresh named volume mounted there is writable. The name is historical.
storage "raft" {
  path    = "/openbao/file"
  node_id = "openbao-1"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1
}

api_addr     = "http://openbao:8200"
cluster_addr = "http://openbao:8201"

# Recommended with Raft: mlock would also pin the storage engine's mmapped files.
disable_mlock = true
