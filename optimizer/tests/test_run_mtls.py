import ssl

from run_mtls import ssl_kwargs


def test_requires_client_certs_when_server_certs_exist(tmp_path):
    for name in ("optimizer.pem", "optimizer-key.pem", "ca.pem"):
        (tmp_path / name).write_text("x")

    kwargs = ssl_kwargs(str(tmp_path))

    assert kwargs["ssl_cert_reqs"] == ssl.CERT_REQUIRED
    assert kwargs["ssl_ca_certs"] == str(tmp_path / "ca.pem")
    assert kwargs["ssl_certfile"] == str(tmp_path / "optimizer.pem")
    assert kwargs["ssl_keyfile"] == str(tmp_path / "optimizer-key.pem")


def test_plain_http_without_server_certs(tmp_path):
    assert ssl_kwargs(str(tmp_path)) == {}
