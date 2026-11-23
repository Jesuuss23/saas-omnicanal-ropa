"use client";

import Link from "next/link";
import {useSearchParams} from "next/navigation";
import {Suspense} from "react";
import {AlertTriangle, ArrowRight, Check, Circle, Package, PartyPopper, Receipt, ShoppingBag, Wallet} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {StatCard} from "@/components/console/StatCard";
import {BarChart} from "@/components/console/BarChart";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Skeleton} from "@/components/ui/skeleton";
import {ownerApi} from "@/lib/api";
import {statusInfo} from "@/lib/subscription";
import {formatDate, limaDateTime, money, shortDay} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {useStore} from "./_panel/StoreContext";

type Overview = {
  metrics: {webRevenue30d: number; webOrders30d: number; pendingOrders: number; posRevenue30d: number; posSales30d: number;
    products: number; variants: number; lowStock: number; outOfStock: number};
  sales: {day: string; web: number; pos: number}[];
  recentOrders: {id: string; customer: string; total: number; status: string; deliveryMethod: string | null; createdAt: string}[];
  setup: {products: boolean; photos: boolean; appearance: boolean; fulfillment: boolean; whatsapp: boolean; payments: boolean; fiscal: boolean; twoFactor: boolean};
  plan: {status: string; planCode: string | null; trialEndsAt: string | null};
};

const orderStatus: Record<string, {label: string; variant: "success" | "warning" | "neutral" | "destructive"}> = {
  PENDING_PAYMENT: {label: "Por cobrar", variant: "warning"},
  PAID: {label: "Pagado", variant: "success"},
  CANCELLED: {label: "Cancelado", variant: "neutral"},
  EXPIRED: {label: "Vencido", variant: "neutral"},
};

export default function Home() {
  return <Suspense><HomeContent/></Suspense>;
}

