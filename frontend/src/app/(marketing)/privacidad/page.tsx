import type {Metadata} from "next";
import {LegalPage} from "@/components/marketing/LegalPage";
import {brand} from "@/lib/brand";

export const metadata: Metadata = {title: "Política de privacidad"};

export default function Privacy() {
  return <LegalPage title="Política de privacidad" updated="23 de noviembre de 2026">
    <p>En {brand.name} tratamos los datos personales conforme a la Ley N.° 29733, Ley de Protección de Datos Personales del Perú, y su reglamento.</p>
    <h2>1. Qué datos tratamos</h2>
    <ul><li>De los dueños de tienda: nombre, correo, celular y datos de facturación.</li><li>De los compradores de cada tienda: nombre, teléfono, dirección de entrega y mensajes, que la tienda gestiona como responsable.</li></ul>
    <h2>2. Para qué los usamos</h2>
    <p>Para prestar el servicio, procesar pedidos y comprobantes, enviar avisos de la cuenta y mejorar la plataforma. No vendemos datos personales.</p>
    <h2>3. Seguridad</h2>
    <p>Cada tienda está aislada de las demás, las credenciales de integraciones se guardan cifradas y el acceso puede protegerse con verificación en dos pasos.</p>
    <h2>4. Encargados</h2>
    <p>Compartimos datos solo con los proveedores necesarios para operar: alojamiento, correo transaccional, WhatsApp, medios de pago y facturación electrónica.</p>
    <h2>5. Tus derechos</h2>
    <p>Puedes ejercer tus derechos de acceso, rectificación, cancelación y oposición escribiéndonos desde tu cuenta.</p>
    <p className="text-xs">Este documento es una versión base y debe ser revisado por asesoría legal antes de su publicación definitiva.</p>
  </LegalPage>;
}
