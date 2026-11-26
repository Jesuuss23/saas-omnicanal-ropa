"use client";
import {apiFetch} from "@/lib/api";
import {discountPercent} from "@/lib/pricing";

import {useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent} from "react";
import {ChevronLeft, ChevronRight, ImageOff, Plus, Search, ZoomIn} from "lucide-react";
import {Dialog, DialogContent, DialogDescription, DialogTitle} from "@/components/ui/dialog";
import {Skeleton} from "@/components/ui/skeleton";
import type {Variant} from "./CatalogCart";

type Option = {id:string; size:string; color:string|null; price:number; compareAtPrice:number|null; discountPercent:number|null; available:number};
export type ProductInfo = {id:string; name:string; category:string|null; subcategory:string|null; description:string|null;
  material:string|null; images:string[]; variants:Option[]; imageUrls?:(string|null)[]};

const currency = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const ZOOM = 2.5;

/**
 * Foto con lupa: en escritorio la imagen se amplía siguiendo el cursor; en móvil un toque activa el zoom y se
 * arrastra con el dedo para recorrerla (otro toque lo apaga).
 */
export function ZoomImage({src, alt}:{src:string; alt:string}) {
  const box = useRef<HTMLDivElement>(null);
  const [origin,setOrigin] = useState("50% 50%");
  const [zoomed,setZoomed] = useState(false);
  const touch = useRef<{x:number; y:number; moved:boolean}|null>(null);

  function place(clientX:number, clientY:number) {
    const rect = box.current?.getBoundingClientRect();
    if (!rect) return;
    const x = Math.min(100, Math.max(0, ((clientX - rect.left) / rect.width) * 100));
    const y = Math.min(100, Math.max(0, ((clientY - rect.top) / rect.height) * 100));
    setOrigin(x.toFixed(1) + "% " + y.toFixed(1) + "%");
  }

  function down(e:ReactPointerEvent) {
    if (e.pointerType === "mouse") return;
    touch.current = {x:e.clientX, y:e.clientY, moved:false};
    if (zoomed) e.currentTarget.setPointerCapture(e.pointerId);
  }
  function move(e:ReactPointerEvent) {
    if (e.pointerType === "mouse") { place(e.clientX, e.clientY); return; }
    const t = touch.current;
    if (!t) return;
    if (Math.abs(e.clientX - t.x) + Math.abs(e.clientY - t.y) > 6) t.moved = true;
    if (zoomed) place(e.clientX, e.clientY);
  }
  function up(e:ReactPointerEvent) {
    if (e.pointerType === "mouse") return;
    const t = touch.current;
    touch.current = null;
    if (t && !t.moved) { place(e.clientX, e.clientY); setZoomed(z => !z); }
  }

  return <div ref={box} role="img" aria-label={alt + (zoomed ? " (ampliada)" : "")}
    className={"relative aspect-[4/5] w-full overflow-hidden rounded-2xl bg-muted select-none " + (zoomed ? "cursor-zoom-out touch-none" : "cursor-zoom-in touch-pan-y")}
    onPointerEnter={e => { if (e.pointerType === "mouse") { place(e.clientX, e.clientY); setZoomed(true); } }}
    onPointerLeave={e => { if (e.pointerType === "mouse") setZoomed(false); }}
    onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={() => { touch.current = null; }}>
    {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
    <img src={src} alt="" draggable={false} className="size-full object-cover transition-transform duration-150 ease-out"
      style={{transformOrigin:origin, transform:zoomed ? `scale(${ZOOM})` : "scale(1)"}}/>
    {!zoomed && <span className="pointer-events-none absolute right-3 bottom-3 inline-flex items-center gap-1 rounded-full bg-card/90 px-2.5 py-1 text-xs font-medium shadow-sm">
      <ZoomIn aria-hidden className="size-3.5"/><span className="hidden sm:inline">Pasa el cursor para ampliar</span><span className="sm:hidden">Toca para ampliar</span></span>}
  </div>;
}

/** Ficha de la prenda: galería con lupa, descripción, color y talla, precio y stock. */
export default function ProductDetail({store, product, initialVariant, open, onOpenChange, onAdd, disabled, theme}:{
  store:string; product:{id:string; name:string; category:string|null}; initialVariant?:string; open:boolean;
  onOpenChange:(open:boolean)=>void; onAdd:(item:Variant)=>void; disabled?:boolean; theme?:CSSProperties;
}) {
  const [info,setInfo] = useState<ProductInfo|null>(null);
  const [failed,setFailed] = useState(false);
  const [image,setImage] = useState(0);
  const [selected,setSelected] = useState<string|undefined>(initialVariant);

  useEffect(() => {
    let alive = true;
    apiFetch("/api/public/stores/" + store + "/products/" + product.id, {cache:"no-store"})
      .then(async res => {
        if (!res.ok) throw new Error();
        const data = await res.json() as ProductInfo;
        if (alive) setInfo(data);
      })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [store, product.id]);

  const variants = info?.variants ?? [];
  const current = variants.find(v => v.id === selected) ?? variants.find(v => v.available > 0) ?? variants[0];
  const colors = [...new Set(variants.map(v => v.color).filter((c):c is string => Boolean(c)))];
  const sizes = [...new Set(variants.filter(v => !current?.color || v.color === current.color).map(v => v.size))];
  const images = info?.images ?? [];
  const shown = Math.min(image, Math.max(0, images.length - 1));
  const photo = images[shown];
  // Cloudinary si la foto ya está allá; si no, el backend (que también redirige a Cloudinary).
  const src = (id:string, i:number) => info?.imageUrls?.[i] ?? "/api/public/stores/" + store + "/images/" + id;
  const discount = current ? current.discountPercent ?? discountPercent(current.price, current.compareAtPrice) : null;

  function pick(color:string|null, size:string) {
    const match = variants.find(v => v.color === color && v.size === size && v.available > 0)
      ?? variants.find(v => v.color === color && v.size === size)
      ?? variants.find(v => v.color === color && v.available > 0) ?? variants.find(v => v.color === color);
    if (match) setSelected(match.id);
  }

  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent style={theme} className="max-h-[94dvh] w-[calc(100%-1rem)] max-w-5xl gap-0 p-0 sm:w-[calc(100%-2rem)]">
      <div className="grid md:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <div className="grid gap-3 p-4 sm:p-6">
          {!info && !failed ? <Skeleton className="aspect-[4/5] w-full rounded-2xl"/>
            : photo ? <div className="relative">
              <ZoomImage key={photo} src={src(photo, shown)}
                alt={product.name + (images.length > 1 ? (image === 0 ? " · vista delantera" : image === 1 ? " · vista trasera" : " · foto " + (image + 1)) : "")}/>
              {images.length > 1 && <>
                <button type="button" aria-label="Foto anterior" onClick={() => setImage((image - 1 + images.length) % images.length)}
                  className="absolute top-1/2 left-2 grid size-9 -translate-y-1/2 place-items-center rounded-full bg-card/90 shadow-sm hover:bg-card"><ChevronLeft className="size-5"/></button>
                <button type="button" aria-label="Foto siguiente" onClick={() => setImage((image + 1) % images.length)}
                  className="absolute top-1/2 right-2 grid size-9 -translate-y-1/2 place-items-center rounded-full bg-card/90 shadow-sm hover:bg-card"><ChevronRight className="size-5"/></button>
              </>}
              {discount != null && <span className="absolute top-3 left-3 rounded-full bg-destructive px-2.5 py-1 text-xs font-semibold text-white shadow-sm">−{discount}%</span>}
            </div>
            : <div className="grid aspect-[4/5] w-full place-items-center rounded-2xl bg-muted text-muted-foreground"><ImageOff aria-hidden className="size-10"/></div>}
          {images.length > 1 && <ul className="flex gap-2 overflow-x-auto" aria-label="Fotos">
            {images.map((id, i) => <li key={id}>
              <button type="button" aria-label={i === 0 ? "Vista delantera" : i === 1 ? "Vista trasera" : "Foto " + (i + 1)} aria-pressed={i === image}
                onClick={() => setImage(i)}
                className={"block size-16 overflow-hidden rounded-lg border-2 " + (i === image ? "border-[var(--store)]" : "border-transparent opacity-70 hover:opacity-100")}>
                {/* eslint-disable-next-line @next/next/no-img-element -- miniatura servida por el backend */}
                <img src={src(id, i)} alt="" className="size-full object-cover"/>
              </button>
            </li>)}
          </ul>}
        </div>

        <div className="grid content-start gap-5 border-t border-border p-4 sm:p-6 md:border-t-0 md:border-l">
          <div className="grid gap-1 pr-8">
            <p className="text-xs tracking-wide text-muted-foreground uppercase">
              {[info?.category ?? product.category, info?.subcategory].filter(Boolean).join(" · ") || "Prenda"}</p>
            <DialogTitle className="text-2xl leading-tight font-semibold">{product.name}</DialogTitle>
            {current && <p className="flex flex-wrap items-baseline gap-2">
              <span className="text-2xl font-semibold text-[var(--store)]">{currency.format(current.price)}</span>
              {discount != null && current.compareAtPrice != null && <>
                <s className="text-muted-foreground">{currency.format(current.compareAtPrice)}</s>
                <span className="rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-semibold text-destructive">Ahorras {discount}%</span></>}
            </p>}
          </div>

          {failed && <p role="alert" className="text-sm text-destructive">No se pudo cargar la ficha. Cierra y vuelve a intentarlo.</p>}
          {!info && !failed && <div className="grid gap-2"><Skeleton className="h-4 w-3/4"/><Skeleton className="h-4 w-2/3"/><Skeleton className="h-9 w-1/2"/></div>}

          <DialogDescription className={"text-sm leading-relaxed whitespace-pre-line text-foreground/80" + (info ? "" : " sr-only")}>
            {info ? info.description || "Elige el color y la talla para ver el stock disponible." : "Cargando la ficha de la prenda."}</DialogDescription>
          {info && <>
            {info.material && <p className="text-sm"><span className="text-muted-foreground">Material:</span> {info.material}</p>}

            {colors.length > 0 && <fieldset className="grid gap-2">
              <legend className="mb-2 text-sm font-medium">Color: <span className="font-normal text-muted-foreground">{current?.color}</span></legend>
              <div className="flex flex-wrap gap-2">{colors.map(color => {
                const any = variants.some(v => v.color === color && v.available > 0);
                return <button key={color} type="button" aria-pressed={color === current?.color} onClick={() => pick(color, current?.size ?? "")}
                  className={"h-9 rounded-full border px-3.5 text-sm " + (color === current?.color ? "border-foreground font-medium" : "border-border hover:border-foreground/40") + (any ? "" : " text-muted-foreground line-through")}>{color}</button>;
              })}</div>
            </fieldset>}

            <fieldset className="grid gap-2">
              <legend className="mb-2 text-sm font-medium">Talla: <span className="font-normal text-muted-foreground">{current?.size}</span></legend>
              <div className="flex flex-wrap gap-2">{sizes.map(size => {
                const option = variants.find(v => v.size === size && (!current?.color || v.color === current.color));
                const soldOut = !option || option.available <= 0;
                return <button key={size} type="button" aria-pressed={size === current?.size} onClick={() => pick(current?.color ?? null, size)}
                  aria-label={"Talla " + size + (soldOut ? ", agotada" : "")}
                  className={"h-10 min-w-11 rounded-lg border px-3 text-sm font-medium " + (size === current?.size
                    ? "border-[var(--store)] bg-[var(--store)] text-[var(--store-fg)]" : "border-border bg-card hover:border-foreground/40")
                    + (soldOut ? " opacity-50 line-through" : "")}>{size}</button>;
              })}</div>
            </fieldset>

            {current && <p className="text-sm" role="status">
              {current.available <= 0 ? <span className="font-medium text-destructive">Agotado en esta talla y color</span>
                : current.available <= 3 ? <span className="font-medium text-warning-foreground">{current.available === 1 ? "¡Última unidad!" : "Últimas " + current.available + " unidades"}</span>
                : <span className="text-muted-foreground">{current.available} unidades disponibles</span>}</p>}

            <button type="button" disabled={disabled || !current || current.available <= 0}
              onClick={() => current && onAdd({id:current.id, productId:info.id, name:info.name, category:info.category, size:current.size,
                color:current.color, price:current.price, available:current.available, coverImage:images[0] ?? null})}
              className="inline-flex h-12 w-full items-center justify-center gap-2 rounded-full bg-[var(--store)] px-5 font-semibold text-[var(--store-fg)] disabled:opacity-50">
              <Plus aria-hidden className="size-5"/>{current && current.available <= 0 ? "Agotado" : "Agregar al carrito"}</button>
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Search aria-hidden className="size-3.5"/>El stock se confirma al reservar tu carrito.</p>
          </>}
        </div>
      </div>
    </DialogContent>
  </Dialog>;
}
