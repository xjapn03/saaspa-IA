package com.juanp.saaspa.ia.tools;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;

import com.juanp.saaspa.ia.backend.BackendClient;
import com.juanp.saaspa.ia.backend.BackendException;
import com.juanp.saaspa.ia.backend.dto.AvailabilityDto;
import com.juanp.saaspa.ia.backend.dto.AvailabilitySlotDto;
import com.juanp.saaspa.ia.backend.dto.ServiceDto;
import com.juanp.saaspa.ia.backend.dto.ServicePageDto;
import com.juanp.saaspa.ia.config.TenantProperties;
import com.juanp.saaspa.ia.security.CurrentTurnToken;
import com.juanp.saaspa.ia.security.TurnToken;

/**
 * Herramientas de lectura del agente CLIENTAS (Fase 1): catalogo y disponibilidad.
 *
 * <p>Son deliberadamente delgadas (AGENTS.md, seccion 8): validan la entrada, llaman a la API
 * interna del backend y devuelven datos ya formateados. Principios que respetan:
 * <ul>
 *   <li>los precios y las franjas <strong>solo</strong> salen de estas herramientas, nunca del modelo
 *       ni de RAG (regla R11);
 *   <li>el tenant y el turn token se toman del contexto de seguridad, no de los argumentos de la
 *       herramienta (regla R1);
 *   <li>si el backend falla o no hay datos, la herramienta devuelve {@code ok=false} con un mensaje
 *       para que el agente lo diga y ofrezca handoff, en lugar de inventar (regla R11);
 *   <li>nunca se registran datos personales ni el token (regla R8).
 * </ul>
 *
 * <p>Los importes se devuelven ya formateados en pesos colombianos para que el modelo los copie tal
 * cual y no reformatee cifras por su cuenta.
 */
public class CustomerTools {

	private static final Logger log = LoggerFactory.getLogger(CustomerTools.class);

	static final String SERVICES_PATH = "/api/internal/v1/services";

	static final String AVAILABILITY_PATH = "/api/internal/v1/availability";

	static final int CATALOGUE_PAGE_SIZE = 50;

	private static final Locale LOCALE_CO = Locale.forLanguageTag("es-CO");

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final DateTimeFormatter LOCAL_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private final BackendClient backendClient;

	private final TenantProperties tenantProperties;

	private final Clock clock;

	public CustomerTools(BackendClient backendClient, TenantProperties tenantProperties) {
		this(backendClient, tenantProperties, Clock.systemDefaultZone());
	}

	CustomerTools(BackendClient backendClient, TenantProperties tenantProperties, Clock clock) {
		this.backendClient = backendClient;
		this.tenantProperties = tenantProperties;
		this.clock = clock;
	}

	/**
	 * Lista el catalogo de servicios activos.
	 *
	 * @param soloDestacados si se piden solo los servicios destacados (puede ser {@code null})
	 * @return catalogo con precios formateados en COP, o {@code ok=false} si no se pudo consultar
	 */
	@Tool(name = "listarServicios",
			description = """
					Lista los servicios activos del catalogo de Kamerinos con su precio en pesos colombianos y su
					duracion en minutos. Usar cuando la clienta pregunte que servicios hay, cuanto cuesta alguno o
					cuanto dura. Devuelve tambien el id y el slug de cada servicio para consultarlos despues.""")
	public ServiceListResult listarServicios(
			@ToolParam(description = "true para devolver solo los servicios destacados", required = false) Boolean soloDestacados) {
		try {
			Map<String, Object> query = new LinkedHashMap<>();
			query.put("page", 1);
			query.put("limit", CATALOGUE_PAGE_SIZE);
			if (Boolean.TRUE.equals(soloDestacados)) {
				query.put("featured", true);
			}
			ServicePageDto page = this.backendClient.get(SERVICES_PATH, query, turnToken(), ServicePageDto.class);
			List<ServiceSummary> services = page.data() == null ? List.of()
					: page.data().stream().map(CustomerTools::toSummary).toList();
			return new ServiceListResult(true, null, page.total(), page.total() > services.size(), services);
		}
		catch (BackendException ex) {
			log.warn("listarServicios fallo: {}", ex.getClass().getSimpleName());
			return new ServiceListResult(false, failureMessage(ex), 0, false, List.of());
		}
		catch (AuthenticationException ex) {
			log.warn("listarServicios sin turn token verificado");
			return new ServiceListResult(false, NO_TURN_MESSAGE, 0, false, List.of());
		}
	}

