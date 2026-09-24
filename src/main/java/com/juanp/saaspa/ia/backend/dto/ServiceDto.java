package com.juanp.saaspa.ia.backend.dto;

/**
 * Servicio del catalogo tal como lo devuelve la API interna
 * ({@code GET /api/internal/v1/services} y {@code /services/{id|slug}}).
 *
 * <p>DTO de cable minimo: solo los campos que necesitan las herramientas. Los precios llegan como
 * numero (el backend convierte el Decimal de Prisma).
 *
 * @param id identificador del servicio
 * @param name nombre visible
 * @param slug identificador legible
 * @param description descripcion comercial (puede ser nula)
 * @param price precio en COP
 * @param compareAtPrice precio anterior en COP, si esta en promocion
 * @param duration duracion en minutos
 * @param isActive si el servicio esta activo
 * @param isFeatured si esta destacado
 * @param category categoria del servicio, si tiene
 * @param mainImage imagen principal, si tiene
 */
public record ServiceDto(
		String id,
		String name,
		String slug,
		String description,
		Double price,
		Double compareAtPrice,
		Integer duration,
		Boolean isActive,
		Boolean isFeatured,
		CategoryDto category,
		String mainImage) {
}
