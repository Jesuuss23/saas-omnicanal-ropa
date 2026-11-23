"use client";

import {createContext, useContext} from "react";
import type {Send} from "@/lib/api";

export type StoreRole = "OWNER" | "MANAGER" | "SELLER";
export type Profile = {id: string; name: string; email: string; suspendedReason: string | null; role: StoreRole};

/** Lo que cada rol puede abrir en el panel; el backend aplica las mismas reglas en OwnerSecurity. */
export const roleAccess: Record<StoreRole, {label: string; configure: boolean; ownerOnly: boolean}> = {
  OWNER: {label: "Propietario", configure: true, ownerOnly: true},
  MANAGER: {label: "Encargado", configure: true, ownerOnly: false},
  SELLER: {label: "Vendedor", configure: false, ownerOnly: false},
};

type Store = {store: string; profile: Profile; send: Send; refreshProfile: () => void};

const StoreContext = createContext<Store | null>(null);

export const StoreProvider = StoreContext.Provider;

/** Tienda en sesión para las páginas del panel (el layout solo las muestra con sesión válida). */
export function useStore(): Store {
  const value = useContext(StoreContext);
  if (!value) throw new Error("useStore fuera del panel de tienda");
  return value;
}
