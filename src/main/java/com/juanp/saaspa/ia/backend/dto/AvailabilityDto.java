package com.juanp.saaspa.ia.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Disponibilidad de un dia ({@code GET /api/internal/v1/availability?serviceId&date}).
 *
 * <p>El contrato exige que los instantes traigan <strong>offset explicito</strong> y el campo
 * {@code timezone}: el backend calcula las franjas con la zona horaria de su contenedor
 * (ver {@code docs/contracts/t1.0-backend-validation.md}, seccion 6).
 *
 * @param serviceId servicio consultado
 * @param date dia consultado
 * @param timezone zona horaria del negocio (por ejemplo {@code America/Bogota})
 * @param slots franjas disponibles del dia
 */
public record AvailabilityDto(String serviceId, LocalDate date, String timezone, List<AvailabilitySlotDto> slots) {
}
