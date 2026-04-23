"""Launch Uvicorn with mTLS if certificates are available."""

import os

import uvicorn

CERT_DIR = os.getenv("VAULT_CERT_DIR", "/vault/certs")

ssl_kwargs = {}
cert_file = os.path.join(CERT_DIR, "optimizer.pem")
key_file = os.path.join(CERT_DIR, "optimizer-key.pem")
ca_file = os.path.join(CERT_DIR, "ca.pem")

if os.path.exists(cert_file) and os.path.exists(key_file):
    ssl_kwargs = {
        "ssl_certfile": cert_file,
        "ssl_keyfile": key_file,
        "ssl_ca_certs": ca_file,
    }

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        workers=2,
        **ssl_kwargs,
    )
