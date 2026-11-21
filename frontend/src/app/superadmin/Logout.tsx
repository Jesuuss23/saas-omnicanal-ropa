"use client";
import {useState} from "react";
import {submitAuth} from "./auth";

export default function Logout() {
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState(false);
  async function leave() {
    setBusy(true); setError(false);
    try {
      if (!(await submitAuth("logout")).ok) throw new Error();
      window.location.replace("/superadmin/login");
    } catch { setBusy(false); setError(true); }
  }
  return <div><button className="rounded-lg border border-teal-600 px-4 py-2 text-sm disabled:opacity-60" disabled={busy} onClick={leave}>{busy ? "Cerrando…" : "Cerrar sesión"}</button>
  {error && <p role="alert">No se pudo cerrar la sesión. Inténtalo otra vez.</p>}</div>;
}
