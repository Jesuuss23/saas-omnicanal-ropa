package com.saas.omnicanal.notifications.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmailLogRepository {
    private final JdbcTemplate db;

    public EmailLogRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void log(String to, String template, String subject, String status, String providerId, String error, String body) {
        db.update("""
                INSERT INTO email_log(recipient, template, subject, status, provider_id, error, body)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, to, template, truncate(subject, 200), status, providerId, truncate(error, 300), body);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
