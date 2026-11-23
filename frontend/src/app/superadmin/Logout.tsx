"use client";
import {useState} from "react";
import {LogOut} from "lucide-react";
import {Button} from "@/components/ui/button";
import {toast} from "@/components/ui/toaster";
import {submitAuth} from "./auth";

export default function Logout() {
  const [busy,setBusy] = useState(false);
  async function leave() {
    setBusy(true);
    try {
      if (!(await submitAuth("logout")).ok) throw new Error();
      window.location.replace("/superadmin/login");
    } catch {
      setBusy(false);
      toast.error("No se pudo cerrar la sesión. Inténtalo otra vez.");
    }
  }
  return <Button variant="outline" className="w-full justify-start" loading={busy} onClick={leave}>
    {!busy && <LogOut aria-hidden/>}{busy ? "Cerrando…" : "Cerrar sesión"}
  </Button>;
}
