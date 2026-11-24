"use client";

import Link from "next/link";
import {AlertTriangle, ArrowRight, Ban, CircleDollarSign, Hourglass, Package, ShoppingBag, Store, UserPlus} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {StatCard} from "@/components/console/StatCard";
import {BarChart} from "@/components/console/BarChart";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Skeleton} from "@/components/ui/skeleton";
import {superadminApi} from "@/lib/api";
import {statusInfo} from "@/lib/subscription";
import {auditLabels, formatDate, limaDate, shortDay, type Dashboard} from "@/lib/superadmin";
import {limaDateTime, money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {PlatformAiSwitch} from "./PlatformAiSwitch";

export default function Overview() {
  const {data, error, reload} = useApiData<Dashboard>(superadminApi.url("dashboard"));
  if (error) return <Alert variant="destructive" title="No se pudo cargar el resumen">
    {error.message} <button className="underline" onClick={reload}>Reintentar</button></Alert>;
  if (!data) return <><Skeleton className="h-12 w-72"/><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
    {Array.from({length: 8}, (_, i) => <Skeleton key={i} className="h-32"/>)}</div><Skeleton className="h-72"/></>;
  const k = data.kpis;
  const totalStatuses = Object.values(data.statuses).reduce((a, b) => a + b, 0);
  return <>
    <PageHeader title="Resumen de plataforma" description="Cómo van las tiendas, las pruebas gratis y los cobros del SaaS."
      actions={<><PlatformAiSwitch/><Button asChild><Link href="/superadmin/tiendas?nueva=1"><Store/>Nueva tienda</Link></Button></>}/>

    <section aria-label="Indicadores" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Ingreso mensual recurrente" icon={CircleDollarSign} value={money.format(k.mrrCents / 100)}
        hint={k.activePaid + (k.activePaid === 1 ? " tienda pagando" : " tiendas pagando")}/>
      <StatCard label="Tiendas" icon={Store} value={k.stores} hint={"+" + k.newStores30d + " en los últimos 30 días"}/>
      <StatCard label="En prueba" icon={Hourglass} value={k.trials}
        hint={k.trialsEndingSoon ? k.trialsEndingSoon + " vencen en 7 días" : "Ninguna vence esta semana"}
        tone={k.trialsEndingSoon ? "warning" : "default"}/>
      <StatCard label="Con pago pendiente" icon={AlertTriangle} value={k.pastDue} hint="Gracia, pago pendiente o prueba vencida"
        tone={k.pastDue ? "warning" : "default"}/>
      <StatCard label="Suspendidas" icon={Ban} value={k.suspended} hint="Bloqueadas desde esta consola" tone={k.suspended ? "destructive" : "default"}/>
      <StatCard label="Registros sin confirmar" icon={UserPlus} value={k.pendingSignups} hint="Pruebas gratis esperando el correo"/>
      <StatCard label="Pedidos web (30 días)" icon={ShoppingBag} value={k.webOrders30d} hint={k.posSales30d + " ventas en POS"}/>
      <StatCard label="Catálogo total" icon={Package} value={k.products} hint={k.variants + " variantes"}/>
    </section>

    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <Card>
        <CardHeader><CardTitle>Tiendas nuevas por semana</CardTitle><CardDescription>Últimas 12 semanas</CardDescription></CardHeader>
        <CardContent>
          <BarChart label="Tiendas nuevas por semana" data={data.newStores.map(w => ({
            label: shortDay.format(new Date(w.week + "T00:00:00Z")), title: "Semana del " + shortDay.format(new Date(w.week + "T00:00:00Z")), value: w.stores}))}/>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Estado de suscripciones</CardTitle><CardDescription>{totalStatuses} tiendas con suscripción</CardDescription></CardHeader>
        <CardContent className="grid gap-3">
          {totalStatuses === 0 && <p className="text-sm text-muted-foreground">Todavía no hay suscripciones.</p>}
          {Object.entries(data.statuses).map(([status, count]) => <Link key={status} href={"/superadmin/tiendas?estado=" + status}
            className="grid gap-1.5 rounded-lg p-1 hover:bg-muted">
            <div className="flex items-center justify-between text-sm"><span>{statusInfo(status).label}</span>
              <span className="font-medium tabular-nums">{count}</span></div>
            <div className="h-2 rounded-full bg-muted"><div className="h-2 rounded-full bg-brand-500" style={{width: (count / totalStatuses * 100) + "%"}}/></div>
          </Link>)}
        </CardContent>
      </Card>
    </div>

    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-2">
      <Card>
        <CardHeader className="flex-row items-center justify-between"><CardTitle>Últimas tiendas</CardTitle>
          <Button asChild variant="link" size="sm"><Link href="/superadmin/tiendas">Ver todas<ArrowRight/></Link></Button></CardHeader>
        <CardContent>
          {data.recent.length ? <ul className="divide-y">{data.recent.map(t => <li key={t.id}>
            <Link href={"/superadmin/tiendas/" + t.id} className="flex items-center justify-between gap-3 py-3 hover:text-primary">
              <span className="min-w-0"><span className="block truncate font-medium">{t.name}</span>
                <span className="block truncate text-sm text-muted-foreground">{t.ownerEmail ?? "Sin propietario"} · {formatDate(t.createdAt)}</span></span>
              {t.suspended ? <Badge variant="destructive">Suspendida</Badge> : <Badge variant={statusInfo(t.status).variant}>{statusInfo(t.status).label}</Badge>}
            </Link></li>)}</ul> : <p className="text-sm text-muted-foreground">Todavía no hay tiendas registradas.</p>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="flex-row items-center justify-between"><CardTitle>Actividad reciente</CardTitle>
          <Button asChild variant="link" size="sm"><Link href="/superadmin/auditoria">Auditoría<ArrowRight/></Link></Button></CardHeader>
        <CardContent>
          {data.activity.length ? <ol className="grid gap-4">{data.activity.map(a => <li key={a.id} className="grid gap-0.5 border-l-2 border-brand-100 pl-3">
            <p className="text-sm font-medium">{auditLabels[a.action] ?? a.action}{a.tenantName && <> · <Link className="text-primary hover:underline" href={"/superadmin/tiendas/" + a.tenant}>{a.tenantName}</Link></>}</p>
            <p className="text-xs text-muted-foreground">{a.actor} · {limaDateTime.format(new Date(a.createdAt))}</p>
          </li>)}</ol> : <p className="text-sm text-muted-foreground">Las acciones que hagas en la consola aparecerán aquí.</p>}
        </CardContent>
      </Card>
    </div>
    <p className="text-xs text-muted-foreground">Datos al {limaDate.format(new Date())}. El ingreso mensual suma el precio de lista de los planes activos o en gracia.</p>
  </>;
}
