import type {Metadata} from "next";
import type {ReactNode} from "react";

export const metadata:Metadata = {
  title:"Seguimiento privado del pedido",
  description:"Consulta el estado del pedido con tu enlace privado.",
  robots:{index:false,follow:false}
};

export default function OrderTrackingLayout({children}:{children:ReactNode}) {
  return children;
}
