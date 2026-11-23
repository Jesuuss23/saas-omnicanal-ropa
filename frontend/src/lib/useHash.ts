"use client";
import {useSyncExternalStore} from "react";

function subscribe(onChange: () => void) {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
}

/**
 * Fragmento de la URL sin "#". Los enlaces de los correos llevan el token ahí porque el
 * fragmento nunca viaja al servidor ni queda en logs. En el servidor devuelve null.
 */
export function useHash(): string | null {
  return useSyncExternalStore(subscribe, () => window.location.hash.slice(1), () => null);
}
