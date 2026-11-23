import type {Metadata} from "next";
import {LegalPage} from "@/components/marketing/LegalPage";
import {brand} from "@/lib/brand";

export const metadata: Metadata = {title: "Términos y condiciones"};

export default function Terms() {
  return <LegalPage title="Términos y condiciones" updated="23 de noviembre de 2026">
    <p>Estos términos regulan el uso de {brand.name}, una plataforma en la nube para que tiendas de ropa vendan por web, WhatsApp y punto de venta. Al crear una cuenta aceptas estas condiciones.</p>
    <h2>1. El servicio</h2>
    <p>{brand.name} te permite administrar catálogo, inventario, pedidos, clientes, cobros y comprobantes electrónicos. Algunas funciones dependen de terceros (Meta/WhatsApp, Mercado Pago, Culqi, SUNAT y proveedores de facturación), cuyas condiciones también debes cumplir.</p>
    <h2>2. Cuenta y seguridad</h2>
    <p>Eres responsable de la información de tu tienda y de mantener tus credenciales en reserva. Recomendamos activar la verificación en dos pasos.</p>
    <h2>3. Prueba gratis y planes</h2>
    <ul><li>La prueba gratis dura 14 días y no requiere tarjeta.</li><li>Los planes se cobran mensualmente en soles a través de Culqi.</li><li>Si un pago no se completa, la cuenta tiene un periodo de gracia antes de suspenderse.</li></ul>
    <h2>4. Pagos de tus clientes</h2>
    <p>Los cobros de tus ventas se procesan en tu propia cuenta de Mercado Pago. {brand.name} no recibe ni retiene ese dinero.</p>
    <h2>5. Uso aceptable</h2>
    <p>No puedes usar la plataforma para enviar spam, vender productos ilegales ni infringir las políticas de WhatsApp o de los medios de pago.</p>
    <h2>6. Contacto</h2>
    <p>Para consultas sobre estos términos, escríbenos desde tu panel o al correo de soporte indicado en tu cuenta.</p>
    <p className="text-xs">Este documento es una versión base y debe ser revisado por asesoría legal antes de su publicación definitiva.</p>
  </LegalPage>;
}
