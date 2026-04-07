package com.kernfolio.service

import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty("spring.mail.host")
class EmailService(private val mailSender: JavaMailSender) {

    private val log = LoggerFactory.getLogger(EmailService::class.java)

    fun sendInviteCode(recipientEmail: String, code: String, baseUrl: String) {
        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message)
        helper.setTo(recipientEmail)
        helper.setSubject("Your Kernfolio Invite")
        helper.setText(
            """
            You've been invited to Kernfolio.

            Your invite code: $code

            Register at: $baseUrl/register
            """.trimIndent()
        )
        mailSender.send(message)
        log.info("Invite code email sent to {}", recipientEmail)
    }
}
