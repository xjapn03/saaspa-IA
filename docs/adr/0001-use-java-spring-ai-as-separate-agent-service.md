# ADR 0001: Java + Spring AI como servicio separado (cerebro)

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

Kamerinos SPA ya está en producción con backend **NestJS 11 + Prisma** y frontend **Next.js 16**.
Se necesita añadir un agente conversacional con IA (tool-calling, RAG, memoria, evaluación). La
documentación previa proponía tres stacks contradictorios (Spring AI, Python y Node.js).

## Decisión

Implementar el motor de IA como un **servicio separado en Java 21 + Spring Boot 4 + Spring AI 2.0**,
contenido en este repo (`saaspa-IA`), que se integra al producto por HTTP. El backend NestJS
permanece como sistema de registro.

Se descartan: (a) implementar el agente dentro de NestJS, y (b) un microservicio Python. Se elige
Spring AI por sus abstracciones portables (ChatClient, Advisors, Chat Memory, RAG/ETL, Tool Calling,
evaluación) y porque es el stack que el roadmap original ya asumía.

## Consecuencias

- **Positivas:** separación clara de responsabilidades; el cerebro no toca la BD ni duplica lógica;
  Spring AI aporta tool-calling, RAG y evaluación con un solo framework.
- **Negativas:** segundo lenguaje y segundo despliegue; latencia extra por las llamadas HTTP a
  NestJS para ejecutar herramientas; hay que versionar el contrato entre ambos servicios.
