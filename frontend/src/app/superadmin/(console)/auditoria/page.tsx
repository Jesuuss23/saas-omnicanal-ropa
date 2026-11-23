"use client";

import Link from "next/link";
import {useState} from "react";
import {ScrollText} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Pagination, type Page} from "@/components/console/Pagination";
import {Alert} from "@/components/ui/alert";
import {Card} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {superadminApi} from "@/lib/api";
import {auditLabels, type AuditEntry} from "@/lib/superadmin";
import {limaDateTime} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

export default function Audit() {
  const [page, setPage] = useState(0);
  const {data, error} = useApiData<Page<AuditEntry>>(superadminApi.url("audit?size=50&page=" + page));
  return <>
    <PageHeader title="Auditoría" description="Cada cambio hecho desde la consola: quién, cuándo y sobre qué tienda."/>
    <Card className="grid gap-4 p-4">
      {error && <Alert variant="destructive">{error.message}</Alert>}
      {!data && !error && <div className="grid gap-2">{Array.from({length: 6}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>}
      {data && (data.items.length === 0 ? <EmptyState icon={ScrollText} title="Sin acciones registradas"/>
        : <Table><TableHeader><TableRow><TableHead>Cuándo</TableHead><TableHead>Acción</TableHead><TableHead>Tienda</TableHead><TableHead>Detalle</TableHead><TableHead>Quién</TableHead></TableRow></TableHeader>
          <TableBody>{data.items.map(a => <TableRow key={a.id}>
            <TableCell className="whitespace-nowrap text-muted-foreground">{limaDateTime.format(new Date(a.createdAt))}</TableCell>
            <TableCell className="font-medium">{auditLabels[a.action] ?? a.action}</TableCell>
            <TableCell>{a.tenant ? <Link className="hover:text-primary" href={"/superadmin/tiendas/" + a.tenant}>{a.tenantName ?? a.tenant}</Link> : "Plataforma"}</TableCell>
            <TableCell className="max-w-md text-muted-foreground">{a.detail ?? "—"}</TableCell>
            <TableCell>{a.actor}</TableCell>
          </TableRow>)}</TableBody></Table>)}
      {data && <Pagination page={data.page} hasNext={data.hasNext} onPage={setPage}/>}
    </Card>
  </>;
}
