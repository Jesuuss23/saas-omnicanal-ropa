import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import NewStore from "./NewStore";
import Logout from "./Logout";

type Dashboard = {email: string; stores: number; products: number; variants: number; recent: {name: string; domain: string | null}[]};

export default async function Panel() {
  const session = (await cookies()).get("JSESSIONID");
  if (!session) redirect("/superadmin/login");
  const base = (process.env.BACKEND_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api").replace(/\/$/, "");
  const res = await fetch(base + "/superadmin/dashboard", {
    headers: {Cookie: "JSESSIONID=" + encodeURIComponent(session.value)}, cache: "no-store"
  });
  if (res.status === 401 || res.status === 403) redirect("/superadmin/login");
  if (!res.ok) throw new Error("No se pudo cargar el panel.");
  const data: Dashboard = await res.json();
  return <main className="min-h-screen bg-stone-50 text-stone-900">
    <header className="bg-teal-950 text-white"><div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-5"><span className="text-xl font-semibold">Omnicanal</span><Logout /></div></header>
    <div className="mx-auto max-w-6xl px-6 py-10"><p className="text-sm text-teal-800">{data.email}</p><h1 className="mt-2 text-4xl font-semibold tracking-tight">Resumen de plataforma</h1>
    <dl className="my-8 grid gap-4 sm:grid-cols-3">{[["Tiendas",data.stores],["Productos",data.products],["Variantes",data.variants]].map(([label,count]) => <div key={label} className="rounded-xl border border-stone-200 bg-white p-6"><dt className="text-sm text-stone-600">{label}</dt><dd className="mt-3 text-4xl font-semibold tabular-nums">{count}</dd></div>)}</dl>
    <NewStore />
    <section className="rounded-xl border border-stone-200 bg-white p-6"><h2 className="text-xl font-semibold">Últimas tiendas registradas</h2>
    {data.recent.length ? <ul className="mt-4 divide-y divide-stone-200">{data.recent.map((store,i) => <li key={i} className="flex flex-wrap justify-between gap-2 py-3"><strong>{store.name}</strong><span className="text-stone-600">{store.domain || "Dominio pendiente"}</span></li>)}</ul> : <p className="mt-3 text-stone-600">Todavía no hay tiendas registradas.</p>}</section></div></main>;
}
