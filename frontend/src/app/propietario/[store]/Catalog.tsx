"use client";
import {ownerApi, type Send} from "@/lib/api";

import {useRef, useState, type FormEvent} from "react";
import {Camera, ImageOff, Package, Pencil, Plus, QrCode, Search, Trash2} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {Pagination} from "@/components/console/Pagination";
import {money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

import EditVariant, {type InventoryRow as Row} from "./EditVariant";
import {ProductPhotos, photoUrl} from "./ProductPhotos";

type Props = {store:string; send:Send};
type Page = {rows:Row[]; hasNext:boolean};

export default function Catalog({store, send}:Props) {
  const [copies,setCopies] = useState<Record<string,number>>({});
  const [labelBusy,setLabelBusy] = useState(false);
  const totalLabels = Object.values(copies).reduce((a,b)=>a+b,0);
  const selectedVariants = Object.values(copies).filter(n=>n>0).length;
  const [editing,setEditing] = useState<Row|null>(null);
  const [photos,setPhotos] = useState<Row|null>(null);
  const [creating,setCreating] = useState(false);
  const [page,setPage] = useState(0);
  const [query,setQuery] = useState("");
  const [draft,setDraft] = useState("");
  const [refresh,setRefresh] = useState(0);
  const path = "stores/" + store + "/catalog";
  const params = new URLSearchParams({page:String(page)});
  if (query) params.set("query", query);
  const {data,error:listError,loading} = useApiData<Page>(ownerApi.url(path+"?"+params),refresh);

  async function downloadLabels() {
    setLabelBusy(true);
    try {
      const items = Object.entries(copies).filter(([,count])=>count > 0).map(([id,count])=>({id,copies:count}));
      const res = await send(path + "/labels", JSON.stringify({items}));
      if (!res.ok) throw new Error();
      const {pdf} = await res.json();
      const bytes = Uint8Array.from(atob(pdf), char=>char.charCodeAt(0));
      const url = URL.createObjectURL(new Blob([bytes], {type:"application/pdf"}));
      const link = document.createElement("a");
      link.href = url;
      link.download = "etiquetas-prendas.pdf";
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url), 60000);
      toast.success("PDF generado", {description:"Imprime en A4 a tamaño real."});
    } catch {toast.error("No se pudo generar el PDF. Comprueba la sesión y la selección.");}
    finally {setLabelBusy(false);}
  }

  function search(event:FormEvent) {
    event.preventDefault();
    setPage(0);
    setQuery(draft.trim());
  }

  const tooMany = totalLabels>300 || selectedVariants>100;
  return <div className="grid gap-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <form onSubmit={search} role="search" className="relative min-w-56 flex-1 sm:max-w-sm">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden/>
        <Input aria-label="Buscar en el catálogo" placeholder="Buscar prenda, SKU, talla o color" value={draft} onChange={e=>setDraft(e.target.value)} className="pl-9"/>
      </form>
      <Button onClick={()=>setCreating(true)}><Plus/>Nueva prenda</Button>
    </div>

    {totalLabels>0 && <Card className="flex flex-wrap items-center gap-3 p-3 text-sm">
      <QrCode className="size-4 text-primary"/>
      <span>{totalLabels} etiquetas QR de {selectedVariants} variantes</span>
      <Button size="sm" loading={labelBusy} disabled={tooMany} onClick={downloadLabels}>Descargar PDF</Button>
      <Button size="sm" variant="ghost" disabled={labelBusy} onClick={()=>setCopies({})}>Limpiar</Button>
      {tooMany && <span className="text-destructive">Hasta 300 etiquetas y 100 variantes por PDF.</span>}
    </Card>}

    {editing && <EditVariant key={editing.id} row={editing} path={path} send={send} close={()=>setEditing(null)}
      reload={()=>{setEditing(null); setRefresh(v=>v+1);}}/>}
    {photos && <ProductPhotos key={photos.productId} store={store} product={photos.productId} name={photos.name} open
      onOpenChange={open=>{ if (!open) setPhotos(null); }} onChanged={()=>setRefresh(v=>v+1)}/>}
    <NewProduct open={creating} onOpenChange={setCreating} path={path} send={send}
      onCreated={()=>{setCreating(false); setQuery(""); setDraft(""); setPage(0); setRefresh(v=>v+1);}}/>

    <Card className="p-0">
      {listError ? <div className="p-4"><Alert variant="destructive" title="No se pudo cargar el inventario">
          <button className="underline" onClick={()=>setRefresh(v=>v+1)}>Volver a cargar</button></Alert></div>
        : !data ? <div className="grid gap-2 p-4" aria-label="Cargando inventario…">{Array.from({length:5},(_,i)=><Skeleton key={i} className="h-14"/>)}</div>
        : !data.rows.length ? <EmptyState className="m-4" icon={Package} title={query ? "Sin resultados" : "Tu catálogo está vacío"}
            description={query ? "Prueba con otra palabra." : "Registra tu primera prenda con sus tallas, colores y stock."}
            action={!query && <Button onClick={()=>setCreating(true)}><Plus/>Nueva prenda</Button>}/>
        : <Table aria-busy={loading}>
          <TableHeader><TableRow>
            <TableHead>Prenda</TableHead><TableHead>SKU</TableHead><TableHead>Talla / color</TableHead>
            <TableHead className="text-right">Precio</TableHead><TableHead className="text-right">Disponible</TableHead>
            <TableHead>Etiquetas</TableHead><TableHead className="text-right">Acciones</TableHead>
          </TableRow></TableHeader>
          <TableBody>{data.rows.map(row=><TableRow key={row.id}>
            <TableCell><div className="flex items-center gap-3">
              <button type="button" onClick={()=>setPhotos(row)} aria-label={"Fotos de "+row.name}
                className="grid size-12 shrink-0 place-items-center overflow-hidden rounded-md border bg-muted text-muted-foreground hover:border-primary">
                {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
                {row.coverImage ? <img src={photoUrl(store,row.coverImage)} alt="" className="size-full object-cover"/> : <ImageOff className="size-4"/>}
              </button>
              <div className="min-w-0"><p className="truncate font-medium">{row.name}</p>
                {row.category && <p className="text-xs text-muted-foreground">{row.category}</p>}</div>
            </div></TableCell>
            <TableCell className="font-mono text-xs">{row.sku}</TableCell>
            <TableCell>{row.size}{row.color ? " · "+row.color : ""}</TableCell>
            <TableCell className="text-right tabular-nums">{money.format(row.price)}</TableCell>
            <TableCell className="text-right">
              <Badge variant={row.available<=0 ? "destructive" : row.available<=2 ? "warning" : "neutral"} className="tabular-nums">
                {row.available<=0 ? "Agotado" : row.available}</Badge>
              {row.reserved>0 && <span className="block text-xs text-muted-foreground">{row.reserved} reservadas</span>}
            </TableCell>
            <TableCell><Input type="number" min={0} max={300} disabled={labelBusy} value={copies[row.id] || ""} placeholder="0"
              aria-label={`Copias de ${row.sku}`} className="h-8 w-16"
              onChange={event=>{const n=Number(event.target.value); if(Number.isInteger(n) && n>=0 && n<=300) setCopies(old=>({...old,[row.id]:n}));}}/></TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" onClick={()=>setPhotos(row)}><Camera/>Fotos</Button>
              <Button variant="ghost" size="sm" disabled={!!editing} onClick={()=>setEditing(row)}><Pencil/>Editar</Button>
            </TableCell>
          </TableRow>)}</TableBody>
        </Table>}
      {data && <div className="border-t p-3"><Pagination page={page} hasNext={data.hasNext} onPage={setPage}/></div>}
    </Card>
  </div>;
}

