"use client";

import { useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { csrfToken } from "./auth";

export default function NewStore() {
  const router = useRouter();
  const registration = useRef("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setMessage("");
    try {
      registration.current ||= crypto.randomUUID();
      const response = await fetch("/api/superadmin/tenants", {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": await csrfToken()},
        body: JSON.stringify({id: registration.current, name: String(data.get("name")).trim(),
          ownerEmail: String(data.get("ownerEmail")).trim()}),
      });
      if (response.status === 401) {
        router.replace("/superadmin/login");
        return;
      }
      if (!response.ok) {
        setMessage(response.status === 400 ? "Revisa el nombre y el correo." :
          response.status === 409 ? "Este registro ya se procesó con otros datos. Actualiza el panel para revisarlo." :
          "No se pudo confirmar el registro. Vuelve a intentarlo.");
        return;
      }
      const store = await response.json();
      setMessage(`Tienda ${store.name} registrada. Propietario: ${store.ownerEmail}.`);
      registration.current = "";
      form.reset();
      router.refresh();
    } catch {
      setMessage("No se pudo confirmar el registro. Puedes reintentar con los mismos datos.");
    } finally {
      setBusy(false);
    }
  }

  const input = "mt-2 w-full rounded-lg border border-stone-300 px-3 py-2 focus:outline-teal-700";
  return <section className="mb-6 rounded-xl border border-stone-200 bg-white p-6">
    <h2 className="text-xl font-semibold">Registrar tienda</h2>
    <form onSubmit={register} className="mt-4 grid items-end gap-4 md:grid-cols-[1fr_1fr_auto]">
      <label>Nombre de la tienda<input name="name" className={input} maxLength={100} required disabled={busy} /></label>
      <label>Correo del propietario<input name="ownerEmail" type="email" className={input} maxLength={254} required disabled={busy} /></label>
      <button disabled={busy} className="rounded-lg bg-teal-800 px-5 py-2.5 font-semibold text-white disabled:opacity-60">
        {busy ? "Registrando…" : "Registrar tienda"}
      </button>
    </form>
    <p aria-live="polite" className="mt-3 text-sm text-stone-700">{message}</p>
  </section>;
}
