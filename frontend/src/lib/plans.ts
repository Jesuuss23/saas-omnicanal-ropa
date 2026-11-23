/** Planes públicos del SaaS tal como los devuelve GET /api/public/plans. */
export type Entitlement = {key: string; enabled: boolean; limit: number | null};
export type Plan = {code: string; name: string; monthlyPriceCents: number; currency: string; purchasable: boolean; entitlements: Entitlement[]};

/** Etiquetas de cada capacidad, en el orden en que se muestran. */
export const entitlementLabels: {key: string; label: string; unit?: string}[] = [
  {key: "catalog.products", label: "Productos en catálogo"},
  {key: "team.users", label: "Usuarios del equipo"},
  {key: "whatsapp.evolution", label: "WhatsApp conectado por QR"},
  {key: "whatsapp.cloud_api", label: "WhatsApp API oficial de Meta"},
  {key: "ai.responses.monthly", label: "Respuestas con IA", unit: "al mes"},
  {key: "payments.mercadopago", label: "Cobros con Mercado Pago"},
  {key: "fiscal.documents.monthly", label: "Boletas y facturas", unit: "al mes"},
  {key: "fiscal.production", label: "Emisión SUNAT en producción"},
  {key: "domain.custom", label: "Dominio propio"},
  {key: "campaigns.email", label: "Campañas por correo"},
  {key: "campaigns.whatsapp", label: "Campañas por WhatsApp", unit: "mensajes al mes"},
];

export const planTaglines: Record<string, string> = {
  BASICO: "Para empezar a vender en línea con orden.",
  PRO: "Para tiendas que venden todos los días por WhatsApp.",
  PREMIUM: "Para marcas con varios vendedores y alto volumen.",
};

export const featuredPlan = "PRO";

const number = new Intl.NumberFormat("es-PE");

/** Texto corto de una capacidad: "Ilimitado", "500", "Incluido" o null si no está incluida. */
export function entitlementValue(plan: Plan, key: string): string | null {
  const e = plan.entitlements.find(x => x.key === key);
  if (!e || !e.enabled) return null;
  if (e.limit === null) return key.endsWith(".monthly") || key.startsWith("catalog") || key.startsWith("team") || key === "campaigns.whatsapp" ? "Ilimitado" : "Incluido";
  return number.format(e.limit);
}

export function planPrice(plan: Plan) {
  return new Intl.NumberFormat("es-PE", {style: "currency", currency: plan.currency, maximumFractionDigits: 0}).format(plan.monthlyPriceCents / 100);
}
