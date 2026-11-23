"use client";
import type {Send} from "@/lib/api";

import {useState, type FormEvent} from "react";

export type InventoryRow = {
  id:string; name:string; category:string|null; sku:string; size:string; color:string|null;
  price:number; available:number; physical:number; reserved:number; productVersion:string; variantVersion:string;
};
type Props = {row:InventoryRow; path:string; send:Send; close:()=>void; reload:()=>void};
const input = "mt-1 block w-full rounded-lg border border-stone-300 bg-white px-3 py-2";

export default function EditVariant({row,path,send,close,reload}:Props) {
  const [busy,setBusy] = useState(false);
  const [stale,setStale] = useState(false);
  const [error,setError] = useState("");
  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || stale) return;
    const fields = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      const res = await send(path + "/variants/" + row.id, JSON.stringify({
        name:fields.get("name"), category:fields.get("category"),
        productVersion:row.productVersion, variantVersion:row.variantVersion,
        variant:{sku:fields.get("sku"), size:fields.get("size"), color:fields.get("color"),
          price:fields.get("price"), stock:Number(fields.get("stock"))}
      }), "PUT");
      if (res.ok) {reload(); return;}
      if (res.status === 412) {
        setStale(true);
        setError("El registro cambió o hay más unidades reservadas. Recarga el inventario antes de editar.");
      } else if (res.status === 409) setError("Ese SKU ya pertenece a otra variante.");
      else setError("No se guardaron los cambios. Revisa los datos y tu sesión.");
    } catch {setError("No se pudo confirmar el guardado. Recarga el inventario para comprobarlo.");}
    finally {setBusy(false);}
  }

  return <form onSubmit={save} className="my-4 rounded-xl border border-teal-200 bg-teal-50 p-5">
    <fieldset disabled={busy || stale} className="grid gap-3 sm:grid-cols-2">
      <legend className="mb-3 text-lg font-semibold">Editar {row.sku}</legend>
      <p className="sm:col-span-2 text-sm">El nombre y la categoría se aplican a todas las variantes de esta prenda.</p>
      <label>Nombre<input name="name" defaultValue={row.name} maxLength={150} required className={input}/></label>
      <label>Categoría<input name="category" defaultValue={row.category ?? ""} maxLength={50} className={input}/></label>
      <label>SKU<input name="sku" defaultValue={row.sku} maxLength={50} required className={input}/></label>
      <label>Talla<input name="size" defaultValue={row.size} maxLength={10} required className={input}/></label>
      <label>Color<input name="color" defaultValue={row.color ?? ""} maxLength={30} required className={input}/></label>
      <label>Precio (S/)<input name="price" type="number" defaultValue={row.price} min="0.01" max="99999999.99" step="0.01" required className={input}/></label>
      <label>Stock físico<input name="stock" type="number" defaultValue={row.physical} min={Math.max(0,row.reserved)} max="2147483647" required className={input}/></label>
      <p className="self-end py-2 text-sm">Unidades reservadas: {row.reserved}</p>
      <button className="rounded-lg bg-teal-800 px-4 py-2 text-white">{busy ? "Guardando…" : "Guardar cambios"}</button>
    </fieldset>
    <button type="button" disabled={busy} onClick={stale ? reload : close} className="mt-3 text-teal-800 underline">{stale ? "Recargar inventario" : "Cancelar"}</button>
    <p role="status" className="mt-2 text-sm">{error}</p>
  </form>;
}
