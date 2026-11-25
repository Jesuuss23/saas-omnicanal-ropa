"use client";

import Link from "next/link";
import {useState} from "react";
import {CreditCard} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Pagination, type Page} from "@/components/console/Pagination";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Card} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {NativeSelect} from "@/components/ui/input";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {superadminApi} from "@/lib/api";
import {type PaymentEvent} from "@/lib/superadmin";
import {limaDateTime} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

const statuses: Record<string, {label: string; variant: "success" | "warning" | "destructive" | "neutral"}> = {
  RECEIVED: {label: "Recibido", variant: "warning"},
  PROCESSED: {label: "Procesado", variant: "success"},
  IGNORED: {label: "Ignorado", variant: "neutral"},
  FAILED: {label: "Falló", variant: "destructive"},
};
const providers: Record<string, string> = {CULQI: "Culqi (suscripciones y tiendas)", MERCADOPAGO: "Mercado Pago (histórico)"};

export default function Payments() {
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({page: String(page), size: "50"});
  if (provider) params.set("provider", provider);
  if (status) params.set("status", status);
  const {data, error} = useApiData<Page<PaymentEvent>>(superadminApi.url("payments?" + params));
  return <>
    <PageHeader title="Pagos" description="Notificaciones de Culqi: suscripciones del SaaS y cobros de cada tienda. Cada una se valida y se vuelve a consultar al proveedor antes de cambiar un estado."/>
    <Card className="grid gap-4 p-4">
      <div className="flex flex-wrap gap-3">
        <NativeSelect aria-label="Proveedor" className="w-auto" value={provider} onChange={e => { setProvider(e.target.value); setPage(0); }}>
          <option value="">Todos los proveedores</option>
          {Object.entries(providers).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </NativeSelect>
        <NativeSelect aria-label="Estado" className="w-auto" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}>
          <option value="">Todos los estados</option>
          {Object.entries(statuses).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </NativeSelect>
      </div>
      {error && <Alert variant="destructive">{error.message}</Alert>}
      {!data && !error && <div className="grid gap-2">{Array.from({length: 5}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>}
      {data && (data.items.length === 0 ? <EmptyState icon={CreditCard} title="Sin notificaciones" description="Aparecerán cuando Culqi avise un cobro."/>
        : <Table><TableHeader><TableRow><TableHead>Recibido</TableHead><TableHead>Proveedor</TableHead><TableHead>Tienda</TableHead><TableHead>Evento</TableHead><TableHead>Estado</TableHead></TableRow></TableHeader>
          <TableBody>{data.items.map(e => <TableRow key={e.provider + e.eventId}>
            <TableCell className="whitespace-nowrap text-muted-foreground">{limaDateTime.format(new Date(e.receivedAt))}</TableCell>
            <TableCell>{e.provider === "MERCADOPAGO" ? "Mercado Pago" : e.provider === "CULQI" ? "Culqi" : e.provider}</TableCell>
            <TableCell>{e.tenant ? <Link className="hover:text-primary" href={"/superadmin/tiendas/" + e.tenant}>{e.tenantName ?? e.tenant}</Link> : "—"}</TableCell>
            <TableCell><span className="block">{e.type ?? "—"}</span><span className="block max-w-56 truncate font-mono text-xs text-muted-foreground" title={e.eventId}>{e.eventId}</span></TableCell>
            <TableCell><Badge variant={(statuses[e.status] ?? statuses.IGNORED).variant}>{(statuses[e.status] ?? {label: e.status}).label}</Badge>
              {e.error && <span className="mt-1 block max-w-64 text-xs text-muted-foreground">{e.error}</span>}</TableCell>
          </TableRow>)}</TableBody></Table>)}
      {data && <Pagination page={data.page} hasNext={data.hasNext} onPage={setPage}/>}
    </Card>
  </>;
}
