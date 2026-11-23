import type {Metadata} from "next";
import Link from "next/link";
import {Faq} from "@/components/marketing/Faq";
import {loadPlans, PricingCards, PricingTable} from "@/components/marketing/Pricing";

export const revalidate = 300;
export const metadata: Metadata = {title: "Precios", description: "Planes en soles para tiendas de ropa, con 14 días de prueba gratis."};

const faqs: [string, React.ReactNode][] = [
  ["¿Cómo se cobra la suscripción?", "Mensualmente con tarjeta de crédito o débito a través de Culqi. Recibirás el cargo cada mes en la misma fecha."],
  ["¿Qué pasa al terminar la prueba gratis?", "Tu tienda queda en pausa hasta que elijas un plan. No se borra nada: tus productos, clientes y ventas te esperan."],
  ["¿Qué pasa si se rechaza un pago?", "Tienes unos días de gracia para actualizar tu tarjeta antes de que se suspenda el servicio."],
  ["¿Puedo pasar de un plan a otro?", "Sí, desde tu panel en cualquier momento. Si llegas a un límite (por ejemplo, de productos), el panel te lo indica y puedes subir de plan al instante."],
];

export default async function Pricing() {
  const plans = await loadPlans();
  return <div className="mx-auto grid max-w-6xl gap-20 px-4 py-20 sm:px-6">
    <header className="mx-auto max-w-2xl text-center">
      <p className="text-sm font-semibold uppercase tracking-wider text-primary">Precios</p>
      <h1 className="mt-3 text-4xl font-semibold tracking-tight sm:text-5xl">Elige el plan que crece con tu tienda</h1>
      <p className="mt-4 text-lg text-muted-foreground">Precios mensuales en soles. Empieza con 14 días gratis, sin tarjeta.</p>
    </header>
    <PricingCards plans={plans}/>
    <section className="grid gap-6">
      <h2 className="text-2xl font-semibold tracking-tight">Comparación detallada</h2>
      <PricingTable plans={plans}/>
    </section>
    <section className="mx-auto grid w-full max-w-3xl gap-6">
      <h2 className="text-2xl font-semibold tracking-tight">Preguntas sobre pagos</h2>
      <Faq items={faqs}/>
      <p className="text-center text-sm text-muted-foreground">¿Tienes una cadena o más de una marca? <Link href="/prueba-gratis?plan=PREMIUM" className="font-medium text-primary hover:underline">Empieza con Premium</Link> y lo configuramos contigo.</p>
    </section>
  </div>;
}
