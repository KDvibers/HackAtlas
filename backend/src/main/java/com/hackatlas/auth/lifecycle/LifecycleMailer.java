package com.hackatlas.auth.lifecycle;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** SMTP is confined to the loopback sink by LifecycleSettings; no relay/provider is configured. */
public final class LifecycleMailer {
    private final JavaMailSenderImpl sender;

    public LifecycleMailer(LifecycleSettings settings) {
        sender = new JavaMailSenderImpl();
        sender.setHost(settings.smtpHost());
        sender.setPort(settings.smtpPort());
        sender.setDefaultEncoding("UTF-8");
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        properties.setProperty("mail.smtp.writetimeout", "2000");
        properties.setProperty("mail.smtp.localhost", "localhost");
        properties.setProperty("mail.debug", "false");
    }

    public void code(String address, EmailActionPurpose purpose, EmailActionCrypto.Challenge challenge) {
        var message = message(address);
        message.setSubject(purpose == EmailActionPurpose.EMAIL_VERIFICATION
                ? "Подтверждение почты HackAtlas" : "Восстановление пароля HackAtlas");
        message.setText("Запрос: " + challenge.challengeId().substring(37) + "\nКод: " + challenge.code()
                + "\nКод действует 10 минут. Если вы не запрашивали действие, не сообщайте код другим.");
        sender.send(message);
    }

    public void passwordChanged(String address) {
        var message = message(address);
        message.setSubject("Пароль HackAtlas изменён");
        message.setText("Пароль изменён. Прежние сессии отозваны. Если это были не вы, восстановите доступ заново.");
        sender.send(message);
    }

    private static SimpleMailMessage message(String address) {
        var message = new SimpleMailMessage();
        message.setFrom("noreply@hackatlas.test");
        message.setTo(address);
        return message;
    }
}
