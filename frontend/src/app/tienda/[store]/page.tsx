import Link from "next/link";
import {notFound} from "next/navigation";

import CatalogCart, {type Variant} from "./CatalogCart";
type Catalog = {storeName:string; variants:Variant[]; hasNext:boolean};
type Props = {
  params:Promise<{store:string}>;
  searchParams:Promise<{q?:string|string[]; page?:string|string[]}>;
};

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
    <CatalogCart key={store} store={store} variants={catalog.variants} query={query}/>
    <nav aria-label="Páginas del catálogo" className="mt-6 flex gap-4 text-teal-800 underline">
      {page>0 && <Link href={pageLink(page-1)}>Anterior</Link>}
      {catalog.hasNext && <Link href={pageLink(page+1)}>Siguiente</Link>}
    </nav>
  </main>;
}
