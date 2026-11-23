import Link from "next/link";
import {
  ArrowRight, Bot, Boxes, Check, CreditCard, FileCheck2, Globe, MessageCircle, QrCode, ReceiptText,
  ShieldCheck, Store, Users, Zap,
} from "lucide-react";
import {Faq} from "@/components/marketing/Faq";
import {loadPlans, PricingCards} from "@/components/marketing/Pricing";
import {ProductMockup} from "@/components/marketing/ProductMockup";
import {Button} from "@/components/ui/button";

export const revalidate = 300;

const pains = [
  ["Vendes por WhatsApp y el stock no cuadra", "Separas una prenda por chat, otra persona la vende en tienda y terminas pidiendo disculpas."],
  ["Respondes lo mismo cien veces al día", "Precio, tallas, colores, envío… y mientras tanto otros clientes se enfrían."],
  ["Cobrar y emitir comprobantes te quita horas", "Yape aquí, transferencia allá, boletas a mano y el cierre de caja que nunca cuadra."],
];

const features = [
  {icon: Globe, title: "Catálogo web con stock real", text: "Tu tienda en línea con filtros por talla, color y categoría. El cliente separa y paga sin escribirte.", span: "lg:col-span-2"},
  {icon: MessageCircle, title: "WhatsApp en dos modalidades", text: "Conéctalo en minutos con QR o usa la API oficial de Meta con plantillas y número verificado."},
  {icon: Bot, title: "Asistente con IA", text: "Responde precios, tallas y disponibilidad con tu inventario real, y te pasa la conversación cuando hace falta."},
  {icon: Store, title: "Punto de venta con QR", text: "Cobra en tienda escaneando la etiqueta de la prenda con la cámara del celular o un lector."},
  {icon: ReceiptText, title: "Boletas y facturas SUNAT", text: "Emite comprobantes electrónicos desde la venta, sin pasar datos a otro sistema.", span: "lg:col-span-2"},
  {icon: CreditCard, title: "Cobros con Mercado Pago", text: "Vincula tu cuenta: el dinero de tus ventas web llega directo a ti."},
  {icon: Users, title: "CRM de clientes", text: "Etapas de venta, seguimientos y el historial de cada conversación en un solo lugar.", span: "lg:col-span-2"},
  {icon: ShieldCheck, title: "Seguridad de verdad", text: "Verificación en dos pasos, secretos cifrados y cada tienda aislada de las demás.", span: "lg:col-span-2"},
];

const steps = [
  ["Crea tu tienda", "Regístrate con tu correo y empieza tu prueba de 14 días. No necesitas tarjeta."],
  ["Sube tus prendas", "Carga productos con tallas, colores y stock. Imprime etiquetas QR para tu local."],
  ["Conecta tus canales", "Vincula WhatsApp y Mercado Pago y comparte tu catálogo. Desde ese momento, todo se sincroniza."],
];

const faqs: [string, React.ReactNode][] = [
  ["¿Necesito tarjeta para la prueba gratis?", "No. Creas tu tienda con tu correo y usas la plataforma 14 días. Al final eliges un plan y pagas con tarjeta de forma segura con Culqi."],
  ["¿Puedo usar mi número de WhatsApp actual?", "Sí. Puedes conectarlo escaneando un QR (rápido, ideal para empezar) o migrar a la API oficial de Meta cuando necesites plantillas y mayor volumen, sin perder tu historial."],
  ["¿Cómo recibo el dinero de mis ventas?", "Vinculas tu propia cuenta de Mercado Pago. Los pagos de tu catálogo web van directo a tu cuenta; nosotros no tocamos tu dinero."],
  ["¿Emiten boletas y facturas electrónicas?", "Sí. Configuras tu RUC y certificado, pruebas en el entorno Beta de SUNAT y luego pasas a producción en los planes que lo incluyen."],
  ["¿Puedo cambiar de plan?", "Cuando quieras, desde tu panel. Si llegas a un límite de tu plan, el panel te lo indica y puedes subir de plan al instante."],
  ["¿Mis datos y los de mis clientes están seguros?", "Cada tienda está aislada, las credenciales se guardan cifradas y el acceso se protege con verificación en dos pasos."],
];

