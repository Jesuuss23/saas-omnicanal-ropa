package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.whatsapp.model.CloudApiCredentialRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CloudApiCredentialRepository {
    private static final String COLUMNS = """
            tenant_id,phone_number_id,waba_id,access_token_ciphertext,app_secret_ciphertext,display_phone_number,
            verified_name,quality_rating,status,last_error,verified_at,revision""";
    private static final RowMapper<CloudApiCredentialRecord> ROW = (rs, n) -> new CloudApiCredentialRecord(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getBytes(5),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
            rs.getObject(11, OffsetDateTime.class), rs.getLong(12));

    private final JdbcTemplate db;

    public CloudApiCredentialRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<CloudApiCredentialRecord> find(UUID tenant) {
        return db.query("SELECT " + COLUMNS + " FROM whatsapp_cloud_credential WHERE tenant_id=?", ROW, tenant)
                .stream().findFirst();
    }

    public Optional<CloudApiCredentialRecord> findForUpdate(UUID tenant) {
        return db.query("SELECT " + COLUMNS + " FROM whatsapp_cloud_credential WHERE tenant_id=? FOR UPDATE", ROW, tenant)
                .stream().findFirst();
    }

    public boolean phoneNumberTakenByOther(UUID tenant, String phoneNumberId) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM whatsapp_cloud_credential WHERE phone_number_id=? AND tenant_id<>?)",
                Boolean.class, phoneNumberId, tenant));
    }

    public void insert(UUID tenant, String phoneNumberId, String wabaId, byte[] token, byte[] appSecret) {
        db.update("""
                INSERT INTO whatsapp_cloud_credential(tenant_id,phone_number_id,waba_id,access_token_ciphertext,app_secret_ciphertext)
                VALUES (?,?,?,?,?)
                """, tenant, phoneNumberId, wabaId, token, appSecret);
    }

    /** Actualiza con control optimista por revisión; el estado vuelve a PENDING hasta verificar. */
    public boolean update(UUID tenant, long revision, String phoneNumberId, String wabaId, byte[] token, byte[] appSecret) {
        return db.update("""
                UPDATE whatsapp_cloud_credential SET phone_number_id=?, waba_id=?, access_token_ciphertext=?,
                       app_secret_ciphertext=?, status='PENDING', last_error=NULL, verified_at=NULL,
                       display_phone_number=NULL, verified_name=NULL, quality_rating=NULL,
                       revision=revision+1, updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, phoneNumberId, wabaId, token, appSecret, tenant, revision) == 1;
    }

    public void markVerified(UUID tenant, long revision, String display, String name, String quality) {
        db.update("""
                UPDATE whatsapp_cloud_credential SET status='VERIFIED', display_phone_number=?, verified_name=?,
                       quality_rating=?, last_error=NULL, verified_at=now(), updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, display, name, quality, tenant, revision);
    }

    public void markInvalid(UUID tenant, long revision, String error) {
        db.update("""
                UPDATE whatsapp_cloud_credential SET status='INVALID', last_error=left(?,500), updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, error, tenant, revision);
    }

    public void delete(UUID tenant) {
        db.update("DELETE FROM whatsapp_cloud_credential WHERE tenant_id=?", tenant);
    }
}
