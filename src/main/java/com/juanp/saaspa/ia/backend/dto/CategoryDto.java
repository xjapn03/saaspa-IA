package com.juanp.saaspa.ia.backend.dto;

/**
 * Categoria de un servicio ({@code categoryRel} en la respuesta del catalogo).
 *
 * @param id identificador de la categoria
 * @param name nombre visible
 * @param slug identificador legible
 */
public record CategoryDto(String id, String name, String slug) {
}
