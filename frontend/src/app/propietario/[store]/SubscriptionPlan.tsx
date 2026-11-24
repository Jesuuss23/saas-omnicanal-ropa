"use client";
import {apiFetch,errorMessage,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState,type FormEvent} from "react";
import {Check, CreditCard} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {cn} from "@/lib/utils";

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
    const [data,setData] = useState<Overview|null>(null);
  const [plan,setPlan] = useState<Plan|null>(null);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);
  const [version,setVersion] = useState(0);

  useEffect(()=>{
    const controller = new AbortController();
    apiFetch(ownerApi.url(path),{signal:controller.signal}).then(async r=>{
      if (!r.ok) throw new Error();
      const body:Overview = await r.json();
      if (!controller.signal.aborted) setData(body);
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo cargar tu plan.");});
    return ()=>controller.abort();
  },[path,version]);

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
      if (!res.ok) throw new Error(await errorMessage(res, "No se pudo registrar la suscripción."));
      setData(await res.json());
      setPlan(null);
      setNotice("Recibimos tu pago. El plan se activa apenas Culqi lo confirme.");
    } catch (err) {setNotice(err instanceof Error ? err.message : "No se pudo completar el pago.");}
    finally {setBusy(false);}
  }

  const current = data?.plans.find(p=>p.code===data.planCode);
  const pendingPlan = data?.plans.find(p=>p.code===data.pendingPlanCode);
  const needsPlan = data && ["TRIAL_EXPIRED","CANCELLED","SUSPENDED","NONE"].includes(data.status);
  const tone = !data ? "neutral" : data.status==="ACTIVE" || data.status==="TRIAL" ? "success"
    : data.status==="PENDING_PAYMENT" || data.status==="GRACE" ? "warning" : "destructive";

  return <section className="grid gap-5 rounded-xl border bg-card p-5 shadow-card" aria-labelledby="plan-title">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 id="plan-title" className="font-semibold">Plan y suscripción</h2>
      {data && <Badge variant={tone}>{statusLabels[data.status] ?? data.status}</Badge>}
    </div>
    {notice && <Alert role="status">{notice}</Alert>}
    {!data ? <Skeleton className="h-40"/> : <>
      <p className="text-sm">
        {data.status==="TRIAL" ? <>Estás en la prueba gratuita{data.trialEndsAt && <>, vence el {date(data.trialEndsAt)}</>}.</>
          : data.status==="ACTIVE" ? <>Plan <strong>{current?.name ?? data.planCode}</strong>{data.currentPeriodEnd && <>, se renueva el {date(data.currentPeriodEnd)}</>}.</>
          : data.status==="GRACE" ? <>Plan <strong>{current?.name ?? data.planCode}</strong>. Regulariza tu pago{data.graceEndsAt && <> antes del {date(data.graceEndsAt)}</>}.</>
          : data.status==="PENDING_PAYMENT" ? <>Estamos esperando que Culqi confirme el pago del plan <strong>{pendingPlan?.name ?? data.pendingPlanCode}</strong>.</>
          : <>Tu tienda no tiene un plan vigente.</>}
      </p>
      {data.status==="PENDING_PAYMENT" && <Alert variant="warning">Si el pago no se confirma en unos 15 minutos, vuelve a elegir el plan para intentarlo otra vez. No se te cobrará dos veces: el intento anterior se cancela.</Alert>}
      {needsPlan && <Alert variant="warning">Elige un plan para seguir usando todas las funciones de tu tienda.</Alert>}
      <ul className="grid gap-1 text-sm md:grid-cols-2">{data.entitlements.map(e=><li key={e.key} className="flex justify-between gap-3 border-b py-1">
        <span className="text-muted-foreground">{keyLabels[e.key] ?? e.key}</span><span className="font-medium">{describe(e)}</span></li>)}</ul>

      <div className="grid gap-3 md:grid-cols-3">{data.plans.map(p=>{
        const isCurrent = data.planCode===p.code && data.status==="ACTIVE";
        return <article key={p.code} className={cn("grid content-start gap-2 rounded-lg border p-4", isCurrent && "border-primary bg-primary/5", plan?.code===p.code && "ring-2 ring-primary")}>
          <h3 className="flex items-center justify-between font-semibold">{p.name}{isCurrent && <Badge variant="success">Plan actual</Badge>}</h3>
          <p className="text-2xl font-semibold">{soles.format(p.monthlyPriceCents/100)} <span className="text-sm font-normal text-muted-foreground">/ mes</span></p>
          <ul className="grid gap-1 text-sm">{p.entitlements.filter(e=>e.enabled).map(e=><li key={e.key} className="flex gap-2">
            <Check className="mt-0.5 size-4 shrink-0 text-success" aria-hidden/>{keyLabels[e.key] ?? e.key}{e.limit!==null && `: ${e.limit}`}</li>)}</ul>
          <Button type="button" variant={isCurrent ? "outline" : "default"} className="mt-2" disabled={busy || !data.checkoutAvailable || !p.purchasable || isCurrent}
            onClick={()=>{setPlan(p); setNotice("");}}>{isCurrent ? "Plan actual" : "Elegir plan"}</Button>
        </article>;})}</div>
      {!data.checkoutAvailable && <p className="text-sm text-muted-foreground">El pago en línea aún no está habilitado. Escríbenos para activar tu plan.</p>}

      {plan && <form onSubmit={pay} className="grid gap-4 rounded-lg border p-4 md:grid-cols-2">
        <p className="font-medium md:col-span-2">Datos de facturación para el plan {plan.name}</p>
        {[["firstName","Nombres"],["lastName","Apellidos"],["email","Correo"],["phone","Celular"],["address","Dirección"],["city","Ciudad"]].map(([n,l])=>
          <Field key={n} label={l} htmlFor={"bill-"+n}><Input id={"bill-"+n} name={n} required type={n==="email"?"email":n==="phone"?"tel":"text"}/></Field>)}
        <div className="flex flex-wrap gap-3 md:col-span-2">
          <Button loading={busy}><CreditCard/>Pagar con tarjeta (Culqi)</Button>
          <Button type="button" variant="ghost" disabled={busy} onClick={()=>setPlan(null)}>Cancelar</Button>
        </div>
      </form>}
    </>}
  </section>;
}
