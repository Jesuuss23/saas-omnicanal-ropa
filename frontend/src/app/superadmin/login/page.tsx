"use client";

import { useState, type FormEvent } from "react";
import { submitAuth } from "../auth";

export default function Login() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function enter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    const fields = new URLSearchParams();
    new FormData(event.currentTarget).forEach((v,k) => fields.set(k, String(v)));
    try {
      const res = await submitAuth("login", fields);
      if (res.ok) { window.location.replace("/superadmin"); return; }
      setError(res.status === 401 ? "Acceso denegado. Revisa tus datos y el código vigente. Si hiciste varios intentos, espera cinco minutos." : "No se pudo iniciar sesión. Vuelve a intentarlo.");
    } catch { setError("No se pudo conectar con el servidor."); }
    setBusy(false);
  }
  const input = "mt-2 w-full rounded-lg border border-stone-300 bg-white px-4 py-3 text-stone-950 focus:outline-2 focus:outline-teal-700";
  return <main className="grid min-h-screen bg-stone-50 text-stone-900 lg:grid-cols-[0.8fr_1fr]">
    <aside className="flex flex-col justify-between bg-teal-950 px-8 py-8 text-white lg:p-16"><span className="text-xl font-semibold tracking-tight">Omnicanal</span><p className="mt-12 max-w-xs text-3xl font-light lg:text-5xl">Administración de plataforma</p></aside>
    <section className="m-auto w-full max-w-md px-6 py-12"><h1 className="text-3xl font-semibold tracking-tight">Iniciar sesión</h1>
    <form onSubmit={enter} className="mt-8 space-y-5">
    <label className="block">Correo electrónico<input className={input} name="username" type="email" autoComplete="username" required /></label>
    <label className="block">Contraseña<input className={input} name="password" type="password" autoComplete="current-password" required /></label>
    <label className="block">Código de Authenticator<input className={input} name="code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="6 dígitos" required /></label>
    {error && <p role="alert" className="text-sm text-red-800">{error}</p>}
    <button disabled={busy} className="w-full rounded-lg bg-teal-800 p-3 font-semibold text-white disabled:opacity-60">{busy ? "Ingresando…" : "Ingresar al panel"}</button>
    </form></section></main>;
}
