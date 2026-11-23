import Link from "next/link";
import {Logo} from "@/components/auth/AuthShell";
import {brand} from "@/lib/brand";

const columns = [
  {title: "Producto", links: [["Funciones", "/#funciones"], ["Precios", "/precios"], ["Prueba gratis", "/prueba-gratis"]]},
  {title: "Cuenta", links: [["Ingresar a mi tienda", "/ingresar"], ["Recuperar acceso", "/recuperar-acceso"]]},
  {title: "Legal", links: [["Términos y condiciones", "/terminos"], ["Política de privacidad", "/privacidad"]]},
];

export function SiteFooter() {
  return <footer className="border-t bg-card">
    <div className="mx-auto grid max-w-6xl gap-10 px-4 py-12 sm:px-6 md:grid-cols-[2fr_repeat(3,1fr)]">
      <div className="grid content-start gap-3">
        <Link href="/" className="flex items-center gap-2 font-semibold"><Logo/>{brand.name}</Link>
        <p className="max-w-xs text-sm text-muted-foreground">{brand.description}</p>
      </div>
      {columns.map(c => <div key={c.title}>
        <p className="text-sm font-semibold">{c.title}</p>
        <ul className="mt-3 grid gap-2 text-sm text-muted-foreground">
          {c.links.map(([label, href]) => <li key={href}><Link href={href} className="hover:text-foreground">{label}</Link></li>)}
        </ul>
      </div>)}
    </div>
    <div className="border-t"><p className="mx-auto max-w-6xl px-4 py-5 text-xs text-muted-foreground sm:px-6">© {new Date().getFullYear()} {brand.name}. Hecho en Perú para tiendas de ropa.</p></div>
  </footer>;
}
