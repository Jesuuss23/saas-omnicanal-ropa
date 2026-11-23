/** Tipos de las respuestas de /api/superadmin (consola de plataforma). */
export type Kpis = {
  stores: number; activePaid: number; trials: number; trialsEndingSoon: number; pastDue: number; suspended: number;
  newStores30d: number; pendingSignups: number; mrrCents: number; products: number; variants: number;
  webOrders30d: number; posSales30d: number;
};

export type TenantRow = {
  id: string; name: string; ownerEmail: string | null; ownerActive: boolean; planCode: string | null; planName: string | null;
  status: string | null; trialEndsAt: string | null; createdAt: string | null; suspended: boolean; products: number;
};

export type AuditEntry = {id: string; actor: string; action: string; tenant: string | null; tenantName: string | null; detail: string | null; createdAt: string};

export type Dashboard = {
  email: string; kpis: Kpis; newStores: {week: string; stores: number}[]; statuses: Record<string, number>;
  recent: TenantRow[]; activity: AuditEntry[];
};

export type TenantDetail = {
  store: {
    id: string; name: string; domain: string | null; createdAt: string | null; suspendedAt: string | null; suspendedReason: string | null;
    ownerEmail: string | null; ownerName: string | null; ownerPhone: string | null; ownerActive: boolean; ownerMfa: boolean;
    planCode: string | null; planName: string | null; status: string | null; trialEndsAt: string | null; periodEnd: string | null;
    graceEndsAt: string | null; culqiSubscriptionId: string | null; whatsappProvider: string; mercadoPagoLinked: boolean;
    products: number; variants: number; webOrders30d: number; webRevenue30d: number; posSales30d: number; posRevenue30d: number;
    contacts: number;
  };
  events: {fromStatus: string | null; toStatus: string; planCode: string | null; source: string; detail: string | null; createdAt: string}[];
  overrides: {key: string; enabled: boolean; limit: number | null; expiresAt: string | null; reason: string}[];
  audit: AuditEntry[];
};

export type PlanRow = {code: string; name: string; priceCents: number; currency: string; culqiPlanId: string | null; publicPlan: boolean};

export type PaymentEvent = {provider: string; eventId: string; tenant: string | null; tenantName: string | null; type: string | null;
  status: string; error: string | null; receivedAt: string};

export const auditLabels: Record<string, string> = {
  TENANT_CREATED: "Tienda creada",
  TENANT_SUSPENDED: "Tienda suspendida",
  TENANT_REACTIVATED: "Tienda reactivada",
  SUBSCRIPTION_CHANGED: "Suscripción ajustada",
  ENTITLEMENT_OVERRIDE: "Capacidad ajustada",
  ENTITLEMENT_OVERRIDE_REMOVED: "Ajuste de capacidad retirado",
  OWNER_INVITED: "Invitación al propietario",
  PLAN_CULQI_LINKED: "Plan vinculado a Culqi",
  MFA_ENROLLED: "2FA configurada",
  RECOVERY_CODES_REGENERATED: "Códigos de recuperación nuevos",
};

export {formatDate, limaDate, shortDay} from "./utils";
