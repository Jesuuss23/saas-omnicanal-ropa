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
    window.addEventListener(API_EVENTS.quota, quota);
    return () => window.removeEventListener(API_EVENTS.quota, quota);
  }, []);
  return <Sonner position="top-right" richColors closeButton toastOptions={{className: "font-sans"}}/>;
}

export {toast};
