import Link from "next/link";
import {ArrowRight, MessageCircle, ShoppingBag, Store} from "lucide-react";
import {Button} from "@/components/ui/button";
import {brand} from "@/lib/brand";

// Portada provisional: el sitio de venta completo llega en el Sprint 3.
const features = [
  {icon: ShoppingBag, title: "Catálogo web", text: "Tu tienda en línea con stock real y pedidos con pago."},
  {icon: MessageCircle, title: "WhatsApp", text: "Atiende y vende por WhatsApp con QR o con la API oficial."},
  {icon: Store, title: "Punto de venta", text: "Cobra en tienda física con boletas y facturas SUNAT."},
];

export default function Home() {
  return <main className="min-h-dvh bg-gradient-to-b from-brand-50 to-background">
    <div className="mx-auto flex max-w-5xl flex-col gap-16 px-6 py-20">
      <header className="grid gap-6">
        <span className="text-sm font-semibold tracking-wide text-primary">{brand.name}</span>
        <h1 className="max-w-3xl text-4xl font-semibold tracking-tight sm:text-5xl">{brand.tagline}</h1>
        <p className="max-w-2xl text-lg text-muted-foreground">{brand.description}</p>
        <div className="flex flex-wrap gap-3">
          <Button asChild size="lg"><Link href="/superadmin">Ingresar a la plataforma <ArrowRight/></Link></Button>
        </div>
      </header>
      <ul className="grid gap-4 sm:grid-cols-3">
        {features.map(({icon: Icon, title, text}) => <li key={title} className="rounded-xl border bg-card p-6 shadow-card">
          <Icon className="size-6 text-primary" aria-hidden/>
          <h2 className="mt-4 font-semibold">{title}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{text}</p>
        </li>)}
      </ul>
    </div>
  </main>;
}
