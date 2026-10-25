import asyncio
import shutil
import ssl
import subprocess

import pytest

from app.tls_reload import ReloadingConfig

pytestmark = pytest.mark.skipif(shutil.which("openssl") is None, reason="needs the openssl CLI to make certs")


def make_pair(directory, name):
    cert, key = directory / f"{name}.pem", directory / f"{name}-key.pem"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
         "-keyout", str(key), "-out", str(cert), "-subj", "/CN=optimizer", "-days", "1"],
        check=True, capture_output=True,
    )
    return cert.read_bytes(), key.read_bytes()


def served_cert(server_ctx: ssl.SSLContext) -> bytes:
    """DER of the cert a new handshake against server_ctx presents, in memory."""
    client_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    client_ctx.check_hostname = False
    client_ctx.verify_mode = ssl.CERT_NONE
    c_in, c_out, s_in, s_out = (ssl.MemoryBIO() for _ in range(4))
    client = client_ctx.wrap_bio(c_in, c_out)
    server = server_ctx.wrap_bio(s_in, s_out, server_side=True)
    for _ in range(10):
        for side in (client, server):
            try:
                side.do_handshake()
            except ssl.SSLWantReadError:
                pass
        s_in.write(c_out.read())
        c_in.write(s_out.read())
    return client.getpeercert(binary_form=True)


def der(pem: bytes) -> bytes:
    return ssl.PEM_cert_to_DER_cert(pem.decode())


@pytest.fixture
def pairs(tmp_path):
    """Two cert/key pairs, A installed as optimizer.pem / optimizer-key.pem."""
    a, b = make_pair(tmp_path, "a"), make_pair(tmp_path, "b")
    install(tmp_path, *a)
    return tmp_path, a, b


def install(directory, cert: bytes | None = None, key: bytes | None = None):
    if key is not None:
        (directory / "optimizer-key.pem").write_bytes(key)
    if cert is not None:
        (directory / "optimizer.pem").write_bytes(cert)


def config_for(directory, **kwargs) -> ReloadingConfig:
    config = ReloadingConfig(
        "app.main:app",
        ws="none",
        ssl_certfile=str(directory / "optimizer.pem"),
        ssl_keyfile=str(directory / "optimizer-key.pem"),
        **kwargs,
    )
    config.load()
    return config


def test_reloads_a_renewed_cert_into_the_live_context(pairs):
    directory, a, b = pairs
    config = config_for(directory)
    live = config.ssl
    assert served_cert(live) == der(a[0])

    install(directory, *b)

    assert config.reload_if_changed()
    assert config.ssl is live
    assert served_cert(live) == der(b[0])


def test_unchanged_files_are_not_reloaded(pairs):
    directory, _, _ = pairs
    assert not config_for(directory).reload_if_changed()


def test_a_mismatched_pair_keeps_the_current_cert_until_the_key_follows(pairs):
    directory, a, b = pairs
    config = config_for(directory)

    install(directory, cert=b[0])  # caught between the cert and the key

    assert not config.reload_if_changed()
    assert served_cert(config.ssl) == der(a[0])

    install(directory, key=b[1])

    assert config.reload_if_changed()
    assert served_cert(config.ssl) == der(b[0])


def test_a_missing_file_keeps_the_current_cert(pairs):
    directory, a, _ = pairs
    config = config_for(directory)
    (directory / "optimizer-key.pem").unlink()

    assert not config.reload_if_changed()
    assert served_cert(config.ssl) == der(a[0])


def test_loading_in_a_worker_event_loop_starts_the_watcher(pairs):
    directory, _, b = pairs

    async def scenario():
        config = config_for(directory, cert_check_interval=0.05)
        assert config._watcher is not None
        install(directory, *b)
        try:
            for _ in range(40):
                await asyncio.sleep(0.05)
                if served_cert(config.ssl) == der(b[0]):
                    return True
            return False
        finally:
            config._watcher.cancel()

    assert asyncio.run(scenario())


def test_plain_http_config_has_nothing_to_watch():
    config = ReloadingConfig("app.main:app", ws="none")
    config.load()
    assert config.ssl is None
    assert not hasattr(config, "_watcher") or config._watcher is None
