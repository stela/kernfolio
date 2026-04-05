"""Vault integration helpers for the optimizer service."""

import os

import hvac


def get_vault_client() -> hvac.Client | None:
    """Create a Vault client if VAULT_ADDR is configured."""
    vault_addr = os.getenv("VAULT_ADDR")
    if not vault_addr:
        return None

    vault_token = os.getenv("VAULT_TOKEN")
    role_id = os.getenv("VAULT_ROLE_ID")
    secret_id = os.getenv("VAULT_SECRET_ID")

    client = hvac.Client(url=vault_addr, token=vault_token)

    # Use AppRole auth if role_id is set (production)
    if role_id and secret_id:
        client.auth.approle.login(role_id=role_id, secret_id=secret_id)

    if client.is_authenticated():
        return client

    return None
