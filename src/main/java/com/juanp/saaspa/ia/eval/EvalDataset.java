package com.juanp.saaspa.ia.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dataset de evaluacion del agente CLIENTAS (T1.8): lee un fichero JSONL (un caso por linea) desde
 * {@code eval/}. Las lineas vacias y las que empiezan por {@code #} se ignoran.
 *
 * <p>El dataset crece con cada cambio de comportamiento del agente (R15). No se ejecuta en el build
 * normal: la evaluacion con un LLM real corre aparte (R14).
 */
public final class EvalDataset {

	/** Los campos primitivos ausentes (por ejemplo {@code gap}) valen su valor por defecto. */
	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();

	private final List<EvalCase> cases;

	private EvalDataset(List<EvalCase> cases) {
		this.cases = List.copyOf(cases);
	}

	/**
	 * @param path ruta del fichero JSONL
	 * @return dataset cargado
	 */
	public static EvalDataset load(Path path) {
		try {
			List<EvalCase> cases = Files.readAllLines(path).stream()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.map(line -> MAPPER.readValue(line, EvalCase.class))
					.toList();
			return new EvalDataset(cases);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("No se pudo leer el dataset de evaluacion: " + path, ex);
		}
	}

	/** @return los casos del dataset */
	public List<EvalCase> cases() {
		return this.cases;
	}
}