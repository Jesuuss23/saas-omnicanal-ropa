import {backendBase} from "@/lib/api";
import type {Metadata} from "next";
import type {CSSProperties} from "react";
import {cache} from "react";
import Link from "next/link";
import {notFound} from "next/navigation";
import {AtSign, Clock, MapPin, MessageCircle, Search, UserRound, X} from "lucide-react";

import ShareCatalog from "./ShareCatalog";
import CatalogCart, {type Variant} from "./CatalogCart";
import {brandStyle, instagramLink, whatsappLink, type Brand} from "./brand";

const resourceLabels = {LINK:"Ver catálogo",PDF:"Abrir catálogo PDF",IMAGE:"Ver imagen del catálogo"};
type Catalog = {storeName:string; pickup?:{address:string;hours:string}|null; variants:Variant[]; hasNext:boolean; sizes:string[]; colors:string[]; categories:string[]; resource?:{kind:keyof typeof resourceLabels;url:string}|null; brand?:Brand|null};
type Props = {
  params:Promise<{store:string}>;
  searchParams:Promise<{q?:string|string[]; page?:string|string[]; size?:string|string[]; color?:string|string[]; category?:string|string[]; variant?:string|string[]}>;
};
const UUID=/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;

const loadCatalog = cache(async (store:string,query:string,size:string,color:string,category:string,page:number,variant:string) => {
  const base = backendBase();
  const response = await fetch(base+"/public/stores/"+store+"/catalog?"+new URLSearchParams({query,size,color,category,page:String(page),...(variant ? {variant} : {})}),{cache:"no-store"});
  if (response.status===404) notFound();
  if (!response.ok) throw new Error("No se pudo cargar el catálogo.");
  return await response.json() as Catalog;
});

async function catalogView({params,searchParams}:Props) {
  const {store} = await params;
  const filters = await searchParams;
  if (!UUID.test(store)) notFound();
  const query = typeof filters.q==="string" ? filters.q.trim().slice(0,150) : "";
  const category = typeof filters.category==="string" ? filters.category : "";
  const size = typeof filters.size==="string" ? filters.size : "";
  const color = typeof filters.color==="string" ? filters.color : "";
  const variant=filters.variant ?? "";
  if (typeof variant!=="string" || (variant && !UUID.test(variant))) notFound();
  const page = Number(filters.page ?? 0);
  if (!Number.isInteger(page) || page<0 || page>2147483647) notFound();
  const catalog = await loadCatalog(store,query,size,color,category,page,variant);
  return {store,query,size,color,category,page,variant,catalog};
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
  const {catalog,category,query,size,color,variant}=await catalogView(props);
  const section=category && (catalog.categories.includes(category) || catalog.resource) ? category : "Catálogo de prendas";
  const title=(variant && catalog.variants[0] ? catalog.variants[0].name+" · Talla "+catalog.variants[0].size : section)+" · "+catalog.storeName;
  const description=catalog.brand?.tagline || "Consulta las prendas, tallas, colores y precios de "+catalog.storeName+".";
  const image=categoryImage(catalog.resource);
  const indexable = !variant && !query && !size && !color && (!category || catalog.categories.includes(category) || Boolean(catalog.resource));
  return {
    title,description,
    robots:{index:indexable,follow:true},
    openGraph:{type:"website",locale:"es_PE",siteName:catalog.storeName,title,description,
      images:image ? [{url:image,alt:title}] : undefined}
  };
}

const chip="inline-flex h-9 shrink-0 items-center rounded-full border px-4 text-sm font-medium transition-colors";

