import {clsx, type ClassValue} from "clsx";
import {twMerge} from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const money = new Intl.NumberFormat("es-PE", {style: "currency", currency: "PEN"});

export const limaDateTime = new Intl.DateTimeFormat("es-PE", {
  timeZone: "America/Lima", dateStyle: "short", timeStyle: "short",
});

export const limaDate = new Intl.DateTimeFormat("es-PE", {timeZone: "America/Lima", dateStyle: "medium"});
export const shortDay = new Intl.DateTimeFormat("es-PE", {timeZone: "UTC", day: "numeric", month: "short"});

export function formatDate(value: string | null | undefined, format: Intl.DateTimeFormat = limaDate) {
  return value ? format.format(new Date(value)) : "—";
}
