"use client";
import {errorMessage, ownerApi, type Send} from "@/lib/api";

import {useState, type FormEvent} from "react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {useApiData} from "@/lib/useApiData";
import {discountPercent, marginPercent, type StockStatus} from "@/lib/pricing";
import {DescriptionField} from "./DescriptionField";
import {CategorySelect, COMMON_COLORS, SizeSelect, skuBase, SubcategoryInput} from "./catalogOptions";

export type InventoryRow = {
  id:string; name:string; category:string|null; sku:string; size:string; color:string|null;
  price:number; available:number; physical:number; reserved:number; productVersion:string; variantVersion:string;
  productId:string; coverImage:string|null; subcategory?:string|null;
  compareAtPrice?:number|null; cost?:number|null; active?:boolean; description?:string|null; material?:string|null;
  status?:StockStatus; discountPercent?:number|null; marginPercent?:number|null;
};
type Props = {row:InventoryRow; store:string; path:string; send:Send; close:()=>void; reload:()=>void;
  siblings?:{size:string; color:string|null}[]};

export default function EditVariant({row,store,path,send,close,reload,siblings=[]}:Props) {
  const categories = useApiData<string[]>(ownerApi.url(path+"/categories")).data;
  const [name,setName] = useState(row.name);
  const [category,setCategory] = useState(row.category ?? "");
  const [subcategory,setSubcategory] = useState(row.subcategory ?? "");
  const subcategories = useApiData<Record<string,string[]>>(ownerApi.url(path+"/subcategories")).data;
  const [sku,setSku] = useState(row.sku);
  const [size,setSize] = useState(row.size);
  const [color,setColor] = useState(row.color ?? "");
  const [price,setPrice] = useState(String(row.price));
  const [stock,setStock] = useState(String(row.physical));
  const [compareAt,setCompareAt] = useState(row.compareAtPrice != null ? String(row.compareAtPrice) : "");
  const [cost,setCost] = useState(row.cost != null ? String(row.cost) : "");
  const [material,setMaterial] = useState(row.material ?? "");
  const [active,setActive] = useState(row.active ?? true);
  const [description,setDescription] = useState(row.description ?? "");
  const [busy,setBusy] = useState(false);
  const [stale,setStale] = useState(false);
  const [error,setError] = useState("");

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || stale) return;
    setBusy(true); setError("");
    try {
      const res = await send(path + "/variants/" + row.id, JSON.stringify({
        name, category:category.trim() || null, subcategory:category.trim() && subcategory.trim() || null, productVersion:row.productVersion, variantVersion:row.variantVersion,
        description:description.trim() || null, material:material.trim() || null, active,
        variant:{sku, size, color, price, stock:Number(stock), compareAtPrice:compareAt ? compareAt : null, cost:cost ? cost : null}
      }), "PUT");
      if (res.ok) {reload(); return;}
      if (res.status === 412) {
        setStale(true);
        setError("El registro cambió o hay más unidades reservadas. Recarga el inventario antes de editar.");
      } else setError(await errorMessage(res, res.status === 409 ? "Ese SKU ya pertenece a otra variante." : "No se guardaron los cambios. Revisa los datos y tu sesión."));
    } catch {setError("No se pudo confirmar el guardado. Recarga el inventario para comprobarlo.");}
    finally {setBusy(false);}
  }

  const suggested = skuBase(name, color, size);
  const priceNumber = Number(price);
  const discount = discountPercent(priceNumber, compareAt ? Number(compareAt) : null);
  const margin = marginPercent(priceNumber, cost ? Number(cost) : null);
  const facts = {name, category, subcategory, material,
    colors:[color, ...siblings.map(s=>s.color ?? "")], sizes:[size, ...siblings.map(s=>s.size)]};
  return <Dialog open onOpenChange={open=>{ if (!open && !busy) (stale ? reload : close)(); }}>
    <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
      <DialogHeader><DialogTitle>Editar {row.sku}</DialogTitle>
        <DialogDescription>Nombre, categoría, material, descripción y visibilidad se aplican a todas las variantes de esta prenda.</DialogDescription></DialogHeader>
      <form onSubmit={save}>
        <fieldset disabled={busy || stale} className="grid gap-4 sm:grid-cols-2">
          <label className="flex items-start gap-3 rounded-lg border p-3 sm:col-span-2">
            <input type="checkbox" checked={active} onChange={e=>setActive(e.target.checked)} className="mt-1 size-4 accent-[var(--primary)]"/>
            <span className="grid gap-0.5"><span className="text-sm font-medium">Visible en la tienda y para la IA</span>
              <span className="text-xs text-muted-foreground">Si la ocultas, no sale en tu tienda web, el PDF ni las respuestas de WhatsApp. En caja se sigue vendiendo.</span></span>
          </label>
          <Field label="Nombre" htmlFor="ev-name"><Input id="ev-name" value={name} onChange={e=>setName(e.target.value)} maxLength={150} required/></Field>
          <Field label="Categoría" htmlFor="ev-category">
            {categories ? <CategorySelect id="ev-category" value={category} onChange={setCategory} categories={categories}/>
              : <Input id="ev-category" value={category} onChange={e=>setCategory(e.target.value)} maxLength={50}/>}</Field>
          <Field label="Subcategoría" htmlFor="ev-subcategory" className="sm:col-span-2"
            hint="Opcional. El estilo dentro de la categoría; la IA lo usa para ofrecer variedad (por ejemplo polos retro o de verano).">
            <SubcategoryInput id="ev-subcategory" value={subcategory} onChange={setSubcategory} category={category} options={subcategories}/></Field>
          <Field label="Material" htmlFor="ev-material" hint="Opcional. Por ejemplo Algodón, Denim o Lino.">
            <Input id="ev-material" value={material} onChange={e=>setMaterial(e.target.value)} maxLength={80}/></Field>
          <div className="sm:col-span-2"><DescriptionField id="ev-description" store={store} value={description} onChange={setDescription} facts={facts}/></div>
          <Field label="Talla" htmlFor="ev-size"><SizeSelect id="ev-size" value={size} onChange={setSize} required/></Field>
          <Field label="Color" htmlFor="ev-color"><Input id="ev-color" list="ev-colors" value={color} onChange={e=>setColor(e.target.value)} maxLength={30} required/>
            <datalist id="ev-colors">{COMMON_COLORS.map(c=><option key={c} value={c}/>)}</datalist></Field>
          <Field label="SKU" htmlFor="ev-sku" className="sm:col-span-2"
            hint={sku !== suggested ? <button type="button" className="text-primary underline" onClick={()=>setSku(suggested)}>Usar {suggested}</button> : "Coincide con nombre, color y talla."}>
            <Input id="ev-sku" value={sku} onChange={e=>setSku(e.target.value.toUpperCase())} maxLength={50} required className="font-mono"/></Field>
          <Field label="Precio (S/)" htmlFor="ev-price"><Input id="ev-price" type="number" value={price} onChange={e=>setPrice(e.target.value)} min="0.01" max="99999999.99" step="0.01" required/></Field>
          <Field label="Precio antes (S/)" htmlFor="ev-compare" hint={discount != null ? `Se muestra tachado: −${discount}%` : "Opcional. Mayor que el precio actual para mostrar la oferta."}>
            <Input id="ev-compare" type="number" value={compareAt} onChange={e=>setCompareAt(e.target.value)} min="0.01" max="99999999.99" step="0.01"/></Field>
          <Field label="Costo (S/)" htmlFor="ev-cost" hint={margin != null ? `Margen ${margin}%. Solo lo ves tú.` : "Opcional. Solo lo ves tú; sirve para calcular el margen."}>
            <Input id="ev-cost" type="number" value={cost} onChange={e=>setCost(e.target.value)} min="0" max="99999999.99" step="0.01"/></Field>
          <Field label="Stock físico" htmlFor="ev-stock" hint={row.reserved>0 ? `${row.reserved} reservadas en pedidos web: no puede bajar de ahí.` : undefined}>
            <Input id="ev-stock" type="number" value={stock} onChange={e=>setStock(e.target.value)} min={Math.max(0,row.reserved)} max="2147483647" required/></Field>
          {error && <Alert variant="destructive" className="sm:col-span-2">{error}</Alert>}
        </fieldset>
        <DialogFooter className="mt-4">
          <Button type="button" variant="outline" disabled={busy} onClick={stale ? reload : close}>{stale ? "Recargar inventario" : "Cancelar"}</Button>
          {!stale && <Button type="submit" loading={busy}>Guardar cambios</Button>}
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>;
}
