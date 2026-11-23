"use client";

import {createContext, useContext} from "react";
import type {Send} from "@/lib/api";

export type Profile = {id: string; name: string; email: string; suspendedReason: string | null};

type Store = {store: string; profile: Profile; send: Send; refreshProfile: () => void};

const StoreContext = createContext<Store | null>(null);

export const StoreProvider = StoreContext.Provider;

/** Tienda en sesión para las páginas del panel (el layout solo las muestra con sesión válida). */
export function useStore(): Store {
  const value = useContext(StoreContext);
  if (!value) throw new Error("useStore fuera del panel de tienda");
  return value;
}
