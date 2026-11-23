import Link from "next/link";
import {Menu} from "lucide-react";
import {Logo} from "@/components/auth/AuthShell";
import {Button} from "@/components/ui/button";
import {brand} from "@/lib/brand";

const links = [
  {href: "/#funciones", label: "Funciones"},
  {href: "/#como-funciona", label: "Cómo funciona"},
  {href: "/precios", label: "Precios"},
  {href: "/#preguntas", label: "Preguntas"},
];

export function SiteHeader() {
  return <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-lg">
    <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4 sm:px-6">
      <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight"><Logo/>{brand.name}</Link>
      <nav aria-label="Principal" className="hidden flex-1 items-center gap-6 text-sm text-muted-foreground md:flex">
        {links.map(l => <Link key={l.href} href={l.href} className="transition-colors hover:text-foreground">{l.label}</Link>)}
      </nav>
      <div className="ml-auto hidden items-center gap-2 md:flex">
        <Button asChild variant="ghost"><Link href="/ingresar">Ingresar</Link></Button>
        <Button asChild><Link href="/prueba-gratis">Prueba gratis</Link></Button>
      </div>
      <details className="group relative ml-auto md:hidden">
        <summary className="flex size-10 cursor-pointer list-none items-center justify-center rounded-lg hover:bg-muted" aria-label="Abrir menú"><Menu className="size-5"/></summary>
        <div className="absolute right-0 top-12 grid w-60 gap-1 rounded-xl border bg-popover p-2 shadow-pop">
          {links.map(l => <Link key={l.href} href={l.href} className="rounded-md px-3 py-2 text-sm hover:bg-muted">{l.label}</Link>)}
          <hr className="my-1"/>
          <Link href="/ingresar" className="rounded-md px-3 py-2 text-sm hover:bg-muted">Ingresar</Link>
          <Button asChild className="mt-1"><Link href="/prueba-gratis">Prueba gratis 14 días</Link></Button>
        </div>
      </details>
    </div>
  </header>;
}
