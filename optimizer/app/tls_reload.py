"""A uvicorn config whose TLS server cert follows the files.

cert-renewer replaces optimizer.pem and its key while the optimizer runs.
uvicorn reads them once, when each worker loads its config, so
ReloadingConfig also starts a task in that worker's event loop that checks the
files every `cert_check_interval` seconds and loads a changed pair into the
live SSLContext. New handshakes then present the new cert; open connections
keep theirs.

The cert and key are replaced one after the other, so a check can catch a
mismatched pair. A failed load_cert_chain on the live context could leave it
with the new cert and no key, so the bytes are copied once to a private temp
dir, tried on a scratch context, and only then loaded into the live one. A
bad pair keeps the current cert, and the next check tries again.

In this module, not run_mtls.py: worker processes are spawned, and the config
is pickled into them, so its class has to be importable.
"""

import asyncio
import hashlib
import logging
import os
import ssl
import tempfile

import uvicorn

logger = logging.getLogger("uvicorn.error")


class ReloadingConfig(uvicorn.Config):
    def __init__(self, *args, cert_check_interval: float = 30.0, **kwargs):
        super().__init__(*args, **kwargs)
        self.cert_check_interval = cert_check_interval
        self._loaded_fingerprint: str | None = None
        self._watcher: asyncio.Task | None = None

    def load(self) -> None:
        super().load()
        if self.ssl is None:
            return
        self._loaded_fingerprint = self._read()[0]
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return  # Loaded outside a server, e.g. in a test: nothing to watch.
        self._watcher = loop.create_task(self._watch())

    async def _watch(self) -> None:
        while True:
            await asyncio.sleep(self.cert_check_interval)
            self.reload_if_changed()

    def _read(self) -> tuple[str, bytes, bytes]:
        with open(self.ssl_certfile, "rb") as f:
            cert = f.read()
        with open(self.ssl_keyfile, "rb") as f:
            key = f.read()
        return hashlib.sha256(cert + b"\0" + key).hexdigest(), cert, key

    def reload_if_changed(self) -> bool:
        """Loads the cert and key into the live context if they changed and
        belong together. True if it did."""
        try:
            fingerprint, cert, key = self._read()
        except OSError as e:
            logger.warning("Keeping the current TLS certificate: %s", e)
            return False
        if fingerprint == self._loaded_fingerprint:
            return False
        with tempfile.TemporaryDirectory() as work:
            cert_file, key_file = os.path.join(work, "cert.pem"), os.path.join(work, "key.pem")
            for path, data in ((cert_file, cert), (key_file, key)):
                with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as f:
                    f.write(data)
            try:
                ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(cert_file, key_file, self.ssl_keyfile_password)
            except ssl.SSLError as e:
                logger.warning("Keeping the current TLS certificate: %s and its key don't load together: %s",
                               self.ssl_certfile, e)
                return False
            self.ssl.load_cert_chain(cert_file, key_file, self.ssl_keyfile_password)
        self._loaded_fingerprint = fingerprint
        logger.info("Reloaded the TLS certificate %s", self.ssl_certfile)
        return True
