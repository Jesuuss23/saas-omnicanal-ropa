"use client";
import {useState} from "react";
import {Input, NativeSelect} from "@/components/ui/input";

/** Tallas más usadas en tiendas de ropa del Perú. "Otra" deja escribir una a mano. */
export const SIZE_GROUPS: {label:string; sizes:string[]}[] = [
  {label:"Letras", sizes:["XXS","XS","S","M","L","XL","XXL","XXXL"]},
  {label:"Jeans y pantalones", sizes:["26","28","30","32","34","36","38","40","42","44"]},
  {label:"Niños", sizes:["2","4","6","8","10","12","14","16"]},
  {label:"Otras", sizes:["Única","Estándar"]},
];
export const ALL_SIZES = SIZE_GROUPS.flatMap(g=>g.sizes);
export const COMMON_COLORS = ["Negro","Blanco","Gris","Azul","Celeste","Rojo","Rosado","Verde","Beige","Marrón","Amarillo","Morado","Naranja","Jean"];

/** Igual que ProductCatalogService.skuBase en el backend: NOMBRE-COLOR-TALLA sin tildes. */
export function skuBase(name:string, color:string, size:string) {
  const slug = (t:string) => t.normalize("NFD").replace(/\p{M}/gu,"").toUpperCase().replace(/[^A-Z0-9]+/g,"-").replace(/^-|-$/g,"");
  let n = slug(name); const c = slug(color), z = slug(size);
  const room = Math.max(50 - 6 - (c ? c.length+1 : 0) - (z ? z.length+1 : 0), 8);
  if (n.length > room) n = n.slice(0, room).replace(/-+$/,"");
  let sku = [n,c,z].filter(Boolean).join("-");
  if (sku.length > 44) sku = sku.slice(0,44).replace(/-+$/,"");
  return sku || "PRODUCTO";
}

const OTHER = "__other__";

/** Select de talla con las tallas comunes; si la talla no está en la lista, se escribe a mano. */
export function SizeSelect({id, value, onChange, required}:{id:string; value:string; onChange:(v:string)=>void; required?:boolean}) {
  const [custom,setCustom] = useState(value !== "" && !ALL_SIZES.includes(value));
  return <div className="grid gap-2">
    <NativeSelect id={id} value={custom ? OTHER : value} required={required && !custom}
      onChange={e=>{ if (e.target.value === OTHER) {setCustom(true); onChange("");} else {setCustom(false); onChange(e.target.value);} }}>
      <option value="">Elige la talla</option>
      {SIZE_GROUPS.map(g=><optgroup key={g.label} label={g.label}>{g.sizes.map(s=><option key={s} value={s}>{s}</option>)}</optgroup>)}
      <option value={OTHER}>Otra talla…</option>
    </NativeSelect>
    {custom && <Input aria-label="Talla personalizada" value={value} onChange={e=>onChange(e.target.value)} maxLength={10} required={required} placeholder="Ej. 3XL, 38/40"/>}
  </div>;
}

/** Select de categoría con las que ya existen en la tienda, o una nueva escrita a mano. */
export function CategorySelect({id, value, onChange, categories}:{id:string; value:string; onChange:(v:string)=>void; categories:string[]}) {
  const [custom,setCustom] = useState(value !== "" && !categories.includes(value));
  const showInput = custom || !categories.length;
  return <div className="grid gap-2">
    {categories.length > 0 && <NativeSelect id={id} value={custom ? OTHER : value}
      onChange={e=>{ if (e.target.value === OTHER) {setCustom(true); onChange("");} else {setCustom(false); onChange(e.target.value);} }}>
      <option value="">Sin categoría</option>
      {categories.map(c=><option key={c} value={c}>{c}</option>)}
      <option value={OTHER}>+ Nueva categoría…</option>
    </NativeSelect>}
    {showInput && <Input id={categories.length ? undefined : id} aria-label={categories.length ? "Nombre de la nueva categoría" : undefined}
      value={value} onChange={e=>onChange(e.target.value)} maxLength={50} placeholder="Polos, Jeans, Vestidos…"/>}
  </div>;
}

/** Subcategoría (estilo dentro de la categoría, por ejemplo "Retro" en Polos). Sugiere las que ya existen en esa categoría. */
export function SubcategoryInput({id, value, onChange, category, options}:{id:string; value:string; onChange:(v:string)=>void; category:string;
  options:Record<string,string[]>|null|undefined}) {
  const key = Object.keys(options ?? {}).find(k=>k.toLowerCase()===category.trim().toLowerCase());
  const suggestions = key && options ? options[key] : [];
  return <>
    <Input id={id} list={id+"-list"} value={value} onChange={e=>onChange(e.target.value)} maxLength={50}
      disabled={!category.trim()} placeholder={category.trim() ? "Retro, Verano, Invierno…" : "Elige primero una categoría"}/>
    <datalist id={id+"-list"}>{suggestions.map(s=><option key={s} value={s}/>)}</datalist>
  </>;
}
