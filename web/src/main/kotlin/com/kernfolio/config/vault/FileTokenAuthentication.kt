package com.kernfolio.config.vault

import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.support.VaultToken
import java.nio.file.Files
import java.nio.file.Path

/**
 * Token auth with the token read from [file] (web's `openbao-token`, minted by
 * openbao/init/setup.sh), read again on every login, so a re-minted token is
 * picked up without a restart.
 *
 * A [TokenAuthentication] subclass on purpose: Spring Vault's session managers
 * treat those as a plain token. They self-look it up and renew it (it's
 * periodic), but never revoke it on shutdown, which would kill the token
 * openbao-init handed out.
 */
class FileTokenAuthentication(private val file: Path) : TokenAuthentication(read(file)) {

    override fun login(): VaultToken = VaultToken.of(read(file))

    override fun toString() = "FileTokenAuthentication($file)"

    companion object {
        fun read(file: Path): String =
            Files.readString(file).trim().also { check(it.isNotEmpty()) { "$file is empty" } }
    }
}
