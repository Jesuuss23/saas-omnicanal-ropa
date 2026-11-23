"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {useStore} from "../_panel/StoreContext";
import WhatsAppChannels from "../WhatsAppChannels";

export default function WhatsApp() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="WhatsApp" description="Conecta tu número por QR o con la API oficial de Meta y responde desde la bandeja."/>
    <WhatsAppChannels store={store} send={send}/>
  </>;
}
