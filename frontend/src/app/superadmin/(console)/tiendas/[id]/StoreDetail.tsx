"use client";

import Link from "next/link";
import {useState} from "react";
import {ArrowLeft, Ban, Copy, Mail, MessageCircle, Package, RotateCcw, ShieldCheck, ShoppingBag, Users, Wallet} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {StatCard} from "@/components/console/StatCard";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardHeader, CardTitle} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {toast} from "@/components/ui/toaster";
import {ApiError, superadminApi} from "@/lib/api";
import {statusInfo} from "@/lib/subscription";
import {auditLabels, formatDate, type TenantDetail} from "@/lib/superadmin";
import {limaDateTime, money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {SuspendDialog, SubscriptionDialog} from "./StoreActions";
import {Overrides} from "./Overrides";

export function StoreDetail({id}: {id: string}) {
  const {data, error, mutate, reload} = useApiData<TenantDetail>(superadminApi.url("tenants/" + id));
  const [invite, setInvite] = useState("");
  const [busy, setBusy] = useState(false);

  if (error) return <>
    <Back/>
    <Alert variant="destructive" title={error.status === 404 ? "No encontramos esa tienda" : "No se pudo cargar la tienda"}>{error.message}</Alert>
  </>;
  if (!data) return <><Back/><Skeleton className="h-12 w-80"/><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
    {Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-32"/>)}</div><Skeleton className="h-64"/></>;
  const s = data.store;
  const status = statusInfo(s.status);

  async function reactivate() {
    setBusy(true);
    try {
      mutate(await superadminApi.json<TenantDetail>("tenants/" + id + "/reactivate"));
      toast.success("Tienda reactivada");
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo reactivar."); }
    finally { setBusy(false); }
  }

  async function sendInvitation() {
    setBusy(true);
    try {
      const res = await superadminApi.json<{token: string; emailed: string}>("owners/" + id + "/invitation");
      setInvite(location.origin + "/propietario/" + id + "#" + res.token);
      toast.success(res.emailed === "true" ? "Invitación enviada a " + s.ownerEmail : "Invitación creada",
        {description: res.emailed === "true" ? "También puedes copiar el enlace." : "El correo no está configurado: copia el enlace y envíalo."});
      reload();
    } catch (e) { toast.error(e instanceof ApiError && e.status === 409 ? "El propietario ya activó su acceso." : "No se pudo crear la invitación."); }
    finally { setBusy(false); }
  }

  return <>
    <Back/>
    <PageHeader title={s.name}
      eyebrow={<span className="flex flex-wrap items-center gap-2">
        {s.suspendedAt && <Badge variant="destructive"><Ban/>Suspendida</Badge>}
        <Badge variant={status.variant}>{status.label}</Badge>
        {s.planName && <Badge variant="outline">{s.planName}</Badge>}
        <button className="inline-flex items-center gap-1 font-mono text-xs hover:text-foreground" title="Copiar identificador"
          onClick={() => { navigator.clipboard?.writeText(s.id); toast.success("Identificador copiado"); }}>{s.id}<Copy className="size-3"/></button>
      </span>}
      actions={<>
        {!s.ownerActive && s.ownerEmail && <Button variant="outline" loading={busy} onClick={sendInvitation}><Mail/>Invitar propietario</Button>}
        <SubscriptionDialog id={id} detail={data} onDone={mutate}/>
        {s.suspendedAt
          ? <Button variant="outline" loading={busy} onClick={reactivate}><RotateCcw/>Reactivar</Button>
          : <SuspendDialog id={id} name={s.name} onDone={mutate}/>}
      </>}/>

    {s.suspendedAt && <Alert variant="destructive" title={"Suspendida el " + formatDate(s.suspendedAt)}>
      {s.suspendedReason} El panel y la tienda pública responden “suspendida” hasta que la reactives.</Alert>}
    {invite && <Card className="grid gap-2 p-4"><p className="text-sm font-medium">Enlace privado de activación (vence en 24 horas)</p>
      <Input aria-label="Enlace privado de activación" value={invite} readOnly onFocus={e => e.target.select()}/></Card>}

    <Tabs defaultValue="resumen">
      <TabsList className="max-w-full overflow-x-auto">
        <TabsTrigger value="resumen">Resumen</TabsTrigger>
        <TabsTrigger value="suscripcion">Suscripción</TabsTrigger>
        <TabsTrigger value="capacidades">Capacidades</TabsTrigger>
        <TabsTrigger value="auditoria">Auditoría</TabsTrigger>
      </TabsList>

      <TabsContent value="resumen" className="grid gap-6">
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard label="Ventas web (30 días)" icon={ShoppingBag} value={money.format(s.webRevenue30d)} hint={s.webOrders30d + " pedidos"}/>
          <StatCard label="Ventas POS (30 días)" icon={Wallet} value={money.format(s.posRevenue30d)} hint={s.posSales30d + " ventas"}/>
          <StatCard label="Catálogo" icon={Package} value={s.products} hint={s.variants + " variantes"}/>
          <StatCard label="Contactos en CRM" icon={Users} value={s.contacts}/>
        </div>
        <div className="grid gap-6 lg:grid-cols-3">
          <Card><CardHeader><CardTitle>Propietario</CardTitle></CardHeader><CardContent>
            <Facts rows={[["Nombre", s.ownerName ?? "—"], ["Correo", s.ownerEmail ?? "—"], ["Teléfono", s.ownerPhone ?? "—"],
              ["Acceso", s.ownerActive ? "Activo" : "Sin activar"],
              ["2FA", s.ownerMfa ? <span key="m" className="inline-flex items-center gap-1 text-success"><ShieldCheck className="size-4"/>Activa</span> : "No configurada"]]}/>
          </CardContent></Card>
          <Card><CardHeader><CardTitle>Suscripción</CardTitle></CardHeader><CardContent>
            <Facts rows={[["Plan", s.planName ?? "—"], ["Estado", status.label],
              ["Fin de prueba", formatDate(s.trialEndsAt)], ["Periodo pagado hasta", formatDate(s.periodEnd)],
              ["Gracia hasta", formatDate(s.graceEndsAt)], ["Culqi", s.culqiSubscriptionId ?? "Sin suscripción"]]}/>
          </CardContent></Card>
          <Card><CardHeader><CardTitle>Integraciones</CardTitle></CardHeader><CardContent>
            <Facts rows={[["WhatsApp", <span key="w" className="inline-flex items-center gap-1"><MessageCircle className="size-4"/>{s.whatsappProvider === "CLOUD_API" ? "Cloud API oficial" : "Evolution (QR)"}</span>],
              ["Cobros Culqi", s.culqiLinked ? "Conectado" : "Sin conectar"], ["Dominio", s.domain ?? "Pendiente"],
              ["Alta", formatDate(s.createdAt)]]}/>
          </CardContent></Card>
        </div>
      </TabsContent>

      <TabsContent value="suscripcion">
        <Card><CardHeader><CardTitle>Historial de la suscripción</CardTitle></CardHeader><CardContent>
          {data.events.length ? <ol className="grid gap-4">{data.events.map((e, i) => <li key={i} className="grid gap-0.5 border-l-2 border-brand-100 pl-3">
            <p className="text-sm font-medium">{e.fromStatus ? statusInfo(e.fromStatus).label + " → " : ""}{statusInfo(e.toStatus).label}
              {e.planCode && <span className="text-muted-foreground"> · {e.planCode}</span>}</p>
            <p className="text-xs text-muted-foreground">{sourceLabel(e.source)} · {limaDateTime.format(new Date(e.createdAt))}{e.detail && " · " + e.detail}</p>
          </li>)}</ol> : <p className="text-sm text-muted-foreground">Sin movimientos.</p>}
        </CardContent></Card>
      </TabsContent>

      <TabsContent value="capacidades"><Overrides id={id} overrides={data.overrides} onChange={reload}/></TabsContent>

      <TabsContent value="auditoria">
        <Card><CardContent className="p-0">
          {data.audit.length ? <Table><TableHeader><TableRow><TableHead>Acción</TableHead><TableHead>Detalle</TableHead><TableHead>Quién</TableHead><TableHead>Cuándo</TableHead></TableRow></TableHeader>
            <TableBody>{data.audit.map(a => <TableRow key={a.id}><TableCell className="font-medium">{auditLabels[a.action] ?? a.action}</TableCell>
              <TableCell className="max-w-md text-muted-foreground">{a.detail ?? "—"}</TableCell><TableCell>{a.actor}</TableCell>
              <TableCell className="whitespace-nowrap text-muted-foreground">{limaDateTime.format(new Date(a.createdAt))}</TableCell></TableRow>)}</TableBody></Table>
            : <p className="p-6 text-sm text-muted-foreground">Sin acciones registradas para esta tienda.</p>}
        </CardContent></Card>
      </TabsContent>
    </Tabs>
  </>;
}

function Back() {
  return <Link href="/superadmin/tiendas" className="flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
    <ArrowLeft className="size-4"/>Tiendas</Link>;
}

function Facts({rows}: {rows: [string, React.ReactNode][]}) {
  return <dl className="grid gap-3 text-sm">{rows.map(([k, v]) => <div key={k} className="flex justify-between gap-4">
    <dt className="text-muted-foreground">{k}</dt><dd className="min-w-0 truncate text-right font-medium">{v}</dd></div>)}</dl>;
}

function sourceLabel(source: string) {
  return ({REGISTRATION: "Alta", SIGNUP: "Prueba gratis", SCHEDULER: "Automático", CHECKOUT: "Checkout", CULQI_WEBHOOK: "Culqi", SUPERADMIN: "Superadmin"} as Record<string, string>)[source] ?? source;
}
