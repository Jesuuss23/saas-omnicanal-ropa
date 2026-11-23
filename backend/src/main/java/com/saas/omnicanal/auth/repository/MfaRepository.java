package com.saas.omnicanal.auth.repository;

import com.saas.omnicanal.auth.model.EnrollmentRecord;
import com.saas.omnicanal.auth.model.MfaSubject;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MfaRepository {
    private final JdbcTemplate db;

    public MfaRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<EnrollmentRecord> find(MfaSubject type, String subject) {
        return db.query("""
                SELECT secret_enc, pending_secret_enc, pending_created_at, confirmed_at, last_step
                FROM mfa_enrollment WHERE subject_type = ? AND subject_id = ?
                """, (rs, row) -> new EnrollmentRecord(rs.getBytes(1), rs.getBytes(2), instant(rs.getTimestamp(3)),
                instant(rs.getTimestamp(4)), rs.getLong(5)), type.name(), subject).stream().findFirst();
    }

    public void savePending(MfaSubject type, String subject, byte[] pending) {
        db.update("""
                INSERT INTO mfa_enrollment(subject_type, subject_id, pending_secret_enc, pending_created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (subject_type, subject_id) DO UPDATE
                SET pending_secret_enc = EXCLUDED.pending_secret_enc, pending_created_at = now()
                """, type.name(), subject, pending);
    }

    /** Pasa el secreto pendiente a activo, siempre que siga siendo el mismo que se verificó. */
    public boolean promotePending(MfaSubject type, String subject, byte[] expectedPending, long step) {
        return db.update("""
                UPDATE mfa_enrollment SET secret_enc = pending_secret_enc, pending_secret_enc = NULL,
                    pending_created_at = NULL, confirmed_at = now(), last_step = ?
                WHERE subject_type = ? AND subject_id = ? AND pending_secret_enc = ?
                """, step, type.name(), subject, expectedPending) == 1;
    }

    /** Registra el paso usado; falla si ya se usó ese paso o uno posterior (código repetido). */
    public boolean useStep(MfaSubject type, String subject, long step) {
        return db.update("""
                UPDATE mfa_enrollment SET last_step = ? WHERE subject_type = ? AND subject_id = ? AND last_step < ?
                """, step, type.name(), subject, step) == 1;
    }

    public void replaceRecoveryCodes(MfaSubject type, String subject, Collection<String> hashes) {
        db.update("DELETE FROM mfa_recovery_code WHERE subject_type = ? AND subject_id = ?", type.name(), subject);
        db.batchUpdate("INSERT INTO mfa_recovery_code(subject_type, subject_id, code_hash) VALUES (?, ?, ?)",
                hashes.stream().map(h -> new Object[]{type.name(), subject, h}).toList());
    }

    public boolean useRecoveryCode(MfaSubject type, String subject, String hash) {
        return db.update("""
                UPDATE mfa_recovery_code SET used_at = now()
                WHERE subject_type = ? AND subject_id = ? AND code_hash = ? AND used_at IS NULL
                """, type.name(), subject, hash) == 1;
    }

    public int recoveryCodesLeft(MfaSubject type, String subject) {
        Integer left = db.queryForObject("""
                SELECT count(*) FROM mfa_recovery_code WHERE subject_type = ? AND subject_id = ? AND used_at IS NULL
                """, Integer.class, type.name(), subject);
        return left == null ? 0 : left;
    }

    public void delete(MfaSubject type, String subject) {
        db.update("DELETE FROM mfa_enrollment WHERE subject_type = ? AND subject_id = ?", type.name(), subject);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
