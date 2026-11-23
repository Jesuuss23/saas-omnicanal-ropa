import {BadgeCheck, CreditCard, Search, ShoppingBag} from "lucide-react";

const products = [
  {name: "Casaca denim", price: "S/ 189", tone: "from-sky-200 to-indigo-300"},
  {name: "Vestido lino", price: "S/ 149", tone: "from-rose-200 to-orange-200"},
  {name: "Polo básico", price: "S/ 49", tone: "from-emerald-200 to-teal-300"},
  {name: "Jean mom", price: "S/ 129", tone: "from-violet-200 to-fuchsia-200"},
  {name: "Blusa satín", price: "S/ 99", tone: "from-amber-200 to-yellow-200"},
  {name: "Hoodie oversize", price: "S/ 139", tone: "from-stone-200 to-stone-300"},
];

/** Composición ilustrativa del producto (catálogo web, chat de WhatsApp y avisos), hecha con HTML y CSS. */
export function ProductMockup() {
  return <div aria-hidden className="relative mx-auto w-full max-w-xl select-none lg:max-w-none">
    <div className="overflow-hidden rounded-2xl border bg-card shadow-pop">
      <div className="flex items-center gap-2 border-b bg-muted/60 px-4 py-2.5">
        <span className="size-2.5 rounded-full bg-red-300"/><span className="size-2.5 rounded-full bg-amber-300"/><span className="size-2.5 rounded-full bg-emerald-300"/>
        <span className="ml-3 flex-1 truncate rounded-md bg-card px-3 py-1 text-[11px] text-muted-foreground">tutienda.pe/catalogo</span>
      </div>
      <div className="grid gap-3 p-4">
        <div className="flex items-center justify-between"><p className="text-sm font-semibold">Nueva colección</p>
          <span className="flex items-center gap-1.5 rounded-md border px-2 py-1 text-[11px] text-muted-foreground"><Search className="size-3"/>Buscar talla, color…</span></div>
        <div className="grid grid-cols-3 gap-3">
          {products.map(p => <div key={p.name} className="grid gap-1.5">
            <div className={"aspect-[4/5] rounded-lg bg-gradient-to-br " + p.tone}/>
            <p className="truncate text-[11px] font-medium">{p.name}</p>
            <p className="text-[11px] text-muted-foreground">{p.price}</p>
          </div>)}
        </div>
      </div>
    </div>

    <div className="absolute -bottom-10 -left-4 w-56 rounded-[1.75rem] border-[6px] border-stone-900 bg-[#ece5dd] shadow-pop sm:-left-10">
      <div className="flex items-center gap-2 rounded-t-[1.3rem] bg-[#075e54] px-3 py-2.5 text-white">
        <span className="grid size-7 place-items-center rounded-full bg-white/20 text-[10px] font-semibold">LM</span>
        <div><p className="text-[11px] font-semibold leading-tight">Lucía M.</p><p className="text-[9px] text-white/70">en línea</p></div>
      </div>
      <div className="grid gap-1.5 p-2.5 text-[10.5px] leading-snug">
        <p className="max-w-[85%] rounded-lg rounded-tl-none bg-white px-2 py-1.5 shadow-sm">Hola, ¿la casaca denim tienen en talla M?</p>
        <p className="ml-auto max-w-[85%] rounded-lg rounded-tr-none bg-[#dcf8c6] px-2 py-1.5 shadow-sm">¡Sí! Quedan 3 en M 🙌 Te dejo el enlace para separarla:</p>
        <p className="ml-auto max-w-[85%] rounded-lg rounded-tr-none bg-[#dcf8c6] px-2 py-1.5 text-[#0f766e] underline shadow-sm">tutienda.pe/casaca-denim</p>
        <p className="max-w-[85%] rounded-lg rounded-tl-none bg-white px-2 py-1.5 shadow-sm">Listo, ya pagué 💳</p>
      </div>
    </div>

    <div className="absolute -right-3 top-10 grid gap-2 sm:-right-8">
      <Toast icon={<CreditCard className="size-4"/>} title="Pago aprobado" text="S/ 189.00 · Mercado Pago"/>
      <Toast icon={<BadgeCheck className="size-4"/>} title="Boleta B001-245" text="Aceptada por SUNAT"/>
      <Toast icon={<ShoppingBag className="size-4"/>} title="Stock actualizado" text="Casaca denim M · quedan 2"/>
    </div>
  </div>;
}

function Toast({icon, title, text}: {icon: React.ReactNode; title: string; text: string}) {
  return <div className="flex w-52 items-center gap-2.5 rounded-xl border bg-card/95 p-2.5 shadow-pop backdrop-blur">
    <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-accent text-accent-foreground">{icon}</span>
    <div className="min-w-0"><p className="truncate text-xs font-semibold">{title}</p><p className="truncate text-[11px] text-muted-foreground">{text}</p></div>
  </div>;
}
