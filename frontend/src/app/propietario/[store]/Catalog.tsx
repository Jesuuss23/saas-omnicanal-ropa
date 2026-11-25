"use client";
import {errorMessage, ownerApi, type Send} from "@/lib/api";

import {useState, type FormEvent} from "react";
import {Camera, ImageOff, Package, Pencil, Plus, QrCode, Search, Trash2, X} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Field, Label} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {Pagination} from "@/components/console/Pagination";
import {cn, money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

import EditVariant, {type InventoryRow as Row} from "./EditVariant";
import {ProductPhotos, photoUrl} from "./ProductPhotos";
import {ALL_SIZES, CategorySelect, COMMON_COLORS, SIZE_GROUPS, SizeSelect, skuBase, SubcategoryInput} from "./catalogOptions";

type Props = {store:string; send:Send};
type Page = {rows:Row[]; hasNext:boolean};

/** Descarga un PDF que llega en base64 desde el backend. */
function downloadPdf(base64:string, name:string) {
  const bytes = Uint8Array.from(atob(base64), char=>char.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], {type:"application/pdf"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(()=>URL.revokeObjectURL(url), 60000);
}

export default function Catalog({store, send}:Props) {
  // Una etiqueta (un QR) por variante: al escanearla en caja la prenda entra con su talla, color y precio.
  const [labels,setLabels] = useState<Record<string,string>>({});
  const [labelBusy,setLabelBusy] = useState(false);
  const selected = Object.keys(labels);
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

  async function downloadLabels(variants:string[]) {
    setLabelBusy(true);
    try {
      const res = await send(path + "/labels", JSON.stringify({variants}));
      if (!res.ok) throw new Error(await errorMessage(res, "Comprueba la sesión y la selección."));
      const {pdf,labels:count} = await res.json() as {pdf:string; labels:number};
      downloadPdf(pdf, variants.length === 1 ? "etiqueta-prenda.pdf" : "etiquetas-prendas.pdf");
      toast.success(count === 1 ? "Etiqueta generada" : count + " etiquetas generadas", {description:"Imprime en A4 a tamaño real. Hay 18 etiquetas por hoja."});
    } catch (e) {toast.error("No se pudo generar el PDF", {description:e instanceof Error ? e.message : undefined});}
    finally {setLabelBusy(false);}
  }

  function toggleLabel(row:Row) {
    setLabels(old=>{
      const next = {...old};
      if (next[row.id]) delete next[row.id]; else next[row.id] = row.sku;
      return next;
    });
  }

  function search(event:FormEvent) {
    event.preventDefault();
    setPage(0);
    setQuery(draft.trim());
  }

  const rowsOnPage = data?.rows ?? [];
  const allOnPage = rowsOnPage.length > 0 && rowsOnPage.every(r=>labels[r.id]);
  const tooMany = selected.length>200;
  return <div className="grid gap-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <form onSubmit={search} role="search" className="relative min-w-56 flex-1 sm:max-w-sm">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden/>
        <Input aria-label="Buscar en el catálogo" placeholder="Buscar prenda, SKU, talla o color" value={draft} onChange={e=>setDraft(e.target.value)} className="pl-9"/>
      </form>
      <Button onClick={()=>setCreating(true)}><Plus/>Nueva prenda</Button>
    </div>

    {selected.length>0 && <Card className="flex flex-wrap items-center gap-3 p-3 text-sm" role="region" aria-label="Etiquetas seleccionadas">
      <QrCode className="size-4 text-primary"/>
      <span>{selected.length === 1 ? "1 etiqueta QR" : selected.length + " etiquetas QR"} · una por talla y color</span>
      <Button size="sm" loading={labelBusy} disabled={tooMany} onClick={()=>downloadLabels(selected)}>Descargar PDF</Button>
      <Button size="sm" variant="ghost" disabled={labelBusy} onClick={()=>setLabels({})}>Limpiar</Button>
      {tooMany && <span className="text-destructive">Hasta 200 etiquetas por PDF.</span>}
    </Card>}

    {editing && <EditVariant key={editing.id} row={editing} path={path} send={send} close={()=>setEditing(null)}
      reload={()=>{setEditing(null); setRefresh(v=>v+1);}}/>}
    {photos && <ProductPhotos key={photos.productId} store={store} product={photos.productId} name={photos.name} open
      onOpenChange={open=>{ if (!open) setPhotos(null); }} onChanged={()=>setRefresh(v=>v+1)}/>}
    {creating && <NewProduct open onOpenChange={setCreating} path={path} send={send}
      onCreated={()=>{setCreating(false); setQuery(""); setDraft(""); setPage(0); setRefresh(v=>v+1);}}/>}

    <Card className="p-0">
      {listError ? <div className="p-4"><Alert variant="destructive" title="No se pudo cargar el inventario">
          <button className="underline" onClick={()=>setRefresh(v=>v+1)}>Volver a cargar</button></Alert></div>
        : !data ? <div className="grid gap-2 p-4" aria-label="Cargando inventario…">{Array.from({length:5},(_,i)=><Skeleton key={i} className="h-14"/>)}</div>
        : !data.rows.length ? <EmptyState className="m-4" icon={Package} title={query ? "Sin resultados" : "Tu catálogo está vacío"}
            description={query ? "Prueba con otra palabra." : "Registra tu primera prenda con sus tallas, colores y stock."}
            action={!query && <Button onClick={()=>setCreating(true)}><Plus/>Nueva prenda</Button>}/>
        : <Table aria-busy={loading}>
          <TableHeader><TableRow>
            <TableHead className="w-10"><input type="checkbox" className="size-4 accent-primary" aria-label="Elegir todas las variantes de esta página para etiquetas"
              checked={allOnPage} onChange={()=>setLabels(old=>{
                const next = {...old};
                rowsOnPage.forEach(r=>{ if (allOnPage) delete next[r.id]; else next[r.id] = r.sku; });
                return next;
              })}/></TableHead>
            <TableHead>Prenda</TableHead><TableHead>SKU</TableHead><TableHead>Talla / color</TableHead>
            <TableHead className="text-right">Precio</TableHead><TableHead className="text-right">Disponible</TableHead>
            <TableHead className="text-right">Acciones</TableHead>
          </TableRow></TableHeader>
          <TableBody>{data.rows.map((row,i)=>{
            const first = i===0 || data.rows[i-1].productId !== row.productId;
            return <TableRow key={row.id} className={cn(!first && "border-t-0")}>
            <TableCell><input type="checkbox" className="size-4 accent-primary" checked={!!labels[row.id]}
              onChange={()=>toggleLabel(row)} aria-label={"Etiqueta QR de "+row.sku}/></TableCell>
            <TableCell>{first ? <div className="flex items-center gap-3">
              <button type="button" onClick={()=>setPhotos(row)} aria-label={"Fotos de "+row.name}
                className="grid size-12 shrink-0 place-items-center overflow-hidden rounded-md border bg-muted text-muted-foreground hover:border-primary">
                {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
                {row.coverImage ? <img src={photoUrl(store,row.coverImage)} alt="" className="size-full object-cover"/> : <ImageOff className="size-4"/>}
              </button>
              <div className="min-w-0"><p className="truncate font-medium">{row.name}</p>
                {row.category && <p className="text-xs text-muted-foreground">{row.category}{row.subcategory ? " · " + row.subcategory : ""}</p>}</div>
            </div> : <span className="sr-only">{row.name}</span>}</TableCell>
            <TableCell className="font-mono text-xs">{row.sku}</TableCell>
            <TableCell><Badge variant="outline">{row.size}</Badge>{row.color ? <span className="ml-2">{row.color}</span> : null}</TableCell>
            <TableCell className="text-right tabular-nums">{money.format(row.price)}</TableCell>
            <TableCell className="text-right">
              <Badge variant={row.available<=0 ? "destructive" : row.available<=2 ? "warning" : "neutral"} className="tabular-nums">
                {row.available<=0 ? "Agotado" : row.available}</Badge>
              {row.reserved>0 && <span className="block text-xs text-muted-foreground">{row.reserved} reservadas</span>}
            </TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <Button variant="ghost" size="sm" disabled={labelBusy} onClick={()=>downloadLabels([row.id])} aria-label={"Imprimir etiqueta de "+row.sku}><QrCode/>QR</Button>
              {first && <Button variant="ghost" size="sm" onClick={()=>setPhotos(row)}><Camera/>Fotos</Button>}
              <Button variant="ghost" size="sm" disabled={!!editing} onClick={()=>setEditing(row)} aria-label={"Editar "+row.sku}><Pencil/>Editar</Button>
            </TableCell>
          </TableRow>;})}</TableBody>
        </Table>}
      {data && <div className="border-t p-3"><Pagination page={page} hasNext={data.hasNext} onPage={setPage}/></div>}
    </Card>
  </div>;
}

type Override = {sku?:string; price?:string; stock?:string};

/** Alta guiada: se eligen tallas y colores y se arma una variante por combinación, con SKU automático legible. */
function NewProduct({open,onOpenChange,path,send,onCreated}:{open:boolean; onOpenChange:(o:boolean)=>void; path:string; send:Send; onCreated:()=>void}) {
  const categories = useApiData<string[]>(ownerApi.url(path+"/categories")).data ?? [];
  const [name,setName] = useState("");
  const [category,setCategory] = useState("");
  const [subcategory,setSubcategory] = useState("");
  const subcategories = useApiData<Record<string,string[]>>(ownerApi.url(path+"/subcategories")).data;
  const [price,setPrice] = useState("");
  const [stock,setStock] = useState("0");
  const [sizes,setSizes] = useState<string[]>([]);
  const [colors,setColors] = useState<string[]>([]);
  const [colorDraft,setColorDraft] = useState("");
  const [extraSize,setExtraSize] = useState("");
  const [removed,setRemoved] = useState<string[]>([]);
  const [overrides,setOverrides] = useState<Record<string,Override>>({});
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);

  const orderedSizes = [...sizes].sort((a,b)=>{
    const ia = ALL_SIZES.indexOf(a), ib = ALL_SIZES.indexOf(b);
    return (ia<0?999:ia) - (ib<0?999:ib);
  });
  const combos = colors.flatMap(color=>orderedSizes.map(size=>({key:color+"|"+size, color, size}))).filter(c=>!removed.includes(c.key));

  function toggleSize(size:string) {
    setSizes(old=>old.includes(size) ? old.filter(s=>s!==size) : [...old,size]);
  }
  function addColor(raw:string) {
    const color = raw.trim().slice(0,30);
    if (!color) return;
    const nice = color.charAt(0).toUpperCase() + color.slice(1);
    setColors(old=>old.some(c=>c.toLowerCase()===nice.toLowerCase()) ? old : [...old,nice]);
    setColorDraft("");
  }
  function edit(key:string, change:Override) {
    setOverrides(old=>({...old,[key]:{...old[key],...change}}));
  }

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!combos.length) {setMessage("Elige al menos una talla y un color."); return;}
    if (combos.length>100) {setMessage("Hasta 100 variantes por prenda."); return;}
    const variants = combos.map(c=>({
      sku:(overrides[c.key]?.sku ?? "").trim(), size:c.size, color:c.color,
      price:overrides[c.key]?.price || price, stock:Number(overrides[c.key]?.stock ?? stock)
    }));
    setBusy(true); setMessage("");
    try {
      const res = await send(path,JSON.stringify({name, category:category.trim() || null,
        subcategory:category.trim() && subcategory.trim() || null, variants}));
      if (!res.ok) {
        setMessage(await errorMessage(res, res.status === 409 ? "Uno de los SKU ya existe en la tienda o se repite en este formulario." :
          res.status === 402 ? "Llegaste al límite de productos de tu plan." : "Revisa los datos de la prenda y sus variantes."));
        return;
      }
      toast.success("Prenda registrada", {description:"Agrega sus fotos con el botón Fotos e imprime su etiqueta QR."});
      onCreated();
    } catch {setMessage("No se pudo confirmar el registro. Consulta el catálogo antes de reintentar.");}
    finally {setBusy(false);}
  }

  return <Dialog open={open} onOpenChange={o=>{onOpenChange(o); setMessage("");}}>
    <DialogContent className="max-h-[92vh] max-w-4xl overflow-y-auto">
      <DialogHeader><DialogTitle>Nueva prenda</DialogTitle>
        <DialogDescription>Elige las tallas y los colores: se crea una variante por combinación, con su SKU, precio y stock.</DialogDescription></DialogHeader>
      <form onSubmit={save}>
        <fieldset disabled={busy} className="grid gap-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Nombre" htmlFor="np-name"><Input id="np-name" value={name} onChange={e=>setName(e.target.value)} required maxLength={150} placeholder="Polo básico algodón"/></Field>
            <Field label="Categoría" htmlFor="np-category" hint={categories.length ? "Elige una existente o crea una nueva." : "Opcional. Agrupa tus prendas en la tienda."}>
              <CategorySelect id="np-category" value={category} onChange={setCategory} categories={categories}/></Field>
            <Field label="Subcategoría" htmlFor="np-subcategory" hint="Opcional. Por ejemplo Retro, Verano o Invierno dentro de Polos. La IA la usa para ofrecer variedad.">
              <SubcategoryInput id="np-subcategory" value={subcategory} onChange={setSubcategory} category={category} options={subcategories}/></Field>
            <Field label="Precio (S/)" htmlFor="np-price" hint="Se aplica a todas las variantes; puedes cambiarlo en cada una.">
              <Input id="np-price" value={price} onChange={e=>setPrice(e.target.value)} type="number" min="0.01" max="99999999.99" step="0.01" required inputMode="decimal"/></Field>
            <Field label="Stock inicial por variante" htmlFor="np-stock">
              <Input id="np-stock" value={stock} onChange={e=>setStock(e.target.value)} type="number" min="0" max="2147483647" step="1" required/></Field>
          </div>

          <div className="grid gap-2">
            <Label>Tallas</Label>
            <div className="flex flex-wrap gap-2" role="group" aria-label="Tallas">
              {SIZE_GROUPS[0].sizes.concat(sizes.filter(s=>!SIZE_GROUPS[0].sizes.includes(s))).map(size=>
                <button key={size} type="button" aria-pressed={sizes.includes(size)} onClick={()=>toggleSize(size)}
                  className={cn("h-9 min-w-11 rounded-lg border px-3 text-sm font-medium transition-colors",
                    sizes.includes(size) ? "border-primary bg-primary text-primary-foreground" : "bg-card hover:border-primary")}>{size}</button>)}
            </div>
            <div className="flex max-w-sm items-start gap-2">
              <div className="flex-1"><SizeSelect key={sizes.join()} id="np-extra-size" value={extraSize} onChange={setExtraSize}/></div>
              <Button type="button" variant="outline" disabled={!extraSize.trim()} onClick={()=>{ if (!sizes.includes(extraSize.trim())) toggleSize(extraSize.trim()); setExtraSize(""); }}>
                <Plus/>Talla</Button>
            </div>
          </div>

          <div className="grid gap-2">
            <Label htmlFor="np-color">Colores</Label>
            {colors.length>0 && <div className="flex flex-wrap gap-2">{colors.map(c=>
              <Badge key={c} variant="outline" className="gap-1 py-1 pl-3 pr-1 text-sm">{c}
                <button type="button" aria-label={"Quitar "+c} className="rounded p-0.5 hover:bg-muted" onClick={()=>setColors(colors.filter(x=>x!==c))}><X className="size-3.5"/></button></Badge>)}</div>}
            <div className="flex max-w-sm gap-2">
              <Input id="np-color" list="np-colors" value={colorDraft} maxLength={30} placeholder="Negro, Azul marino…" onChange={e=>setColorDraft(e.target.value)}
                onKeyDown={e=>{ if (e.key==="Enter") {e.preventDefault(); addColor(colorDraft);} }}/>
              <Button type="button" variant="outline" disabled={!colorDraft.trim()} onClick={()=>addColor(colorDraft)}><Plus/>Color</Button>
            </div>
            <datalist id="np-colors">{COMMON_COLORS.filter(c=>!colors.includes(c)).map(c=><option key={c} value={c}/>)}</datalist>
            <div className="flex flex-wrap gap-1.5">{COMMON_COLORS.filter(c=>!colors.includes(c)).slice(0,8).map(c=>
              <button key={c} type="button" onClick={()=>addColor(c)} className="rounded-full border px-2.5 py-0.5 text-xs text-muted-foreground hover:border-primary hover:text-foreground">+ {c}</button>)}</div>
          </div>

          {combos.length>0 ? <div className="grid gap-2">
            <p className="text-sm font-medium">{combos.length} {combos.length===1 ? "variante" : "variantes"}</p>
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader><TableRow><TableHead>Color / talla</TableHead><TableHead>SKU</TableHead>
                  <TableHead className="w-28">Precio</TableHead><TableHead className="w-24">Stock</TableHead><TableHead className="w-10"><span className="sr-only">Quitar</span></TableHead></TableRow></TableHeader>
                <TableBody>{combos.map((c,i)=><TableRow key={c.key}>
                  <TableCell className="whitespace-nowrap">{c.color} · <strong>{c.size}</strong></TableCell>
                  <TableCell><Input aria-label={"SKU de "+c.color+" "+c.size} className="h-8 min-w-48 font-mono text-xs" maxLength={50}
                    value={overrides[c.key]?.sku ?? ""} placeholder={skuBase(name,c.color,c.size)} onChange={e=>edit(c.key,{sku:e.target.value.toUpperCase()})}/></TableCell>
                  <TableCell><Input aria-label={"Precio de "+c.color+" "+c.size} className="h-8" type="number" min="0.01" step="0.01"
                    value={overrides[c.key]?.price ?? ""} placeholder={price || "0.00"} onChange={e=>edit(c.key,{price:e.target.value})}/></TableCell>
                  <TableCell><Input aria-label={"Stock de "+c.color+" "+c.size} className="h-8" type="number" min="0" step="1"
                    value={overrides[c.key]?.stock ?? ""} placeholder={stock} onChange={e=>edit(c.key,{stock:e.target.value})}/></TableCell>
                  <TableCell><Button type="button" size="sm" variant="ghost" aria-label={"Quitar variante "+(i+1)} onClick={()=>setRemoved([...removed,c.key])}><Trash2/></Button></TableCell>
                </TableRow>)}</TableBody>
              </Table>
            </div>
            <p className="text-xs text-muted-foreground">Si dejas el SKU vacío se usa el sugerido (nombre-color-talla). Si ya existe, se le agrega un número.</p>
          </div> : <Alert>Elige al menos una talla y un color para armar las variantes.</Alert>}

          {message && <Alert variant="destructive">{message}</Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={()=>onOpenChange(false)}>Cancelar</Button>
            <Button type="submit" loading={busy} disabled={!combos.length}>Guardar prenda</Button>
          </DialogFooter>
        </fieldset>
      </form>
    </DialogContent>
  </Dialog>;
}
