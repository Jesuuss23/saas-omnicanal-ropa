/**
 * Reglas de precio y disponibilidad del catálogo. Son las mismas de PricingRules.java en el backend:
 * - Disponible = stock físico − reservado (nunca menor que 0).
 * - Margen % = (precio − costo) / precio × 100, con un decimal. Sin costo no hay margen.
 * - Descuento % = (precio antes − precio) / precio antes × 100, redondeado hacia abajo; solo si el precio antes es mayor.
 * - Estado: INACTIVE (oculto), SOLD_OUT (0 disponibles), LOW_STOCK (LOW_STOCK o menos) o AVAILABLE.
 */
export const LOW_STOCK = 3;

export type StockStatus = "INACTIVE" | "SOLD_OUT" | "LOW_STOCK" | "AVAILABLE";

export function available(physical: number, reserved: number) {
  return Math.max(0, physical - reserved);
}

export function marginPercent(price: number, cost: number | null | undefined): number | null {
  if (cost == null || !(price > 0)) return null;
  return Math.round(((price - cost) / price) * 1000) / 10;
}

export function discountPercent(price: number, compareAt: number | null | undefined): number | null {
  if (compareAt == null || !(compareAt > price)) return null;
  return Math.floor(((compareAt - price) / compareAt) * 100);
}

export function stockStatus(active: boolean, units: number): StockStatus {
  if (!active) return "INACTIVE";
  if (units <= 0) return "SOLD_OUT";
  return units <= LOW_STOCK ? "LOW_STOCK" : "AVAILABLE";
}

export const STATUS_LABEL: Record<StockStatus, string> = {
  INACTIVE: "Oculto", SOLD_OUT: "Agotado", LOW_STOCK: "Últimas unidades", AVAILABLE: "Disponible",
};