	/**
	 * Detalle de un servicio del catalogo.
	 *
	 * @param servicio id o slug tal como lo devolvio {@link #listarServicios(Boolean)}
	 * @return detalle del servicio, o {@code ok=false} si no existe o no se pudo consultar
	 */
	@Tool(name = "consultarServicio",
			description = """
					Devuelve el detalle de un servicio del catalogo (precio, duracion y descripcion).
					Usar el id o el slug que devolvio listarServicios; no inventar identificadores.""")
	public ServiceDetailResult consultarServicio(
			@ToolParam(description = "id o slug del servicio devuelto por listarServicios") String servicio) {
		if (!StringUtils.hasText(servicio)) {
			return new ServiceDetailResult(false, "Falta el servicio a consultar; usa listarServicios primero", null,
					null);
		}
		try {
			ServiceDto service = this.backendClient.get(SERVICES_PATH + "/{servicio}", Map.of("servicio", servicio.trim()),
					Map.of(), turnToken(), ServiceDto.class);
			return new ServiceDetailResult(true, null, toSummary(service), service.description());
		}
		catch (BackendException ex) {
			log.warn("consultarServicio fallo: {}", ex.getClass().getSimpleName());
			String message = ex.status().orElse(0) == 404
					? "No existe un servicio con ese id o slug; usa listarServicios para obtener los validos"
					: failureMessage(ex);
			return new ServiceDetailResult(false, message, null, null);
		}
		catch (AuthenticationException ex) {
			log.warn("consultarServicio sin turn token verificado");
			return new ServiceDetailResult(false, NO_TURN_MESSAGE, null, null);
		}
	}

	/**
	 * Franjas libres de agenda para un servicio y un dia.
	 *
	 * @param servicio id o slug del servicio
	 * @param fecha dia en formato {@code AAAA-MM-DD}
	 * @return franjas ordenadas con hora local, o {@code ok=false} si la fecha es invalida/pasada o si
	 *     no se pudo consultar
	 */
	@Tool(name = "consultarDisponibilidad",
			description = """
					Consulta las franjas libres de agenda de un servicio para un dia concreto. La fecha va en
					formato AAAA-MM-DD y debe ser hoy o posterior. Devuelve las horas libres en la zona horaria de la
					sede. No inventar horarios: si no devuelve franjas, ese dia no hay disponibilidad.""")
	public AvailabilityResult consultarDisponibilidad(
			@ToolParam(description = "id o slug del servicio") String servicio,
			@ToolParam(description = "dia a consultar en formato AAAA-MM-DD") String fecha) {
		if (!StringUtils.hasText(servicio)) {
			return availabilityFailure("Falta el servicio a consultar; usa listarServicios primero");
		}
		LocalDate day;
		try {
			day = LocalDate.parse(fecha == null ? "" : fecha.trim(), DATE_FORMAT);
		}
		catch (DateTimeParseException ex) {
			return availabilityFailure("La fecha debe venir en formato AAAA-MM-DD; hoy es " + today());
		}
		if (day.isBefore(today())) {
			return availabilityFailure("Esa fecha ya paso; hoy es " + today());
		}
		try {
			AvailabilityDto availability = this.backendClient.get(AVAILABILITY_PATH,
					Map.of("serviceId", servicio.trim(), "date", day.toString()), turnToken(), AvailabilityDto.class);
			ZoneId zone = zone(availability.timezone());
			List<TimeSlot> slots = availability.slots() == null ? List.of()
					: availability.slots().stream().map(slot -> toTimeSlot(slot, zone)).toList();
			String message = slots.isEmpty() ? "No hay franjas disponibles ese dia" : null;
			return new AvailabilityResult(true, message, availability.serviceId(),
					availability.date() == null ? day.toString() : availability.date().toString(), zone.getId(), slots);
		}
		catch (BackendException ex) {
			log.warn("consultarDisponibilidad fallo: {}", ex.getClass().getSimpleName());
			return availabilityFailure(failureMessage(ex));
		}
		catch (AuthenticationException ex) {
			log.warn("consultarDisponibilidad sin turn token verificado");
			return availabilityFailure(NO_TURN_MESSAGE);
		}
	}

	private static final String NO_TURN_MESSAGE = "No se pudo identificar el turno; pide a la persona que reintente";

