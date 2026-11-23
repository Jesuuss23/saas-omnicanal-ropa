/** Textos y tonos de los estados de suscripción SaaS (ver SubscriptionStatus en el backend). */
export const subscriptionStatus: Record<string, {label: string; variant: "success" | "warning" | "destructive" | "neutral" | "default"}> = {
  TRIAL: {label: "En prueba", variant: "default"},
  TRIAL_EXPIRED: {label: "Prueba vencida", variant: "warning"},
  PENDING_PAYMENT: {label: "Pago pendiente", variant: "warning"},
  ACTIVE: {label: "Activa", variant: "success"},
  GRACE: {label: "En gracia", variant: "warning"},
  SUSPENDED: {label: "Suspendida por pago", variant: "destructive"},
  CANCELLED: {label: "Cancelada", variant: "neutral"},
  NONE: {label: "Sin suscripción", variant: "neutral"},
};

export function statusInfo(status: string | null | undefined) {
  return subscriptionStatus[status ?? "NONE"] ?? {label: status ?? "—", variant: "neutral" as const};
}
