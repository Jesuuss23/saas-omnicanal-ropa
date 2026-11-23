"use client";

import {useState, type FormEvent} from "react";
import {Link2, MessageCircle, Search, Undo2, UserRound, Users} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {formatDate, money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Row = {id: string; fullName: string | null; phone: string | null; email: string | null; google: boolean; whatsapp: boolean;
  orders: number; spent: number; lastOrderAt: string | null; updatedAt: string};
type Merge = {id: string; survivorId: string; mergedId: string; mergedName: string | null; movedOrders: number; mergedBy: string;
  mergedAt: string; revertedAt: string | null};
type Duplicate = {first: Row; second: Row; reason: string};
type Order = {id: string; createdAt: string; status: string; total: number; deliveryMethod: string | null; trackingPath: string};
type Detail = {contact: {id: string; fullName: string | null; phone: string | null; email: string | null; google: boolean; mergedInto: string | null; createdAt: string};
  whatsapp: boolean; orders: Order[]; merges: Merge[]};

const statusLabels: Record<string, string> = {PENDING_PAYMENT: "Por cobrar", PAID: "Pagado", CANCELLED: "Cancelado", EXPIRED: "Vencido"};
const label = (c: {fullName: string | null; phone: string | null; email: string | null}) => c.fullName || c.email || (c.phone ? "+" + c.phone : "Sin nombre");

export default function UnifiedContacts({store, canMerge}: {store: string; canMerge: boolean}) {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [version, setVersion] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const base = "stores/" + store + "/contacts";
  const list = useApiData<{rows: Row[]; hasNext: boolean}>(ownerApi.url(base + "?" + new URLSearchParams({query, page: String(page)})), version);
  const duplicates = useApiData<Duplicate[]>(ownerApi.url(base + "/duplicates"), version);
  const merges = useApiData<Merge[]>(ownerApi.url(base + "/merges"), version);
  const changed = () => setVersion(v => v + 1);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setQuery(String(new FormData(event.currentTarget).get("q") ?? "").trim());
    setPage(0);
  }

  return <div className="grid gap-6">
    {duplicates.data && duplicates.data.length > 0 && <Card>
      <CardHeader><CardTitle>Posibles duplicados</CardTitle>
        <CardDescription>Compraron con su cuenta usando un teléfono que ya estaba en otro contacto. Únelos si son la misma persona; puedes deshacerlo.</CardDescription></CardHeader>
      <CardContent className="grid gap-3">{duplicates.data.map(d => <DuplicateRow key={d.first.id + d.second.id} store={store} pair={d} canMerge={canMerge} onMerged={changed}/>)}</CardContent>
    </Card>}

    <form onSubmit={search} role="search" className="flex max-w-xl gap-2">
      <label className="relative flex-1"><span className="sr-only">Buscar contacto</span>
        <Search aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"/>
        <Input name="q" defaultValue={query} maxLength={100} placeholder="Nombre, teléfono o correo" className="pl-9"/></label>
      <Button type="submit" variant="outline">Buscar</Button>
    </form>

    {list.error && <Alert variant="destructive">{list.error.message}</Alert>}
    <Card className="p-0">
      {!list.data ? <div className="grid gap-2 p-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>
        : !list.data.rows.length ? <EmptyState icon={Users} title={query ? "Sin resultados" : "Aún no hay contactos"}
          description={query ? "Prueba con otra parte del nombre, el teléfono o el correo." : "Aparecen cuando alguien compra en tu tienda web o ingresa con su cuenta."}/>
        : <Table><TableHeader><TableRow><TableHead>Contacto</TableHead><TableHead>Canales</TableHead><TableHead className="text-right">Pedidos</TableHead>
            <TableHead className="text-right">Pagado</TableHead><TableHead>Última compra</TableHead></TableRow></TableHeader>
          <TableBody>{list.data.rows.map(r => <TableRow key={r.id} className="cursor-pointer" onClick={() => setOpen(r.id)}>
            <TableCell><button type="button" className="text-left font-medium hover:underline" onClick={() => setOpen(r.id)}>{label(r)}</button>
              <span className="block text-xs text-muted-foreground">{[r.phone && "+" + r.phone, r.email].filter(Boolean).join(" · ")}</span></TableCell>
            <TableCell><Channels row={r}/></TableCell>
            <TableCell className="text-right tabular-nums">{r.orders}</TableCell>
            <TableCell className="text-right tabular-nums">{money.format(r.spent)}</TableCell>
            <TableCell className="text-sm text-muted-foreground">{r.lastOrderAt ? formatDate(r.lastOrderAt) : "—"}</TableCell>
          </TableRow>)}</TableBody></Table>}
    </Card>
    {list.data && (page > 0 || list.data.hasNext) && <div className="flex justify-end gap-2">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Anterior</Button>
      <Button variant="outline" size="sm" disabled={!list.data.hasNext} onClick={() => setPage(p => p + 1)}>Siguiente</Button>
    </div>}

    {merges.data && merges.data.length > 0 && <Card>
      <CardHeader><CardTitle>Historial de fusiones</CardTitle><CardDescription>Puedes deshacer la última fusión de cada contacto.</CardDescription></CardHeader>
      <CardContent><ul className="divide-y">{merges.data.map(m => <MergeItem key={m.id} store={store} merge={m} canMerge={canMerge} onReverted={changed}/>)}</ul></CardContent>
    </Card>}

    <ContactDialog store={store} id={open} onClose={() => setOpen(null)}/>
  </div>;
}

