type OrderItem={variant:string;name:string;size:string;color:string|null;quantity:number;unitPrice:number};
export type Order={id:string;total:number;items?:OrderItem[];trackingToken?:string;freeDelivery?:boolean;deliveryFee?:number|null};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

export default function OrderSummary({order}:{order:Pick<Order,"items"|"total"|"freeDelivery"|"deliveryFee">}) {
  return <>
    {order.items && <ul aria-label="Prendas del pedido" className="my-3 divide-y">
      {order.items.map(item=><li key={item.variant} className="py-2">
        <strong>{item.name}</strong>
        <p>Talla {item.size}{item.color && " · "+item.color}</p>
        <p>{item.quantity} × {money.format(item.unitPrice)} = {money.format(Math.round(item.unitPrice*100)*item.quantity/100)}</p>
      </li>)}
    </ul>}
    <p>Importe de prendas: {money.format(order.total)}</p>
    {order.deliveryFee!=null && <p>Envío {money.format(order.deliveryFee)} · Total {money.format(order.total+order.deliveryFee)}</p>}
    {order.freeDelivery && <p>Delivery gratis · S/ 0.00</p>}
  </>;
}