function NewProduct({open,onOpenChange,path,send,onCreated}:{open:boolean; onOpenChange:(o:boolean)=>void; path:string; send:Send; onCreated:()=>void}) {
  const [keys,setKeys] = useState([0]);
  const nextKey = useRef(1);
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const variants = keys.map(key => ({
      sku:fields.get("sku"+key), size:fields.get("size"+key), color:fields.get("color"+key),
      price:fields.get("price"+key), stock:Number(fields.get("stock"+key))
    }));
    setBusy(true); setMessage("");
    try {
      const res = await send(path,JSON.stringify({name:fields.get("name"), category:fields.get("category"), variants}));
      if (!res.ok) {
        setMessage(res.status === 409 ? "Uno de los SKU ya existe en la tienda o se repite en este formulario." :
          res.status === 400 ? "Revisa los datos de la prenda y sus variantes." :
          res.status === 402 ? "Llegaste al límite de productos de tu plan." : "No se pudo registrar la prenda.");
        return;
      }
      form.reset(); setKeys([0]);
      toast.success("Prenda registrada", {description:"Agrega sus fotos desde el botón Fotos."});
      onCreated();
    } catch {setMessage("No se pudo confirmar el registro. Consulta el catálogo antes de reintentar.");}
    finally {setBusy(false);}
  }

  return <Dialog open={open} onOpenChange={o=>{onOpenChange(o); setMessage("");}}>
    <DialogContent className="max-w-3xl">
      <DialogHeader><DialogTitle>Nueva prenda</DialogTitle>
        <DialogDescription>Cada variante es una combinación de talla y color con su propio SKU, precio y stock.</DialogDescription></DialogHeader>
      <form onSubmit={save}>
        <fieldset disabled={busy} className="grid gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Nombre" htmlFor="np-name"><Input id="np-name" name="name" required maxLength={150}/></Field>
            <Field label="Categoría (opcional)" htmlFor="np-category"><Input id="np-category" name="category" maxLength={50} placeholder="Polos, Jeans…"/></Field>
          </div>
          {keys.map((key,i)=><fieldset key={key} className="grid gap-3 rounded-lg border p-3">
            <legend className="px-1 text-sm font-medium">Variante {i+1}</legend>
            <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
              <Field label="SKU" htmlFor={"sku"+key}><Input id={"sku"+key} name={"sku"+key} required maxLength={50}/></Field>
              <Field label="Talla" htmlFor={"size"+key}><Input id={"size"+key} name={"size"+key} required maxLength={10}/></Field>
              <Field label="Color" htmlFor={"color"+key}><Input id={"color"+key} name={"color"+key} required maxLength={30}/></Field>
              <Field label="Precio (S/)" htmlFor={"price"+key}><Input id={"price"+key} name={"price"+key} type="number" min="0.01" max="99999999.99" step="0.01" required/></Field>
              <Field label="Stock inicial" htmlFor={"stock"+key}><Input id={"stock"+key} name={"stock"+key} type="number" defaultValue="0" min="0" max="2147483647" step="1" required/></Field>
            </div>
            {keys.length>1 && <Button type="button" variant="ghost" size="sm" className="w-fit" onClick={()=>setKeys(keys.filter(k=>k!==key))}><Trash2/>Quitar variante</Button>}
          </fieldset>)}
          {message && <Alert variant="destructive">{message}</Alert>}
          <DialogFooter className="sm:justify-between">
            <Button type="button" variant="outline" disabled={keys.length>=100} onClick={()=>setKeys([...keys,nextKey.current++])}><Plus/>Añadir variante</Button>
            <Button type="submit" loading={busy}>Guardar prenda</Button>
          </DialogFooter>
        </fieldset>
      </form>
    </DialogContent>
  </Dialog>;
}
