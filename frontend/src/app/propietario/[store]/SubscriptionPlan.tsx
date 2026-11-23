"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState,type FormEvent} from "react";

type Entitlement = {key:string;enabled:boolean;limit:number|null};
type Plan = {code:string;name:string;monthlyPriceCents:number;currency:string;purchasable:boolean;entitlements:Entitlement[]};
type Overview = {status:string;planCode:string|null;pendingPlanCode:string|null;trialEndsAt:string|null;
  currentPeriodEnd:string|null;graceEndsAt:string|null;entitlements:Entitlement[];plans:Plan[];
  checkoutAvailable:boolean;culqiPublicKey:string|null};
type CulqiGlobal = {publicKey:string;settings:(s:object)=>void;options?:(o:object)=>void;open:()=>void;close:()=>void;
  token?:{id:string};error?:{user_message?:string}};
declare global { interface Window { Culqi?:CulqiGlobal; culqi?:()=>void } }

const statusLabels:Record<string,string> = {
  TRIAL:"Prueba gratuita", TRIAL_EXPIRED:"Prueba vencida", PENDING_PAYMENT:"Pago en confirmación",
  ACTIVE:"Activa", GRACE:"En periodo de gracia", SUSPENDED:"Suspendida por falta de pago", CANCELLED:"Cancelada", NONE:"Sin suscripción",
};
const keyLabels:Record<string,string> = {
  "catalog.products":"Productos","team.users":"Usuarios","ai.responses.monthly":"Respuestas IA al mes",
  "fiscal.documents.monthly":"Comprobantes al mes","fiscal.production":"Facturación en producción",
  "whatsapp.evolution":"WhatsApp por QR","whatsapp.cloud_api":"WhatsApp Cloud API","domain.custom":"Dominio propio",
  "campaigns.email":"Campañas por email","campaigns.whatsapp":"Campañas por WhatsApp","payments.mercadopago":"Cobros con Mercado Pago",
};
const soles = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const date = (v:string|null)=>v ? new Date(v).toLocaleDateString("es-PE",{timeZone:"America/Lima",dateStyle:"long"}) : "";
const describe = (e:Entitlement)=>!e.enabled ? "No incluido" : e.limit===null ? "Ilimitado / incluido" : String(e.limit);

function loadCulqi():Promise<CulqiGlobal> {
  if (window.Culqi) return Promise.resolve(window.Culqi);
  return new Promise((resolve,reject)=>{
    const script = document.createElement("script");
    script.src = "https://checkout.culqi.com/js/v4";
    script.onload = ()=>window.Culqi ? resolve(window.Culqi) : reject(new Error());
    script.onerror = ()=>reject(new Error());
    document.head.appendChild(script);
  });
}

