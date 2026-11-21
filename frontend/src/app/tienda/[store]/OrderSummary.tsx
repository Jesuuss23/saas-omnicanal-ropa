import Link from "next/link";
import OrderAmounts from "../../components/OrderAmounts";

type OrderItem={variant:string;name:string;size:string;color:string|null;quantity:number;unitPrice:number};
export type Order={id:string;total:number;items?:OrderItem[];trackingToken?:string;freeDelivery?:boolean;deliveryFee?:number|null;method?:string|null};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

export default function OrderSummary({order,catalogPath}:{catalogPath?:string;order:Pick<Order,"items"|"total"|"freeDelivery"|"deliveryFee"|"method">}) {
  return <>
    {order.items && <ul aria-label="Prendas del pedido" className="my-3 divide-y">
      {order.items.map(item=><li key={item.variant} className="py-2">
        <strong>{item.name}</strong>
        <p>Talla {item.size}{item.color && " · "+item.color}</p>
        <p>{item.quantity} × {money.format(item.unitPrice)} = {money.format(Math.round(item.unitPrice*100)*item.quantity/100)}</p>
        {catalogPath && <Link prefetch={false} href={{
          pathname:catalogPath,
          query:{q:item.name.slice(0,150),size:item.size,...(item.color ? {color:item.color} : {})}
        }}
          className="text-teal-800 underline" aria-label={"Buscar "+item.name+", talla "+item.size+(item.color ? ", color "+item.color : "")}>
          Buscar esta prenda en la misma talla{item.color && " y color"}
        </Link>}
      </li>)}
    </ul>}
    <OrderAmounts merchandise={order.total} method={order.method} freeDelivery={order.freeDelivery} fee={order.deliveryFee}/>
  </>;
}
