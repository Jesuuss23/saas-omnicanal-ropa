package com.saas.omnicanal.platformadmin.service;

import com.saas.omnicanal.platformadmin.repository.AuditRepository;
import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Registro de acciones del superadmin. Cada cambio hecho desde la consola deja una fila en {@code platform_audit_log}. */
@Service
public class AuditService {
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_SUSPENDED = "TENANT_SUSPENDED";
    public static final String TENANT_REACTIVATED = "TENANT_REACTIVATED";
    public static final String SUBSCRIPTION_CHANGED = "SUBSCRIPTION_CHANGED";
    public static final String ENTITLEMENT_OVERRIDE = "ENTITLEMENT_OVERRIDE";
    public static final String ENTITLEMENT_OVERRIDE_REMOVED = "ENTITLEMENT_OVERRIDE_REMOVED";
    public static final String OWNER_INVITED = "OWNER_INVITED";
    public static final String PLAN_CULQI_LINKED = "PLAN_CULQI_LINKED";
    public static final String MFA_ENROLLED = "MFA_ENROLLED";
    public static final String RECOVERY_CODES_REGENERATED = "RECOVERY_CODES_REGENERATED";

    private final AuditRepository audit;

    public AuditService(AuditRepository audit) {
        this.audit = audit;
    }

    public void record(Principal actor, String action, UUID tenant, String detail) {
        audit.record(actor == null ? "sistema" : actor.getName(), action, tenant, detail);
    }
}
