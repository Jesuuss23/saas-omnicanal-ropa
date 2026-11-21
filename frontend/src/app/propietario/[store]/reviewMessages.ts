type Signal = {
  stage:"IN_PROGRESS"|"LOST"; label:string; quote:string;
};

const withdrawal = /\b(?:ya no (?:lo |la |me )?(?:quiero|interesa)|no me interesa|cancela mi pedido|quiero cancelar mi pedido)\b/;
const interest = /\b(?:quiero (?:comprar|pedir|reservar)|me interesa|tienen (?:la )?talla|hay (?:en )?talla|cuanto (?:cuesta|sale)|cual es el precio)\b/;

// Messages arrive in chronological order. Prefer the latest explicit signal.
export function reviewMessages(messages:ReadonlyArray<{text:string}>):Signal|null {
  for (let i=messages.length-1;i>=0;i--) {
    const phrases=messages[i].text.split(/(?:[.!?;\n]+|,\s*pero\s+|\s+pero\s+)/i);
    for (let j=phrases.length-1;j>=0;j--) {
      const quote=phrases[j].trim();
      const normalized=quote.normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase();
      // A question about cancellation is not a request to cancel.
      if (withdrawal.test(normalized) && !/[?¿]/.test(messages[i].text) &&
          !/\b(?:si|nunca dije|no (?:dije|quiero cancelar mi pedido si))\b/.test(normalized)) {
        return {stage:"LOST",label:"Posible desistimiento del cliente.",quote};
      }
      if (interest.test(normalized) && !/\b(?:no|nunca|tampoco)\b/.test(normalized)) {
        return {stage:"IN_PROGRESS",label:"Consulta de producto o intención de compra.",quote};
      }
    }
  }
  return null;
}
