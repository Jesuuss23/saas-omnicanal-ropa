"use client";
import { useEffect, useState } from "react";

export default function Home() {
  const [mensaje, setMensaje] = useState("Conectando con el backend...");

  useEffect(() => {
    // Usamos la variable de entorno que configuraste en el .env
    fetch(`${process.env.NEXT_PUBLIC_API_URL}/test`)
      .then((res) => res.json())
      .then((data) => setMensaje(data.mensaje))
      .catch((err) => setMensaje("Error de conexión CORS o Backend apagado."));
  }, []);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-900 text-white p-24">
      <h1 className="text-4xl font-bold mb-8">Arquitectura SaaS Lista</h1>
      <div className="p-6 bg-gray-800 rounded-lg shadow-lg border border-gray-700">
        <p className="text-xl text-green-400 font-semibold">{mensaje}</p>
      </div>
    </div>
  );
}