"use client";

import Link from "next/link";
import {useRouter} from "next/navigation";
import {useState, type FormEvent} from "react";
import {Search, Store} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Pagination, type Page} from "@/components/console/Pagination";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Card} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {Input, NativeSelect} from "@/components/ui/input";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {superadminApi} from "@/lib/api";
import {subscriptionStatus, statusInfo} from "@/lib/subscription";
import {formatDate, type TenantRow} from "@/lib/superadmin";
import {useApiData} from "@/lib/useApiData";
import {NewStoreDialog} from "./NewStoreDialog";

export function StoreList({initialQuery, initialStatus, openNew}: {initialQuery: string; initialStatus: string; openNew: boolean}) {
  const router = useRouter();
  const [query, setQuery] = useState(initialQuery);
  const [draft, setDraft] = useState(initialQuery);
  const [status, setStatus] = useState(initialStatus);
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({page: String(page), size: "25"});
  if (query) params.set("q", query);
  if (status) params.set("status", status);
  const {data, error, loading} = useApiData<Page<TenantRow>>(superadminApi.url("tenants?" + params));

  function search(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setQuery(draft.trim());
  }

  return <>
    <PageHeader title="Tiendas" description="Busca por nombre, correo del propietario o identificador."
      actions={<NewStoreDialog defaultOpen={openNew} onCreated={id => router.push("/superadmin/tiendas/" + id)}/>}/>
    <Card className="grid gap-4 p-4">
      <form onSubmit={search} className="flex flex-wrap gap-3" role="search">
        <div className="relative min-w-56 flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden/>
          <Input aria-label="Buscar tiendas" placeholder="Buscar tienda o correo" value={draft} onChange={e => setDraft(e.target.value)} className="pl-9"/>
        </div>
        <NativeSelect aria-label="Filtrar por estado" value={status} className="w-auto"
          onChange={e => { setStatus(e.target.value); setPage(0); }}>
          <option value="">Todos los estados</option>
          {Object.entries(subscriptionStatus).filter(([k]) => k !== "NONE").map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          <option value="SUSPENDED_STORE">Suspendidas por la plataforma</option>
        </NativeSelect>
      </form>
      {error && <Alert variant="destructive">{error.message}</Alert>}
      {loading && !data && <div className="grid gap-2">{Array.from({length: 6}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>}
      {data && (data.items.length === 0
        ? <EmptyState icon={Store} title={query || status ? "Sin resultados" : "Todavía no hay tiendas"}
            description={query || status ? "Prueba con otro nombre o quita el filtro." : "Registra la primera tienda o comparte la prueba gratis."}/>
        : <Table>
          <TableHeader><TableRow>
            <TableHead>Tienda</TableHead><TableHead>Propietario</TableHead><TableHead>Plan</TableHead>
            <TableHead>Estado</TableHead><TableHead className="text-right">Productos</TableHead><TableHead>Alta</TableHead>
          </TableRow></TableHeader>
          <TableBody>{data.items.map(t => <TableRow key={t.id} className="cursor-pointer" onClick={() => router.push("/superadmin/tiendas/" + t.id)}>
            <TableCell><Link href={"/superadmin/tiendas/" + t.id} className="font-medium hover:text-primary" onClick={e => e.stopPropagation()}>{t.name}</Link></TableCell>
            <TableCell><span className="block max-w-56 truncate">{t.ownerEmail ?? "—"}</span>
              {!t.ownerActive && t.ownerEmail && <span className="text-xs text-muted-foreground">Acceso sin activar</span>}</TableCell>
            <TableCell>{t.planName ?? "—"}</TableCell>
            <TableCell className="whitespace-nowrap">
              {t.suspended && <Badge variant="destructive" className="mr-1">Suspendida</Badge>}
              <Badge variant={statusInfo(t.status).variant}>{statusInfo(t.status).label}</Badge>
              {t.status === "TRIAL" && t.trialEndsAt && <span className="ml-2 text-xs text-muted-foreground">hasta {formatDate(t.trialEndsAt)}</span>}
            </TableCell>
            <TableCell className="text-right tabular-nums">{t.products}</TableCell>
            <TableCell className="whitespace-nowrap text-muted-foreground">{formatDate(t.createdAt)}</TableCell>
          </TableRow>)}</TableBody>
        </Table>)}
      {data && <Pagination page={data.page} hasNext={data.hasNext} onPage={setPage}/>}
    </Card>
  </>;
}
