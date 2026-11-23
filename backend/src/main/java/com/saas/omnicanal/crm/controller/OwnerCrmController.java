package com.saas.omnicanal.crm.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/crm")
class OwnerCrmController {
    private final JdbcTemplate db;

    OwnerCrmController(JdbcTemplate db) {
        this.db = db;
    }

    enum Stage { NEW, IN_PROGRESS, WON, LOST }
    record Contact(String chat, Stage stage, long revision, OffsetDateTime lastReceived, long messages, LocalDate followUpDate) {}
    record Page(List<Contact> contacts, boolean more) {}
    record Message(String id, String text, OffsetDateTime receivedAt) {}
    record Edit(@NotBlank @Size(max=160) String chat, @NotNull Stage stage, @NotNull @PositiveOrZero Long revision) {}
    record Saved(Stage stage, long revision) {}

    @GetMapping("/contacts")
    Page contacts(@PathVariable UUID store, Principal owner, @RequestParam(defaultValue="0") int page,
                  @RequestParam(defaultValue="false") boolean followUps) {
        requireOwner(store,owner);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT m.chat_id,coalesce(s.stage,'NEW'),coalesce(s.revision,0),max(m.received_at),count(*),s.follow_up_date
                  FROM whatsapp_inbound_message m LEFT JOIN crm_contact_stage s USING (tenant_id,chat_id)
                 WHERE m.tenant_id=? AND (NOT ? OR s.follow_up_date IS NOT NULL)
                 GROUP BY m.chat_id,s.stage,s.revision,s.follow_up_date
                 ORDER BY CASE WHEN ? THEN s.follow_up_date END ASC,max(m.received_at) DESC,m.chat_id
                 LIMIT 21 OFFSET ?
                """, (rs,row)->new Contact(rs.getString(1),Stage.valueOf(rs.getString(2)),rs.getLong(3),
                        rs.getObject(4,OffsetDateTime.class),rs.getLong(5),rs.getObject(6,LocalDate.class)),store,followUps,followUps,page*20L);
        return new Page(rows.subList(0,Math.min(20,rows.size())),rows.size()>20);
    }

    @GetMapping("/messages")
    List<Message> messages(@PathVariable UUID store, Principal owner, @RequestParam String chat) {
        requireOwner(store,owner);
        return db.query("""
                SELECT message_id,body,received_at FROM (
                    SELECT message_id,body,received_at FROM whatsapp_inbound_message
                     WHERE tenant_id=? AND chat_id=? ORDER BY received_at DESC,message_id DESC LIMIT 5
                ) recent ORDER BY received_at,message_id
                """,(rs,row)->new Message(rs.getString(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class)),store,chat);
    }

    record ChatLine(String key, String text, OffsetDateTime at, String status) {}
    record Conversation(List<ChatLine> lines, boolean more) {}

    @GetMapping("/conversation")
    Conversation conversation(@PathVariable UUID store, Principal owner, @RequestParam String chat,
                              @RequestParam(required=false) OffsetDateTime before,
                              @RequestParam(required=false) String beforeKey) {
        requireOwner(store,owner);
        if ((before == null) != (beforeKey == null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT line_key,body,at_time,delivery FROM (
                    SELECT 'in:' || message_id AS line_key,body,received_at AS at_time,NULL::text AS delivery
                      FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=?
                    UNION ALL
                    SELECT 'out:' || m.request_id::text,m.body,m.created_at,coalesce(r.status,m.status)
                      FROM whatsapp_manual_reply m LEFT JOIN whatsapp_reply_receipt r
                        ON r.tenant_id=m.tenant_id AND r.chat_id=m.chat_id AND r.provider_id=m.provider_id
                     WHERE m.tenant_id=? AND m.chat_id=?
                ) history
                 WHERE CAST(? AS timestamptz) IS NULL OR (at_time,line_key)<(CAST(? AS timestamptz),CAST(? AS text))
                 ORDER BY at_time DESC,line_key DESC LIMIT 21
                """,(rs,row)->new ChatLine(rs.getString(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class),
                        rs.getString(4)),store,chat,store,chat,before,before,beforeKey);
        var lines = new java.util.ArrayList<>(rows.subList(0,Math.min(rows.size(),20)));
        java.util.Collections.reverse(lines);
        return new Conversation(lines,rows.size()>20);
    }

    record FollowUpEdit(@NotBlank @Size(max=160) String chat, LocalDate date,
                        @NotNull @PositiveOrZero Long revision) {}
    record FollowUpSaved(LocalDate date, long revision) {}

    @PutMapping("/follow-up")
    FollowUpSaved followUp(@PathVariable UUID store, Principal owner, @Valid @RequestBody FollowUpEdit edit) {
        requireOwner(store,owner);
        int changed;
        if (edit.revision()==0L) {
            if (edit.date()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            changed = db.update("""
                    INSERT INTO crm_contact_stage(tenant_id,chat_id,stage,revision,follow_up_date)
                    SELECT ?,?,'NEW',1,? WHERE EXISTS (
                        SELECT 1 FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=?)
                    ON CONFLICT (tenant_id,chat_id) DO NOTHING
                    """,store,edit.chat(),edit.date(),store,edit.chat());
        } else {
            changed = db.update("""
                    UPDATE crm_contact_stage SET follow_up_date=?,revision=revision+1
                     WHERE tenant_id=? AND chat_id=? AND revision=?
                    """,edit.date(),store,edit.chat(),edit.revision());
        }
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new FollowUpSaved(edit.date(),edit.revision()+1);
    }

    enum Outcome { CONTACTED, NO_RESPONSE, DECLINED }
    record Completion(@NotBlank @Size(max=160) String chat, @NotNull @Positive Long revision,
                      @NotNull Outcome outcome, @NotNull @Size(max=500) String note) {}
    record HistoryEntry(long revision, LocalDate scheduledDate, OffsetDateTime completedAt, Outcome outcome, String note) {}

    @PutMapping("/follow-up/complete")
    @org.springframework.transaction.annotation.Transactional
    FollowUpSaved complete(@PathVariable UUID store, Principal owner, @Valid @RequestBody Completion input) {
        requireOwner(store,owner);
        LocalDate scheduled = db.query("""
                SELECT follow_up_date FROM crm_contact_stage WHERE tenant_id=? AND chat_id=?
                  AND revision=? AND follow_up_date IS NOT NULL FOR UPDATE
                """,(rs,row)->rs.getObject(1,LocalDate.class),store,input.chat(),input.revision())
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
        long revision = input.revision()+1;
        db.update("""
                INSERT INTO crm_follow_up_history(tenant_id,chat_id,revision,scheduled_date,outcome,note)
                VALUES (?,?,?,?,?,?)
                """,store,input.chat(),revision,scheduled,input.outcome().name(),input.note().strip());
        db.update("""
                UPDATE crm_contact_stage SET follow_up_date=NULL,revision=? WHERE tenant_id=? AND chat_id=?
                """,revision,store,input.chat());
        return new FollowUpSaved(null,revision);
    }

    @GetMapping("/follow-up/history")
    List<HistoryEntry> history(@PathVariable UUID store, Principal owner, @RequestParam String chat,
                               @RequestParam(required=false) Long before) {
        requireOwner(store,owner);
        return db.query("""
                SELECT revision,scheduled_date,completed_at,outcome,note FROM crm_follow_up_history
                 WHERE tenant_id=? AND chat_id=? AND revision<? ORDER BY revision DESC LIMIT 10
                """,(rs,row)->new HistoryEntry(rs.getLong(1),rs.getObject(2,LocalDate.class),
                        rs.getObject(3,OffsetDateTime.class),Outcome.valueOf(rs.getString(4)),rs.getString(5)),
                store,chat,before==null ? Long.MAX_VALUE : before);
    }

    @PutMapping("/stage")
    Saved save(@PathVariable UUID store, Principal owner, @Valid @RequestBody Edit edit) {
        requireOwner(store,owner);
        int changed;
        if (edit.revision()==0L) {
            changed = db.update("""
                    INSERT INTO crm_contact_stage(tenant_id,chat_id,stage,revision)
                    SELECT ?,?,?,1 WHERE EXISTS (
                        SELECT 1 FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=?)
                    ON CONFLICT (tenant_id,chat_id) DO NOTHING
                    """,store,edit.chat(),edit.stage().name(),store,edit.chat());
        } else {
            changed = db.update("""
                    UPDATE crm_contact_stage SET stage=?, revision=revision+1
                     WHERE tenant_id=? AND chat_id=? AND revision=?
                    """,edit.stage().name(),store,edit.chat(),edit.revision());
        }
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Saved(edit.stage(),edit.revision()+1);
    }

    private static void requireOwner(UUID store, Principal principal) {
        if (principal==null || !store.toString().equals(principal.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
