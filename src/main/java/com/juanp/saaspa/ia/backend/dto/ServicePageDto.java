package com.juanp.saaspa.ia.backend.dto;

import java.util.List;

/**
 * Respuesta paginada del catalogo interno ({@code GET /api/internal/v1/services}).
 *
 * <p>DTO de cable minimo: solo los campos que consume el agente. La deserializacion ignora
 * propiedades desconocidas, asi que el backend puede anadir campos sin romper este contrato.
 *
 * @param data servicios de la pagina
 * @param total total de servicios activos
 * @param page pagina devuelta
 * @param limit tamano de pagina
 * @param totalPages total de paginas
 */
public record ServicePageDto(List<ServiceDto> data, int total, int page, int limit, int totalPages) {
}
