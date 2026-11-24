import {backendBase} from "@/lib/api";
import type {Metadata} from "next";
import type {CSSProperties} from "react";
import Link from "next/link";
import {notFound} from "next/navigation";
import {ArrowLeft} from "lucide-react";
import {brandStyle, type Brand} from "../brand";
import CustomerAccount from "./CustomerAccount";

type Props = {params: Promise<{store: string}>};
const UUID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i;

async function store(id: string) {
  if (!UUID.test(id)) notFound();
  const res = await fetch(backendBase() + "/public/stores/" + id + "/catalog?page=0&variant=00000000-0000-0000-0000-000000000000", {cache: "no-store"});
  if (res.status === 404) notFound();
  if (!res.ok) throw new Error("No se pudo cargar la tienda.");
  return await res.json() as {storeName: string; brand?: Brand | null};
}

export async function generateMetadata({params}: Props): Promise<Metadata> {
  const {storeName} = await store((await params).store);
  return {title: "Mi cuenta · " + storeName, robots: {index: false}};
}

export default async function AccountPage({params}: Props) {
  const id = (await params).store;
  const {storeName, brand} = await store(id);
  return <div style={brandStyle(brand) as CSSProperties} className="min-h-dvh bg-background text-foreground">
    <header className="bg-[var(--store)] text-[var(--store-fg)]">
      <div className="mx-auto flex max-w-3xl items-center gap-3 px-4 py-5 sm:px-6">
        <Link href={"/tienda/" + id} className="inline-flex items-center gap-1.5 text-sm font-medium underline-offset-4 hover:underline">
          <ArrowLeft className="size-4"/>Volver a {storeName}</Link>
      </div>
    </header>
    <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Mi cuenta</h1>
      <p className="mt-1 text-muted-foreground">Tus pedidos en {storeName}, en un solo lugar.</p>
      <CustomerAccount store={id} storeName={storeName}/>
    </main>
  </div>;
}
