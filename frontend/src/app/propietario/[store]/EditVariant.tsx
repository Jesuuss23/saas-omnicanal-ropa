"use client";
import {errorMessage, ownerApi, type Send} from "@/lib/api";

import {useState, type FormEvent} from "react";
import {Alert} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {useApiData} from "@/lib/useApiData";
import {CategorySelect, COMMON_COLORS, SizeSelect, skuBase, SubcategoryInput} from "./catalogOptions";

export type InventoryRow = {
  id:string; name:string; category:string|null; sku:string; size:string; color:string|null;
  price:number; available:number; physical:number; reserved:number; productVersion:string; variantVersion:string;
  productId:string; coverImage:string|null; subcategory?:string|null;
};
type Props = {row:InventoryRow; path:string; send:Send; close:()=>void; reload:()=>void};

export default function EditVariant({row,path,send,close,reload}:Props) {
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
        variant:{sku, size, color, price, stock:Number(stock)}
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
  return <Dialog open onOpenChange={open=>{ if (!open && !busy) (stale ? reload : close)(); }}>
    <DialogContent className="max-w-2xl">
      <DialogHeader><DialogTitle>Editar {row.sku}</DialogTitle>
        <DialogDescription>El nombre, la categoría y la subcategoría se aplican a todas las variantes de esta prenda.</DialogDescription></DialogHeader>
      <form onSubmit={save}>
        <fieldset disabled={busy || stale} className="grid gap-4 sm:grid-cols-2">
          <Field label="Nombre" htmlFor="ev-name"><Input id="ev-name" value={name} onChange={e=>setName(e.target.value)} maxLength={150} required/></Field>
          <Field label="Categoría" htmlFor="ev-category">
            {categories ? <CategorySelect id="ev-category" value={category} onChange={setCategory} categories={categories}/>
              : <Input id="ev-category" value={category} onChange={e=>setCategory(e.target.value)} maxLength={50}/>}</Field>
          <Field label="Subcategoría" htmlFor="ev-subcategory" className="sm:col-span-2"
            hint="Opcional. El estilo dentro de la categoría; la IA lo usa para ofrecer variedad (por ejemplo polos retro o de verano).">
            <SubcategoryInput id="ev-subcategory" value={subcategory} onChange={setSubcategory} category={category} options={subcategories}/></Field>
          <Field label="Talla" htmlFor="ev-size"><SizeSelect id="ev-size" value={size} onChange={setSize} required/></Field>
          <Field label="Color" htmlFor="ev-color"><Input id="ev-color" list="ev-colors" value={color} onChange={e=>setColor(e.target.value)} maxLength={30} required/>
            <datalist id="ev-colors">{COMMON_COLORS.map(c=><option key={c} value={c}/>)}</datalist></Field>
          <Field label="SKU" htmlFor="ev-sku" className="sm:col-span-2"
            hint={sku !== suggested ? <button type="button" className="text-primary underline" onClick={()=>setSku(suggested)}>Usar {suggested}</button> : "Coincide con nombre, color y talla."}>
            <Input id="ev-sku" value={sku} onChange={e=>setSku(e.target.value.toUpperCase())} maxLength={50} required className="font-mono"/></Field>
          <Field label="Precio (S/)" htmlFor="ev-price"><Input id="ev-price" type="number" value={price} onChange={e=>setPrice(e.target.value)} min="0.01" max="99999999.99" step="0.01" required/></Field>
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
