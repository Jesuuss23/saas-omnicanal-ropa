"use client";
import {useEffect} from "react";
import {Toaster as Sonner, toast} from "sonner";
import {API_EVENTS} from "@/lib/api";

/** Avisos flotantes. También muestra el aviso de plan cuando el backend responde 402. */
export function Toaster() {
  useEffect(() => {
    const quota = () => toast.warning("Tu plan no incluye esta función o alcanzaste su límite.", {
      id: "quota", description: "Revisa tu suscripción para ampliarlo.",
    });
    const suspended = () => toast.error("La tienda está suspendida.", {
      id: "suspended", description: "Escríbenos para revisar el motivo y reactivarla.",
    });
    window.addEventListener(API_EVENTS.quota, quota);
    window.addEventListener(API_EVENTS.suspended, suspended);
    return () => {
      window.removeEventListener(API_EVENTS.quota, quota);
      window.removeEventListener(API_EVENTS.suspended, suspended);
    };
  }, []);
  return <Sonner position="top-right" richColors closeButton toastOptions={{className: "font-sans"}}/>;
}

export {toast};
