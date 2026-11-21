import Link from "next/link";
import {notFound} from "next/navigation";

type Variant = {id:string; name:string; category:string|null; size:string; color:string|null; price:number};
type Catalog = {storeName:string; variants:Variant[]; hasNext:boolean};
type Props = {
  params:Promise<{store:string}>;
  searchParams:Promise<{q?:string|string[]; page?:string|string[]}>;
};
const currency = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

export default async function StoreCatalog({params,searchParams}:Props) {
  const {store} = await params;
  const filters = await searchParams;
  if (!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(store)) notFound();
  const query = typeof filters.q==="string" ? filters.q.trim().slice(0,150) : "";
  const page = Number(filters.page ?? 0);
  if (!Number.isInteger(page) || page<0 || page>2147483647) notFound();
  const path = "/tienda/"+store;
  const base = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/,"");
  const response = await fetch(base+"/public/stores/"+store+"/catalog?"+new URLSearchParams({query,page:String(page)}),{cache:"no-store"});
  if (response.status===404) notFound();
  if (!response.ok) throw new Error("No se pudo cargar el catálogo.");
  const catalog:Catalog = await response.json();
  const pageLink = (n:number)=>path+"?"+new URLSearchParams({q:query,page:String(n)});

  return <main className="mx-auto max-w-5xl px-5 py-12 text-stone-900">
    <header className="border-b border-stone-300 pb-6">
      <p className="text-sm font-medium text-teal-800">Catálogo de prendas</p>
      <h1 className="mt-1 text-4xl font-semibold">{catalog.storeName}</h1>
    </header>
    <form action={path} method="get" className="my-6 flex flex-wrap items-end gap-3">
      <label className="grid flex-1 gap-1">Buscar por prenda, categoría, talla o color
        <input type="search" name="q" defaultValue={query} maxLength={150} className="rounded border p-2"/>
      </label>
      <button className="rounded bg-teal-800 px-4 py-2 text-white">Buscar</button>
      {query && <Link href={path} className="p-2 underline">Limpiar</Link>}
    </form>
    {catalog.variants.length ? <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {catalog.variants.map(item=><li key={item.id} className="rounded-xl border border-stone-200 p-5">
        {item.category && <p className="text-sm text-stone-600">{item.category}</p>}
        <h2 className="text-xl font-semibold">{item.name}</h2>
        <p className="my-2">Talla {item.size}{item.color && " · "+item.color}</p>
        <p className="font-semibold">{currency.format(item.price)}</p>
      </li>)}
    </ul> : <p>{query?"No hay prendas disponibles para esta búsqueda.":"No hay prendas disponibles en esta página."}</p>}
    <nav aria-label="Páginas del catálogo" className="mt-6 flex gap-4 text-teal-800 underline">
      {page>0 && <Link href={pageLink(page-1)}>Anterior</Link>}
      {catalog.hasNext && <Link href={pageLink(page+1)}>Siguiente</Link>}
    </nav>
  </main>;
}