/** Plan del SaaS: el pago se tokeniza con Culqi Checkout y la activación llega por webhook al backend. */
export default function SubscriptionPlan({store,send}:{store:string;send:Send}) {
  const path = "stores/"+store+"/subscription";
  const [open,setOpen] = useState(false);
  const [data,setData] = useState<Overview|null>(null);
  const [plan,setPlan] = useState<Plan|null>(null);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);
  const [version,setVersion] = useState(0);

  useEffect(()=>{
    if (!open) return;
    const controller = new AbortController();
    apiFetch(ownerApi.url(path),{signal:controller.signal}).then(async r=>{
      if (!r.ok) throw new Error();
      const body:Overview = await r.json();
      if (!controller.signal.aborted) setData(body);
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo cargar tu plan.");});
    return ()=>controller.abort();
  },[open,path,version]);

  useEffect(()=>{
    if (data?.status!=="PENDING_PAYMENT") return;
    const timer = window.setTimeout(()=>setVersion(v=>v+1),8000);
    return ()=>window.clearTimeout(timer);
  },[data]);

  async function pay(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!plan || !data?.culqiPublicKey || busy) return;
    const form = Object.fromEntries(new FormData(e.currentTarget)) as Record<string,string>;
    setBusy(true);setNotice("");
    try {
      const culqi = await loadCulqi();
      culqi.publicKey = data.culqiPublicKey;
      culqi.settings({title:"Suscripción "+plan.name,currency:"PEN",amount:plan.monthlyPriceCents});
      const token = await new Promise<string>((resolve,reject)=>{
        window.culqi = ()=>{
          if (culqi.token?.id) {culqi.close(); resolve(culqi.token.id);}
          else reject(new Error(culqi.error?.user_message ?? "Culqi no pudo procesar la tarjeta."));
        };
        culqi.open();
      });
      const res = await send(path+"/checkout",JSON.stringify({...form,planCode:plan.code,culqiToken:token}));
      if (!res.ok) {
        let message = "No se pudo registrar la suscripción.";
        try { const body = await res.json(); if (body?.message) message = body.message; } catch {}
        throw new Error(message);
      }
      setData(await res.json());
      setPlan(null);
      setNotice("Recibimos tu pago. El plan se activa apenas Culqi lo confirme.");
    } catch (err) {setNotice(err instanceof Error ? err.message : "No se pudo completar el pago.");}
    finally {setBusy(false);}
  }

  return <details className="mt-6 rounded-xl border border-stone-200 p-5" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">Plan y suscripción</summary>
    {notice && <p role="status" className="my-3">{notice}</p>}
    {!data ? <p className="my-3">Cargando…</p> : <>
      <p className="my-3"><span className="font-medium">{statusLabels[data.status] ?? data.status}</span>
        {data.planCode && ` · plan ${data.planCode}`}
        {data.status==="TRIAL" && data.trialEndsAt && ` · vence el ${date(data.trialEndsAt)}`}
        {data.status==="ACTIVE" && data.currentPeriodEnd && ` · renueva el ${date(data.currentPeriodEnd)}`}
        {data.status==="GRACE" && data.graceEndsAt && ` · regulariza tu pago antes del ${date(data.graceEndsAt)}`}</p>
      <ul className="grid gap-1 text-sm md:grid-cols-2">{data.entitlements.map(e=><li key={e.key}>
        {keyLabels[e.key] ?? e.key}: <span className="font-medium">{describe(e)}</span></li>)}</ul>

      <div className="mt-5 grid gap-3 md:grid-cols-3">{data.plans.map(p=><article key={p.code} className="rounded-lg border p-4">
        <h3 className="font-semibold">{p.name}</h3>
        <p className="text-xl">{soles.format(p.monthlyPriceCents/100)} <span className="text-sm">/ mes</span></p>
        <ul className="my-2 text-sm">{p.entitlements.filter(e=>e.enabled).map(e=><li key={e.key}>{keyLabels[e.key] ?? e.key}{e.limit!==null && `: ${e.limit}`}</li>)}</ul>
        <button type="button" disabled={busy || !data.checkoutAvailable || !p.purchasable || data.planCode===p.code && data.status==="ACTIVE"}
          className="rounded-lg bg-teal-800 px-3 py-2 text-white disabled:opacity-50" onClick={()=>setPlan(p)}>
          {data.planCode===p.code && data.status==="ACTIVE" ? "Plan actual" : "Elegir plan"}</button>
      </article>)}</div>
      {!data.checkoutAvailable && <p className="mt-2 text-sm text-stone-600">El pago en línea aún no está habilitado. Escríbenos para activar tu plan.</p>}

      {plan && <form onSubmit={pay} className="mt-5 grid gap-3 md:grid-cols-2">
        <p className="md:col-span-2 font-medium">Datos de facturación para el plan {plan.name}</p>
        {[["firstName","Nombres"],["lastName","Apellidos"],["email","Correo"],["phone","Celular"],["address","Dirección"],["city","Ciudad"]].map(([n,l])=>
          <label key={n} className="grid gap-1 text-sm">{l}<input name={n} required type={n==="email"?"email":"text"} className="rounded border px-3 py-2"/></label>)}
        <div className="flex gap-3 md:col-span-2">
          <button disabled={busy} className="rounded-lg bg-teal-800 px-4 py-2 text-white">{busy?"Procesando…":"Pagar con tarjeta (Culqi)"}</button>
          <button type="button" className="underline" onClick={()=>setPlan(null)}>Cancelar</button>
        </div>
      </form>}
    </>}
  </details>;
}
