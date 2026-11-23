import {clsx, type ClassValue} from "clsx";
import {twMerge} from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const money = new Intl.NumberFormat("es-PE", {style: "currency", currency: "PEN"});

export const limaDateTime = new Intl.DateTimeFormat("es-PE", {
  timeZone: "America/Lima", dateStyle: "short", timeStyle: "short",
});
