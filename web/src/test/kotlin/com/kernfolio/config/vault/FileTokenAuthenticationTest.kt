package com.kernfolio.config.vault

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileTokenAuthenticationTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `logs in with the file's token, trimmed, and re-reads it on every login`() {
        val file = Files.writeString(dir.resolve("openbao-token"), "s.first\n")
        val auth = FileTokenAuthentication(file)
        assertThat(auth.login().token).isEqualTo("s.first")

        Files.writeString(file, "s.second")
        assertThat(auth.login().token).isEqualTo("s.second")
    }

    @Test
    fun `refuses an empty token file`() {
        val file = Files.writeString(dir.resolve("openbao-token"), " \n")
        assertThatThrownBy { FileTokenAuthentication(file) }.hasMessageContaining("is empty")
    }
}