function Channels({row}: {row: Pick<Row, "google" | "whatsapp" | "orders">}) {
  return <div className="flex flex-wrap gap-1">
    {row.google && <Badge variant="outline"><UserRound/>Cuenta</Badge>}
    {row.whatsapp && <Badge variant="success"><MessageCircle/>WhatsApp</Badge>}
    {row.orders > 0 && <Badge variant="neutral">Tienda web</Badge>}
  </div>;
}

function DuplicateRow({store, pair, canMerge, onMerged}: {store: string; pair: Duplicate; canMerge: boolean; onMerged: () => void}) {
  const [busy, setBusy] = useState(false);
  // Sobrevive la cuenta con Google (el comprador sigue viendo sus pedidos al ingresar).
  const [survivor, merged] = pair.second.google && !pair.first.google ? [pair.second, pair.first] : [pair.first, pair.second];
  async function merge() {
    setBusy(true);
    try {
      await ownerApi.json("stores/" + store + "/contacts/" + survivor.id + "/merge", {mergedId: merged.id});
      toast.success("Contactos unidos", {description: label(merged) + " ahora es parte de " + label(survivor) + "."});
      onMerged();
    } catch (e) { toast.error(e instanceof ApiError && e.status === 409 ? "Uno de los contactos ya cambió. Actualiza la página." : "No se pudo unir."); }
    finally { setBusy(false); }
  }
  return <div className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm">
    <div className="min-w-0 flex-1"><p className="font-medium">{label(survivor)} <span className="text-muted-foreground">y</span> {label(merged)}</p>
      <p className="text-xs text-muted-foreground">{pair.reason}</p></div>
    {canMerge && <Button size="sm" loading={busy} onClick={merge}>{!busy && <Link2/>}Unir contactos</Button>}
  </div>;
}

function MergeItem({store, merge, canMerge, onReverted}: {store: string; merge: Merge; canMerge: boolean; onReverted: () => void}) {
  const [busy, setBusy] = useState(false);
  async function revert() {
    setBusy(true);
    try {
      await ownerApi.json("stores/" + store + "/contacts/merges/" + merge.id + "/revert");
      toast.success("Fusión deshecha");
      onReverted();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo deshacer."); }
    finally { setBusy(false); }
  }
  return <li className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
    <span><strong>{merge.mergedName ?? "Contacto"}</strong> se unió ({merge.movedOrders} {merge.movedOrders === 1 ? "pedido" : "pedidos"}) · {merge.mergedBy} · {formatDate(merge.mergedAt)}</span>
    {merge.revertedAt ? <Badge variant="neutral">Deshecha</Badge>
      : canMerge && <Button size="sm" variant="ghost" loading={busy} onClick={revert}>{!busy && <Undo2/>}Deshacer</Button>}
  </li>;
}

function ContactDialog({store, id, onClose}: {store: string; id: string | null; onClose: () => void}) {
  const {data, error} = useApiData<Detail>(id ? ownerApi.url("stores/" + store + "/contacts/" + id) : null);
  return <Dialog open={id !== null} onOpenChange={next => { if (!next) onClose(); }}>
    <DialogContent className="max-w-lg">
      <DialogHeader><DialogTitle>{data ? label(data.contact) : "Contacto"}</DialogTitle>
        <DialogDescription>{data ? [data.contact.phone && "+" + data.contact.phone, data.contact.email].filter(Boolean).join(" · ") || "Sin datos de contacto" : "Cargando…"}</DialogDescription></DialogHeader>
      {error && <Alert variant="destructive">{error.message}</Alert>}
      {data && <div className="grid gap-4 text-sm">
        <div className="flex flex-wrap gap-1">
          {data.contact.google && <Badge variant="outline"><UserRound/>Ingresa con Google</Badge>}
          {data.whatsapp && data.contact.phone && <a href={"https://wa.me/" + data.contact.phone} target="_blank" rel="noopener noreferrer"><Badge variant="success"><MessageCircle/>Abrir WhatsApp</Badge></a>}
          <Badge variant="neutral">Desde {formatDate(data.contact.createdAt)}</Badge>
        </div>
        <div><p className="mb-2 font-medium">Pedidos web</p>
          {!data.orders.length ? <p className="text-muted-foreground">Sin pedidos todavía.</p>
            : <ul className="divide-y rounded-lg border">{data.orders.map(o => <li key={o.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <span>{formatDate(o.createdAt)} · {o.deliveryMethod === "DELIVERY" ? "Delivery" : "Recojo"}</span>
              <span className="flex items-center gap-2"><Badge variant={o.status === "PAID" ? "success" : "neutral"}>{statusLabels[o.status] ?? o.status}</Badge>
                <span className="tabular-nums">{money.format(o.total)}</span></span>
            </li>)}</ul>}
        </div>
      </div>}
    </DialogContent>
  </Dialog>;
}
