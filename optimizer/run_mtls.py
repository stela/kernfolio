"""Launch Uvicorn with mTLS if certificates are available."""

import os
import ssl

import uvicorn

CERT_DIR = os.getenv("OPENBAO_CERT_DIR", "/openbao/certs")


def ssl_kwargs(cert_dir: str) -> dict:
    """Uvicorn TLS settings: serve optimizer.pem and require a client cert
    issued by the OpenBao CA. Any cert from that CA is accepted; uvicorn
    doesn't hand the peer cert to the app, so there's no CN allowlist.

    Without the cert files (bare `uv run` during development) it returns {}
    and the server speaks plain HTTP.
    """
    cert_file = os.path.join(cert_dir, "optimizer.pem")
    key_file = os.path.join(cert_dir, "optimizer-key.pem")
    if not (os.path.exists(cert_file) and os.path.exists(key_file)):
        return {}
    return {
        "ssl_certfile": cert_file,
        "ssl_keyfile": key_file,
        "ssl_ca_certs": os.path.join(cert_dir, "ca.pem"),
        "ssl_cert_reqs": ssl.CERT_REQUIRED,
    }


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        workers=2,
        **ssl_kwargs(CERT_DIR),
    )