function HomeContent() {
  const {store, profile} = useStore();
  const welcome = useSearchParams().get("bienvenida") === "1";
  const {data, error, reload} = useApiData<Overview>(ownerApi.url("stores/" + store + "/overview"));
  const base = "/propietario/" + store;

  if (error) return <Alert variant="destructive" title="No se pudo cargar el inicio">{error.message} <button className="underline" onClick={reload}>Reintentar</button></Alert>;
  if (!data) return <><Skeleton className="h-12 w-72"/><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-32"/>)}</div><Skeleton className="h-72"/></>;

  const m = data.metrics;
  const steps = [
    {done: data.setup.products, label: "Registra tus prendas", hint: "Con tallas, colores, precio y stock.", href: base + "/catalogo"},
    {done: data.setup.photos, label: "Agrega fotos", hint: "Las prendas con foto se venden más.", href: base + "/catalogo"},
    {done: data.setup.appearance, label: "Personaliza tu tienda", hint: "Logo, color y frase de tu marca.", href: base + "/apariencia"},
    {done: data.setup.fulfillment, label: "Configura recojo o delivery", hint: "Dirección, horario y tarifa por distancia.", href: base + "/pedidos"},
    {done: data.setup.whatsapp, label: "Conecta WhatsApp", hint: "Responde a tus clientes desde el panel.", href: base + "/whatsapp"},
    {done: data.setup.payments, label: "Activa los cobros en línea", hint: "Vincula tu cuenta de Mercado Pago.", href: base + "/cobros"},
    {done: data.setup.twoFactor, label: "Protege tu cuenta", hint: "Verificación en dos pasos con tu celular.", href: base + "/cuenta"},
  ];
  const done = steps.filter(s => s.done).length;
  const plan = statusInfo(data.plan.status);
  const chart = data.sales.map(d => ({label: shortDay.format(new Date(d.day + "T00:00:00Z")), title: shortDay.format(new Date(d.day + "T00:00:00Z")) + " · web " + money.format(d.web) + " · POS " + money.format(d.pos), value: Math.round(d.web + d.pos)}));

  return <>
    <PageHeader title={"Hola, " + profile.name} description="Así va tu tienda en los últimos 30 días."
      actions={<>
        <Badge variant={plan.variant}>{plan.label}{data.plan.status === "TRIAL" && data.plan.trialEndsAt ? " hasta " + formatDate(data.plan.trialEndsAt) : ""}</Badge>
        <Button asChild variant="outline"><Link href={base + "/pos"}><Receipt/>Vender en tienda</Link></Button>
      </>}/>

    {welcome && <Alert variant="success" title="¡Tu tienda está lista!">Sigue los pasos de abajo para empezar a vender. Tienes 14 días de prueba con todo incluido.</Alert>}

    {done < steps.length && <Card>
      <CardHeader className="flex-row items-center justify-between gap-4">
        <div className="grid gap-1"><CardTitle>Configura tu tienda</CardTitle><CardDescription>{done} de {steps.length} pasos completos</CardDescription></div>
        <PartyPopper className="size-5 text-primary" aria-hidden/>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div className="h-2 rounded-full bg-muted" role="progressbar" aria-valuemin={0} aria-valuemax={steps.length} aria-valuenow={done} aria-label="Avance de configuración">
          <div className="h-2 rounded-full bg-primary transition-all" style={{width: (done / steps.length * 100) + "%"}}/></div>
        <ol className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">{steps.map(s => <li key={s.label}>
          <Link href={s.href} className="flex items-start gap-3 rounded-lg border p-3 transition-colors hover:border-primary hover:bg-accent/40">
            {s.done ? <span className="grid size-5 shrink-0 place-items-center rounded-full bg-success text-success-foreground"><Check className="size-3"/></span>
              : <Circle className="size-5 shrink-0 text-muted-foreground"/>}
            <span className="grid gap-0.5"><span className={s.done ? "text-sm font-medium text-muted-foreground line-through" : "text-sm font-medium"}>{s.label}</span>
              <span className="text-xs text-muted-foreground">{s.hint}</span></span>
          </Link></li>)}</ol>
      </CardContent>
    </Card>}

    <section aria-label="Indicadores" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Ventas web cobradas" icon={ShoppingBag} value={money.format(m.webRevenue30d)} hint={m.webOrders30d + " pedidos en 30 días"}/>
      <StatCard label="Ventas en tienda" icon={Wallet} value={money.format(m.posRevenue30d)} hint={m.posSales30d + " ventas en 30 días"}/>
      <StatCard label="Pedidos por cobrar" icon={AlertTriangle} value={m.pendingOrders} hint="Esperando pago o confirmación" tone={m.pendingOrders ? "warning" : "default"}/>
      <StatCard label="Catálogo" icon={Package} value={m.products} tone={m.outOfStock ? "warning" : "default"}
        hint={m.outOfStock || m.lowStock ? m.outOfStock + " agotadas · " + m.lowStock + " por agotarse" : m.variants + " variantes con stock"}/>
    </section>

    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <Card><CardHeader><CardTitle>Ventas por día (S/)</CardTitle><CardDescription>Web cobrado + punto de venta, últimos 14 días</CardDescription></CardHeader>
        <CardContent><BarChart label="Ventas por día en soles" data={chart}/></CardContent></Card>
      <Card><CardHeader className="flex-row items-center justify-between"><CardTitle>Últimos pedidos web</CardTitle>
        <Button asChild variant="link" size="sm"><Link href={base + "/pedidos"}>Ver todos<ArrowRight/></Link></Button></CardHeader>
        <CardContent>{data.recentOrders.length ? <ul className="divide-y">{data.recentOrders.map(o => <li key={o.id} className="flex items-center justify-between gap-3 py-3">
          <span className="min-w-0"><span className="block truncate font-medium">{o.customer}</span>
            <span className="block text-xs text-muted-foreground">{limaDateTime.format(new Date(o.createdAt))}{o.deliveryMethod ? " · " + (o.deliveryMethod === "PICKUP" ? "Recojo" : "Delivery") : ""}</span></span>
          <span className="grid justify-items-end gap-1"><span className="font-medium tabular-nums">{money.format(o.total)}</span>
            <Badge variant={(orderStatus[o.status] ?? orderStatus.EXPIRED).variant}>{(orderStatus[o.status] ?? {label: o.status}).label}</Badge></span>
        </li>)}</ul> : <p className="text-sm text-muted-foreground">Cuando alguien compre en tu tienda en línea, lo verás aquí.</p>}</CardContent></Card>
    </div>
  </>;
}