	private String turnToken() {
		TurnToken turnToken = CurrentTurnToken.require();
		return turnToken.rawToken();
	}

	private LocalDate today() {
		return LocalDate.now(this.clock.withZone(this.tenantProperties.zoneId()));
	}

	private ZoneId zone(String timezone) {
		if (StringUtils.hasText(timezone)) {
			try {
				return ZoneId.of(timezone);
			}
			catch (DateTimeException ex) {
				log.warn("Zona horaria desconocida en la respuesta de disponibilidad");
			}
		}
		return this.tenantProperties.zoneId();
	}

	private static AvailabilityResult availabilityFailure(String message) {
		return new AvailabilityResult(false, message, null, null, null, List.of());
	}

	private static String failureMessage(BackendException ex) {
		int status = ex.status().orElse(0);
		if (status == 0) {
			return "El sistema de agenda no respondio en este momento";
		}
		if (status == 401 || status == 403) {
			return "El sistema de agenda rechazo la consulta";
		}
		if (status == 404) {
			return "No existe ese recurso en el sistema de agenda";
		}
		return "El sistema de agenda no pudo responder la consulta";
	}

	private static ServiceSummary toSummary(ServiceDto service) {
		return new ServiceSummary(service.id(), service.slug(), service.name(), service.duration(),
				formatCop(service.price()), formatCop(service.compareAtPrice()),
				service.category() == null ? null : service.category().name());
	}

	private static TimeSlot toTimeSlot(AvailabilitySlotDto slot, ZoneId zone) {
		return new TimeSlot(slot.start().toString(), slot.end().toString(),
				slot.start().atZoneSameInstant(zone).format(LOCAL_TIME_FORMAT));
	}

	/**
	 * Formatea un importe en pesos colombianos para que el modelo lo copie tal cual.
	 *
	 * @param amount importe en COP
	 * @return importe formateado (por ejemplo {@code $ 120.000}) o {@code null}
	 */
	static String formatCop(Double amount) {
		if (amount == null) {
			return null;
		}
		NumberFormat format = NumberFormat.getIntegerInstance(LOCALE_CO);
		return "$ " + format.format(amount);
	}

	/**
	 * Resultado de {@link #listarServicios(Boolean)}.
	 *
	 * @param ok si se pudo consultar el catalogo
	 * @param message motivo cuando {@code ok=false}
	 * @param total servicios activos en total
	 * @param truncated {@code true} si hay mas servicios de los devueltos
	 * @param services servicios devueltos
	 */
	public record ServiceListResult(boolean ok, String message, int total, boolean truncated,
			List<ServiceSummary> services) {
	}

	/**
	 * Servicio resumido para el modelo.
	 *
	 * @param id identificador para llamar a las otras herramientas
	 * @param slug identificador legible
	 * @param name nombre visible
	 * @param durationMinutes duracion en minutos
	 * @param priceCop precio formateado en COP (copiar tal cual)
	 * @param compareAtPriceCop precio anterior formateado en COP, si esta en promocion
	 * @param category nombre de la categoria, si tiene
	 */
	public record ServiceSummary(String id, String slug, String name, Integer durationMinutes, String priceCop,
			String compareAtPriceCop, String category) {
	}

	/**
	 * Resultado de {@link #consultarServicio(String)}.
	 *
	 * @param ok si se encontro el servicio
	 * @param message motivo cuando {@code ok=false}
	 * @param service servicio encontrado
	 * @param description descripcion comercial
	 */
	public record ServiceDetailResult(boolean ok, String message, ServiceSummary service, String description) {
	}

	/**
	 * Resultado de {@link #consultarDisponibilidad(String, String)}.
	 *
	 * @param ok si se pudo consultar la agenda
	 * @param message motivo cuando {@code ok=false} o aviso cuando no hay franjas
	 * @param serviceId servicio consultado
	 * @param date dia consultado
	 * @param timezone zona horaria de las horas devueltas
	 * @param slots franjas libres
	 */
	public record AvailabilityResult(boolean ok, String message, String serviceId, String date, String timezone,
			List<TimeSlot> slots) {
	}

	/**
	 * Franja libre para el modelo.
	 *
	 * @param start inicio en ISO-8601 con offset
	 * @param end fin en ISO-8601 con offset
	 * @param localStartTime hora local de inicio (por ejemplo {@code 08:00}) para ofrecerla tal cual
	 */
	public record TimeSlot(String start, String end, String localStartTime) {
	}

}
