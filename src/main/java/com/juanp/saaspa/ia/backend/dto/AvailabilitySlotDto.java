package com.juanp.saaspa.ia.backend.dto;

import java.time.OffsetDateTime;

/**
 * Franja de agenda devuelta por la API interna.
 *
 * @param start inicio de la franja, con offset explicito
 * @param end fin de la franja, con offset explicito
 */
public record AvailabilitySlotDto(OffsetDateTime start, OffsetDateTime end) {
}