export default async function StoreCatalog(props:Props) {
  const {store,query,size,color,category,page,variant,catalog}=await catalogView(props);
  const path="/tienda/"+store;
  const resource=catalog.resource;
  const brand=catalog.brand;
  const uploadedFile=resource?.url.startsWith("/catalogos/");
  const link = (changes:Record<string,string|number|undefined>)=>{
    const state:Record<string,string|number|undefined>={variant,q:query,category,size,color,page:0,...changes};
    const params=new URLSearchParams();
    for (const [key,value] of Object.entries(state)) if (value && value!==0) params.set(key,String(value));
    return params.size ? path+"?"+params : path;
  };
  const sharePath=link({page});
  const whatsapp=whatsappLink(brand?.whatsappPhone,"Hola "+catalog.storeName+", vi su catálogo y quiero hacer una consulta.");
  const instagram=instagramLink(brand?.instagram);
  const logo=brand?.logoUrl ?? (brand?.logoVersion ? "/api/public/stores/"+store+"/logo?v="+brand.logoVersion : null);
  const filtered=Boolean(query || size || color || category);

  return <div style={brandStyle(brand) as CSSProperties} className="min-h-dvh bg-background text-foreground">
    <header className="bg-[var(--store)] text-[var(--store-fg)]">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-5 px-4 py-8 sm:px-6 sm:py-10">
        {logo
          // eslint-disable-next-line @next/next/no-img-element
          ? <img src={logo} alt={"Logo de "+catalog.storeName} className="size-16 shrink-0 rounded-2xl bg-white object-contain p-1 shadow-sm sm:size-20"/>
          : <span aria-hidden className="grid size-16 shrink-0 place-items-center rounded-2xl bg-white/15 text-2xl font-semibold sm:size-20">{catalog.storeName.slice(0,1).toUpperCase()}</span>}
        <div className="min-w-0 flex-1">
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">{catalog.storeName}</h1>
          <p className="mt-1 max-w-2xl opacity-85">{brand?.tagline || "Catálogo de prendas"}</p>
          <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
            {whatsapp && <a href={whatsapp} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline">
              <MessageCircle className="size-4"/>Escríbenos por WhatsApp</a>}
            {instagram && <a href={instagram} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline">
              <AtSign className="size-4"/>Instagram</a>}
            <ShareCatalog key={sharePath} path={sharePath} title={"Catálogo · "+catalog.storeName} label="Compartir catálogo" tone="inherit"/>
            <Link href={path+"/cuenta"} className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline"><UserRound className="size-4"/>Mi cuenta</Link>
          </div>
        </div>
      </div>
    </header>

    {(brand?.description || catalog.pickup) && <section aria-label="Sobre la tienda" className="border-b border-border bg-card">
      <div className="mx-auto flex max-w-6xl flex-wrap gap-x-10 gap-y-3 px-4 py-4 text-sm sm:px-6">
        {brand?.description && <p className="max-w-2xl text-muted-foreground">{brand.description}</p>}
        {catalog.pickup && <div className="flex flex-wrap gap-x-6 gap-y-1">
          <h2 className="sr-only">Recojo en tienda</h2>
          <p className="inline-flex items-center gap-1.5"><MapPin className="size-4 text-[var(--store)]"/>Recojo en {catalog.pickup.address}</p>
          <p className="inline-flex items-center gap-1.5"><Clock className="size-4 text-[var(--store)]"/>Horario: {catalog.pickup.hours}</p>
        </div>}
      </div>
    </section>}

    <main className="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
      <form key={JSON.stringify([query,size,color,category])} action={path} method="get" role="search"
        className="z-10 -mx-4 flex md:sticky md:top-0 flex-wrap items-center gap-2 border-b border-border bg-background/95 px-4 py-3 backdrop-blur sm:-mx-6 sm:px-6">
        {category && <input type="hidden" name="category" value={category}/>}
        <label className="relative min-w-52 flex-1">
          <span className="sr-only">Buscar por prenda, categoría, talla o color</span>
          <Search aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"/>
          <input type="search" name="q" defaultValue={query} maxLength={150} placeholder="Buscar prendas, tallas o colores"
            className="h-10 w-full rounded-full border border-input bg-card pr-4 pl-9 text-sm outline-none focus-visible:ring-2 focus-visible:ring-[var(--store)]"/>
        </label>
        <label className="sr-only" htmlFor="size-filter">Talla</label>
        <select id="size-filter" name="size" defaultValue={size} className="h-10 rounded-full border border-input bg-card px-3 text-sm">
          <option value="">Todas las tallas</option>
          {size && !catalog.sizes.includes(size) && <option value={size}>{size} (sin coincidencias)</option>}
          {catalog.sizes.map(value=><option key={value} value={value}>Talla {value}</option>)}
        </select>
        <label className="sr-only" htmlFor="color-filter">Color</label>
        <select id="color-filter" name="color" defaultValue={color} className="h-10 rounded-full border border-input bg-card px-3 text-sm">
          <option value="">Todos los colores</option>
          {color && !catalog.colors.includes(color) && <option value={color}>{color} (sin coincidencias)</option>}
          {catalog.colors.map(value=><option key={value} value={value}>{value}</option>)}
        </select>
        <button className="h-10 rounded-full bg-[var(--store)] px-5 text-sm font-medium text-[var(--store-fg)]">Buscar</button>
        {filtered && <Link href={path} className="inline-flex h-10 items-center gap-1 px-2 text-sm text-muted-foreground hover:text-foreground"><X className="size-4"/>Limpiar</Link>}
      </form>

      {(catalog.categories.length>0 || category) && <nav aria-label="Categorías" className="-mx-4 flex gap-2 overflow-x-auto px-4 py-4 sm:-mx-6 sm:px-6">
        <Link href={link({category:""})} aria-current={!category ? "page" : undefined}
          className={chip+(!category ? " border-transparent bg-[var(--store)] text-[var(--store-fg)]" : " border-border bg-card hover:bg-muted")}>Todo</Link>
        {[...catalog.categories, ...(category && !catalog.categories.includes(category) ? [category] : [])].map(value=>
          <Link key={value} href={link({category:value})} aria-current={value===category ? "page" : undefined}
            className={chip+(value===category ? " border-transparent bg-[var(--store)] text-[var(--store-fg)]" : " border-border bg-card hover:bg-muted")}>{value}</Link>)}
      </nav>}

      {page===0 && !catalog.variants.length && query && (size || color) && <p className="my-3">
        <Link href={{pathname:path,query:{q:query,category}}} className="font-medium text-[var(--store)] underline">
          Buscar «{query}» en todas las tallas y colores
        </Link>
      </p>}
      {category && resource?.url && <p className="mb-4 flex flex-wrap gap-4 text-sm">
        <a href={uploadedFile && resource.kind==="PDF" ? resource.url+"?view=1" : resource.url} target="_blank" rel="noopener noreferrer" className="font-medium text-[var(--store)] underline">
          {resourceLabels[resource.kind]} de {category}
        </a>
        {uploadedFile && (resource.kind==="IMAGE" || resource.kind==="PDF") &&
          <a href={resource.url+"?download=1"} download className="font-medium text-[var(--store)] underline">
            {resource.kind==="PDF" ? "Descargar PDF" : "Descargar imagen"}
          </a>}
      </p>}
      {variant && <p className="my-3">{!catalog.variants.length && "Esta variante ya no está disponible. "}
        <Link href={path} className="font-medium text-[var(--store)] underline">Ver catálogo completo</Link>
      </p>}

      <CatalogCart key={store} store={store} variants={catalog.variants} query={query} theme={brandStyle(brand) as CSSProperties} pager={
        (page>0 || catalog.hasNext) ? <nav aria-label="Páginas del catálogo" className="mt-8 flex justify-center gap-3">
          {page>0 && <Link href={link({page:page-1})} className={chip+" border-border bg-card hover:bg-muted"}>Anterior</Link>}
          {catalog.hasNext && <Link href={link({page:page+1})} className={chip+" border-border bg-card hover:bg-muted"}>Siguiente</Link>}
        </nav> : null}/>
    </main>
    <footer className="border-t border-border py-6 text-center text-xs text-muted-foreground">
      {catalog.storeName} · Tienda creada con <Link href="/" className="underline">Omnicanal</Link>
    </footer>
  </div>;
}
