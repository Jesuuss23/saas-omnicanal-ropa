"use client";

import Script from "next/script";
import Link from "next/link";
import {useEffect, useRef, useState} from "react";
import {LogOut, Package} from "lucide-react";
import {apiFetch, getJson} from "@/lib/api";
import {useApiData} from "@/lib/useApiData";

type Order = {id: string; createdAt: string; status: string; total: number; deliveryMethod: string | null; trackingPath: string};
type Profile = {id: string; fullName: string | null; email: string | null; phone: string | null; orders: Order[]};
type GoogleId = {accounts: {id: {initialize: (o: object) => void; renderButton: (el: HTMLElement, o: object) => void; disableAutoSelect: () => void}}};

const statusLabels: Record<string, string> = {PENDING_PAYMENT: "Por pagar", PAID: "Pagado", CANCELLED: "Cancelado", EXPIRED: "Vencido"};
const money = new Intl.NumberFormat("es-PE", {style: "currency", currency: "PEN"});
const date = new Intl.DateTimeFormat("es-PE", {dateStyle: "medium", timeZone: "America/Lima"});

export default function CustomerAccount({store, storeName}: {store: string; storeName: string}) {
  const base = "/api/public/stores/" + store + "/account";
  const config = useApiData<{googleClientId: string | null}>(base + "/config");
  const [profile, setProfile] = useState<Profile | null>(null);
  const [checked, setChecked] = useState(false);
  const [message, setMessage] = useState("");
  const [scriptReady, setScriptReady] = useState(false);
  const button = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let current = true;
    getJson<Profile>(base).then(p => { if (current) setProfile(p); }, () => {}).finally(() => { if (current) setChecked(true); });
    return () => { current = false; };
  }, [base]);

  const clientId = config.data?.googleClientId ?? null;
  useEffect(() => {
    const google = (window as unknown as {google?: GoogleId}).google;
    if (!scriptReady || !clientId || profile || !button.current || !google) return;
    google.accounts.id.initialize({
      client_id: clientId,
      ux_mode: "popup",
      callback: async ({credential}: {credential: string}) => {
        setMessage("");
        try {
          const res = await apiFetch(base + "/google", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({credential})});
          if (!res.ok) throw new Error();
          setProfile(await res.json());
        } catch { setMessage("No pudimos verificar tu cuenta de Google. Vuelve a intentarlo."); }
      },
    });
    google.accounts.id.renderButton(button.current, {theme: "outline", size: "large", text: "continue_with", shape: "pill", locale: "es"});
  }, [scriptReady, clientId, profile, base]);

  async function logout() {
    try {
      await apiFetch(base + "/logout", {method: "POST", headers: {"Content-Type": "application/json"}, body: "{}"});
      (window as unknown as {google?: GoogleId}).google?.accounts.id.disableAutoSelect();
    } finally { setProfile(null); }
  }

  if (!checked || (!profile && !config.data && !config.error)) return <p className="mt-8 text-sm text-muted-foreground">Cargando…</p>;

  if (!profile) return <section className="mt-8 grid max-w-md gap-4 rounded-2xl border border-border bg-card p-6">
    <h2 className="text-lg font-semibold">Ingresa para ver tus pedidos</h2>
    <p className="text-sm text-muted-foreground">Usa tu cuenta de Google. Tus próximas compras en {storeName} quedarán guardadas aquí, con su seguimiento.</p>
    {clientId ? <>
      <Script src="https://accounts.google.com/gsi/client" strategy="afterInteractive" onReady={() => setScriptReady(true)}/>
      <div ref={button} className="min-h-11"/>
    </> : <p className="rounded-lg bg-muted px-3 py-2 text-sm">El ingreso con cuenta todavía no está disponible en esta tienda. Puedes comprar como invitado; el enlace de seguimiento llega al confirmar tu pedido.</p>}
    {message && <p role="alert" className="text-sm text-destructive">{message}</p>}
  </section>;

  return <section className="mt-8 grid gap-6">
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card p-5">
      <div><p className="font-semibold">{profile.fullName ?? "Hola"}</p>
        <p className="text-sm text-muted-foreground">{[profile.email, profile.phone && "+" + profile.phone].filter(Boolean).join(" · ")}</p></div>
      <button type="button" onClick={logout} className="inline-flex h-10 items-center gap-1.5 rounded-full border border-border px-4 text-sm"><LogOut className="size-4"/>Cerrar sesión</button>
    </div>
    <div>
      <h2 className="mb-3 text-lg font-semibold">Mis pedidos</h2>
      {!profile.orders.length ? <div className="rounded-2xl border border-dashed border-border px-6 py-12 text-center text-sm text-muted-foreground">
        <Package className="mx-auto mb-2 size-6"/>Aún no tienes pedidos con esta cuenta.{" "}
        <Link href={"/tienda/" + store} className="font-medium text-[var(--store)] underline">Ver el catálogo</Link></div>
        : <ul className="divide-y divide-border rounded-2xl border border-border bg-card">{profile.orders.map(o => <li key={o.id}>
          <Link href={o.trackingPath} className="flex flex-wrap items-center justify-between gap-2 px-5 py-4 hover:bg-muted/50">
            <span><span className="font-medium">{date.format(new Date(o.createdAt))}</span>
              <span className="block text-xs text-muted-foreground">{o.deliveryMethod === "DELIVERY" ? "Delivery" : "Recojo en tienda"}</span></span>
            <span className="flex items-center gap-3"><span className="rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium">{statusLabels[o.status] ?? o.status}</span>
              <span className="font-semibold tabular-nums">{money.format(o.total)}</span></span>
          </Link></li>)}</ul>}
    </div>
  </section>;
}
