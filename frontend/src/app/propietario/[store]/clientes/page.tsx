"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import ContactsCrm from "../ContactsCrm";

export default function Customers() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Clientes" description="Contactos que te escribieron por WhatsApp, su etapa y los seguimientos pendientes."/>
    <ContactsCrm store={store} send={send}/>
  </>;
}
