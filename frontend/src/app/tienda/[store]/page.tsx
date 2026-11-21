import type {Metadata} from "next";
import {cache} from "react";
import Link from "next/link";
import {notFound} from "next/navigation";

import ShareCatalog from "./ShareCatalog";
import CatalogCart, {type Variant} from "./CatalogCart";
const resourceLabels = {LINK:"Ver catálogo",PDF:"Abrir catálogo PDF",IMAGE:"Ver imagen del catálogo"};
type Catalog = {storeName:string; pickup?:{address:string;hours:string}|null; variants:Variant[]; hasNext:boolean; sizes:string[]; colors:string[]; categories:string[]; resource?:{kind:keyof typeof resourceLabels;url:string}|null};
type Props = {
  params:Promise<{store:string}>;
  searchParams:Promise<{q?:string|string[]; page?:string|string[]; size?:string|string[]; color?:string|string[]; category?:string|string[]}>;
};

const loadCatalog = cache(async (store:string,query:string,size:string,color:string,category:string,page:number) => {
  const base = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/,"");
  const response = await fetch(base+"/public/stores/"+store+"/catalog?"+new URLSearchParams({query,size,color,category,page:String(page)}),{cache:"no-store"});
  if (response.status===404) notFound();
  if (!response.ok) throw new Error("No se pudo cargar el catálogo.");
  return await response.json() as Catalog;
});

async function catalogView({params,searchParams}:Props) {
  const {store} = await params;
  const filters = await searchParams;
  if (!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(store)) notFound();
  const query = typeof filters.q==="string" ? filters.q.trim().slice(0,150) : "";
  const category = typeof filters.category==="string" ? filters.category : "";
  const size = typeof filters.size==="string" ? filters.size : "";
  const color = typeof filters.color==="string" ? filters.color : "";
  const page = Number(filters.page ?? 0);
  if (!Number.isInteger(page) || page<0 || page>2147483647) notFound();
  const catalog = await loadCatalog(store,query,size,color,category,page);
  return {store,query,size,color,category,page,catalog};
}

function categoryImage(resource:Catalog["resource"]):URL|undefined {
  if (resource?.kind!=="IMAGE") return;
  try {
    if (/^https:\/\//i.test(resource.url)) return new URL(resource.url);
    if (resource.url.startsWith("/catalogos/") && process.env.STOREFRONT_PUBLIC_URL) {
      const origin=new URL(process.env.STOREFRONT_PUBLIC_URL);
      if (origin.protocol==="https:" || origin.protocol==="http:") return new URL(resource.url,origin.origin);
    }
  } catch {
    // An invalid public origin should not prevent the catalog from rendering.
    return;
  }
}

export async function generateMetadata(props:Props):Promise<Metadata> {
  const {catalog,category,query,size,color}=await catalogView(props);
  const section=category && (catalog.categories.includes(category) || catalog.resource) ? category : "Catálogo de prendas";
  const title=section+" · "+catalog.storeName;
  const description="Consulta las prendas, tallas, colores y precios de "+catalog.storeName+".";
  const image=categoryImage(catalog.resource);
  const indexable = !query && !size && !color && (!category || catalog.categories.includes(category) || Boolean(catalog.resource));
  return {
    title,description,
    robots:{index:indexable,follow:true},
    openGraph:{type:"website",locale:"es_PE",siteName:catalog.storeName,title,description,
      images:image ? [{url:image,alt:title}] : undefined}
  };
}

export default async function StoreCatalog(props:Props) {
  const {store,query,size,color,category,page,catalog}=await catalogView(props);
  const path="/tienda/"+store;
  const resource=catalog.resource;
  const uploadedFile=resource?.url.startsWith("/catalogos/");
  const pageLink = (n:number)=>{
    const params=new URLSearchParams();
    if (query) params.set("q",query);
    if (category) params.set("category",category);
    if (size) params.set("size",size);
    if (color) params.set("color",color);
    if (n>0) params.set("page",String(n));
    return params.size ? path+"?"+params : path;
  };
  const sharePath=pageLink(page);

  return <main className="mx-auto max-w-5xl px-5 py-12 text-stone-900">
    <header className="border-b border-stone-300 pb-6">
      <p className="text-sm font-medium text-teal-800">Catálogo de prendas</p>
      <h1 className="mt-1 text-4xl font-semibold">{catalog.storeName}</h1>
      <ShareCatalog key={sharePath} path={sharePath} storeName={catalog.storeName}/>
      {catalog.pickup && <section className="mt-4">
        <h2 className="font-semibold">Recojo en tienda</h2>
        <p>{catalog.pickup.address}</p>
        <p>Horario: {catalog.pickup.hours}</p>
      </section>}
    </header>
    <form key={JSON.stringify([query,size,color,category])} action={path} method="get" className="my-6 flex flex-wrap items-end gap-3">
      <label className="grid flex-1 gap-1">Buscar por prenda, categoría, talla o color
        <input type="search" name="q" defaultValue={query} maxLength={150} className="rounded border p-2"/>
      </label>
      <label className="grid gap-1">Categoría
        <select name="category" defaultValue={category} className="rounded border p-2">
          <option value="">Todas las categorías</option>
          {category && !catalog.categories.includes(category) && <option value={category}>{category} (sin coincidencias)</option>}
          {catalog.categories.map(value=><option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <label className="grid gap-1">Talla
        <select name="size" defaultValue={size} className="rounded border p-2">
          <option value="">Todas las tallas</option>
          {size && !catalog.sizes.includes(size) && <option value={size}>{size} (sin coincidencias)</option>}
          {catalog.sizes.map(value=><option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <label className="grid gap-1">Color
        <select name="color" defaultValue={color} className="rounded border p-2">
          <option value="">Todos los colores</option>
          {color && !catalog.colors.includes(color) && <option value={color}>{color} (sin coincidencias)</option>}
          {catalog.colors.map(value=><option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <button className="rounded bg-teal-800 px-4 py-2 text-white">Buscar</button>
      {(query || size || color || category) && <Link href={path} className="p-2 underline">Limpiar</Link>}
    </form>
    {page===0 && !catalog.variants.length && query && (size || color) && <p className="my-3">
      <Link href={{pathname:path,query:{q:query,category}}} className="text-teal-800 underline">
        Buscar «{query}» en todas las tallas y colores
      </Link>
    </p>}
    {category && resource?.url && <p className="mb-4 flex flex-wrap gap-4">
      <a href={uploadedFile && resource.kind==="PDF" ? resource.url+"?view=1" : resource.url} target="_blank" rel="noopener noreferrer" className="text-teal-800 underline">
        {resourceLabels[resource.kind]} de {category}
      </a>
      {uploadedFile && (resource.kind==="IMAGE" || resource.kind==="PDF") &&
        <a href={resource.url+"?download=1"} download className="text-teal-800 underline">
          {resource.kind==="PDF" ? "Descargar PDF" : "Descargar imagen"}
        </a>}
    </p>}
    <CatalogCart key={store} store={store} variants={catalog.variants} query={query}/>
    <nav aria-label="Páginas del catálogo" className="mt-6 flex gap-4 text-teal-800 underline">
      {page>0 && <Link href={pageLink(page-1)}>Anterior</Link>}
      {catalog.hasNext && <Link href={pageLink(page+1)}>Siguiente</Link>}
    </nav>
  </main>;
}