export default async function Home() {
  const plans = await loadPlans();
  return <>
    <section className="relative overflow-hidden">
      <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60rem_30rem_at_70%_-10%,var(--brand-100),transparent),radial-gradient(40rem_25rem_at_0%_20%,var(--brand-50),transparent)]"/>
      <div className="mx-auto grid max-w-6xl items-center gap-16 px-4 pb-24 pt-16 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:pt-24">
        <div className="grid gap-7">
          <span className="inline-flex w-fit items-center gap-2 rounded-full border bg-card px-3 py-1 text-xs font-medium text-muted-foreground shadow-card">
            <Zap className="size-3.5 text-primary"/>Hecho para tiendas de ropa en Perú
          </span>
          <h1 className="text-4xl font-semibold leading-[1.05] tracking-tight sm:text-5xl xl:text-[3.5rem]">
            Vende ropa por WhatsApp, web y tienda <span className="bg-gradient-to-r from-brand-700 to-brand-500 bg-clip-text text-transparent">sin descuadrar tu stock</span>
          </h1>
          <p className="max-w-xl text-lg leading-relaxed text-muted-foreground">
            Un solo inventario para todos tus canales, un asistente que responde a tus clientes, cobros con Mercado Pago y boletas SUNAT en el mismo lugar.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button asChild size="lg"><Link href="/prueba-gratis">Prueba gratis 14 días <ArrowRight/></Link></Button>
            <Button asChild size="lg" variant="outline"><Link href="/precios">Ver precios</Link></Button>
          </div>
          <ul className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted-foreground">
            {["Sin tarjeta para empezar", "Configuración en minutos", "Soporte en español"].map(t =>
              <li key={t} className="flex items-center gap-1.5"><Check className="size-4 text-primary"/>{t}</li>)}
          </ul>
        </div>
        <div className="pb-10 lg:pb-0"><ProductMockup/></div>
      </div>
    </section>

    <section className="border-y bg-card">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-x-10 gap-y-4 px-4 py-6 text-sm text-muted-foreground sm:px-6">
        <span className="font-medium text-foreground">Un inventario, todos tus canales:</span>
        {[[Globe, "Catálogo web"], [MessageCircle, "WhatsApp"], [Store, "Tienda física"], [QrCode, "Etiquetas QR"], [FileCheck2, "SUNAT"], [CreditCard, "Mercado Pago"]].map(([Icon, label]) => {
          const I = Icon as typeof Globe;
          return <span key={label as string} className="flex items-center gap-2"><I className="size-4 text-primary"/>{label as string}</span>;
        })}
      </div>
    </section>

    <section className="mx-auto max-w-6xl px-4 py-24 sm:px-6">
      <SectionTitle eyebrow="El problema" title="Vender en varios canales no debería ser un caos"/>
      <div className="mt-12 grid gap-6 md:grid-cols-3">
        {pains.map(([title, text], i) => <div key={title} className="rounded-2xl border bg-card p-7 shadow-card">
          <span className="font-mono text-sm text-primary">0{i + 1}</span>
          <h3 className="mt-3 text-lg font-semibold">{title}</h3>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{text}</p>
        </div>)}
      </div>
    </section>

    <section id="funciones" className="scroll-mt-20 bg-brand-950 py-24 text-white">
      <div className="mx-auto max-w-6xl px-4 sm:px-6">
        <SectionTitle eyebrow="La solución" title="Todo lo que tu tienda necesita, conectado" invert
          text="Cada venta, venga de donde venga, descuenta el mismo stock y queda registrada con su cliente."/>
        <div className="mt-14 grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {features.map(f => <div key={f.title} className={"rounded-2xl border border-white/10 bg-white/[0.04] p-6 transition-colors hover:bg-white/[0.07] " + (f.span ?? "")}>
            <span className="grid size-10 place-items-center rounded-xl bg-brand-500/20 text-brand-100"><f.icon className="size-5"/></span>
            <h3 className="mt-5 font-semibold">{f.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-white/65">{f.text}</p>
          </div>)}
        </div>
      </div>
    </section>

    <section id="como-funciona" className="mx-auto max-w-6xl scroll-mt-20 px-4 py-24 sm:px-6">
      <SectionTitle eyebrow="Cómo funciona" title="Empieza a vender hoy mismo"/>
      <ol className="mt-14 grid gap-8 md:grid-cols-3">
        {steps.map(([title, text], i) => <li key={title} className="relative grid gap-3">
          <span className="grid size-12 place-items-center rounded-2xl bg-primary text-lg font-semibold text-primary-foreground shadow-card">{i + 1}</span>
          <h3 className="text-lg font-semibold">{title}</h3>
          <p className="text-sm leading-relaxed text-muted-foreground">{text}</p>
        </li>)}
      </ol>
    </section>

    <section id="precios" className="scroll-mt-20 border-y bg-muted/40 py-24">
      <div className="mx-auto max-w-6xl px-4 sm:px-6">
        <SectionTitle eyebrow="Precios" title="Planes simples, en soles" text="Todos incluyen 14 días de prueba gratis. Cambia de plan cuando lo necesites."/>
        <div className="mt-14"><PricingCards plans={plans}/></div>
        <p className="mt-8 text-center text-sm"><Link href="/precios" className="inline-flex items-center gap-1 font-medium text-primary hover:underline">Comparar todos los detalles <ArrowRight className="size-4"/></Link></p>
      </div>
    </section>

    <section id="preguntas" className="mx-auto max-w-3xl scroll-mt-20 px-4 py-24 sm:px-6">
      <SectionTitle eyebrow="Preguntas frecuentes" title="Lo que suelen preguntarnos"/>
      <div className="mt-12"><Faq items={faqs}/></div>
    </section>

    <section className="px-4 pb-24 sm:px-6">
      <div className="relative mx-auto max-w-6xl overflow-hidden rounded-3xl bg-gradient-to-br from-brand-700 to-brand-950 px-8 py-16 text-center text-white shadow-pop">
        <Boxes aria-hidden className="absolute -right-6 -top-6 size-40 text-white/5"/>
        <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">Tu próxima venta puede llegar por cualquier canal</h2>
        <p className="mx-auto mt-4 max-w-xl text-white/75">Prepárate para atenderla con stock al día, respuestas al instante y cobro inmediato.</p>
        <Button asChild size="lg" variant="secondary" className="mt-8"><Link href="/prueba-gratis">Crear mi tienda gratis <ArrowRight/></Link></Button>
      </div>
    </section>
  </>;
}

function SectionTitle({eyebrow, title, text, invert}: {eyebrow: string; title: string; text?: string; invert?: boolean}) {
  return <div className="mx-auto max-w-2xl text-center">
    <p className={"text-sm font-semibold uppercase tracking-wider " + (invert ? "text-brand-100" : "text-primary")}>{eyebrow}</p>
    <h2 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">{title}</h2>
    {text && <p className={"mt-4 text-lg " + (invert ? "text-white/70" : "text-muted-foreground")}>{text}</p>}
  </div>;
}
