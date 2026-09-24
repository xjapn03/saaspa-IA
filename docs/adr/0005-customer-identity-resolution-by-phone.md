# ADR 0005: Resolución de identidad del cliente por teléfono

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

Una clienta de WhatsApp se identifica por `waId`/teléfono y puede no tener cuenta en la web. El flujo
de reserva actual exige un `userId` (Booking.userId es obligatorio), y de él dependen pagos, Google
Calendar, emails y Meta CAPI.

## Decisión

Mapear `waId`/teléfono → `User` (lookup por teléfono; si no existe, auto-creación con rol `CLIENTE`).
La cita se crea como ese usuario en `PENDIENTE_PAGO` y se devuelve el deep-link de pago del abono,
reutilizando el flujo Wompi existente.

## Consecuencias

- **Positivas:** reutiliza TODO el pipeline de reserva/pago/calendario/CAPI sin cambios; una sola
  identidad por cliente en ambos canales.
- **Negativas:** hay que definir la política de auto-creación (verificación, datos mínimos,
  consentimiento) y hacer único/consultable el teléfono para este fin.

## Addendum (2026-09-23)

La resolución de identidad por teléfono **solo aplica a WhatsApp**, donde el número lo verifica
Meta (`waId`). En el **chat web anónimo nunca** se resuelve identidad a partir de un teléfono que
la persona teclea: un número no verificado no identifica a nadie.

La auto-creación de usuarios (cuando aplique) debe definir: consentimiento explícito y datos
mínimos; su efecto en Meta CAPI (la atribución `ctwa_clid` solo cuando corresponda); y qué ocurre
si dos `waId` distintos comparten teléfono.
