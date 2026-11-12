package io.ddalkkak.kit;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 고객 앱의 컨트롤러 매핑에서 API 목록을 만든다 (약속 ① ApiEndpoint). 모양만 읽고 실제 값은 없다.
 * <p>
 * 로그인 필요 · 역할은 메서드 · 클래스의 @PreAuthorize · @Secured · @RolesAllowed 를 먼저 보고, 없으면 Spring Security URL
 * 규칙(requestMatchers)에 익명으로 대 본다({@link SecurityRules}, 역할은 null) — docs/starter-kit.md §2
 */
public class ApiCollector {

	private static final DefaultParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

	private static final String SECURITY = "org.springframework.security.web.access.WebInvocationPrivilegeEvaluator";

	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

	private static final Pattern ROLE =Pattern.compile("(?:ROLE_|hasA?n?y?Role\\(\\s*')([A-Za-z0-9_]+)");

	private final ApplicationContext context;

	private final RequestMeter meter;

	ApiCollector(ApplicationContext context, RequestMeter meter) {
		this.context = context;
		this.meter = meter;
	}

	public record Param(String name, String in, String type, boolean required) {
	}

	public record Endpoint(String method, String path, List<Param> params, boolean authRequired, String role,
			String handler, long calls) {

		public String api() {
			return method + " " + path;
		}

	}

	public List<Endpoint> collect() {
		RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
				RequestMappingHandlerMapping.class);
		List<Endpoint> out = new ArrayList<>();
		SecurityRules rules = securityRules();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = e.getValue();
			// /error 같은 Spring 자체 핸들러는 고객 API 가 아니다
			if (handler.getBeanType().getName().startsWith("org.springframework.")) {
				continue;
			}
			List<Param> params = params(handler);
			Auth annotated = auth(handler);
			String name = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
			for (String method : methods(e.getKey())) {
				for (String path : e.getKey().getPatternValues()) {
					Auth auth = annotated != null ? annotated : new Auth(loginRequired(rules, method, path), null);
					out.add(new Endpoint(method, path, params, auth.required, auth.role, name,
							meter.calls(method + " " + path)));
				}
			}
		}
		out.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
		return out;
	}

	private SecurityRules securityRules() {
		if (!ClassUtils.isPresent(SECURITY, context.getClassLoader())) {
			return null;
		}
		try {
			return SecurityRules.of(context);
		}
		catch (RuntimeException | LinkageError ex) {
			return null;
		}
	}

	// 규칙에 대 보다 실패하면 지금까지처럼 false — 고객 앱에 영향 없게
	private static boolean loginRequired(SecurityRules rules, String method, String path) {
		if (rules == null) {
			return false;
		}
		try {
			return rules.loginRequired(method, path);
		}
		catch (RuntimeException | LinkageError ex) {
			return false;
		}
	}

	// 메서드를 안 적은 매핑(@RequestMapping 만)은 GET 으로 본다. 약속 ① 밖(HEAD · OPTIONS · TRACE)은 뺀다
	private static List<String> methods(RequestMappingInfo info) {
		var methods = info.getMethodsCondition().getMethods();
		return methods.isEmpty() ? List.of("GET")
				: methods.stream().map(RequestMethod::name).filter(METHODS::contains).sorted().toList();
	}

	private static List<Param> params(HandlerMethod handler) {
		List<Param> out = new ArrayList<>();
		for (MethodParameter p : handler.getMethodParameters()) {
			p.initParameterNameDiscovery(names);
			Class<?> type = p.getParameterType();
			boolean optional = type == Optional.class;
			String json = jsonType(optional ? p.nested().getNestedParameterType() : type);
			PathVariable path = p.getParameterAnnotation(PathVariable.class);
			RequestParam query = p.getParameterAnnotation(RequestParam.class);
			RequestHeader header = p.getParameterAnnotation(RequestHeader.class);
			CookieValue cookie = p.getParameterAnnotation(CookieValue.class);
			RequestBody body = p.getParameterAnnotation(RequestBody.class);
			if (path != null) {
				out.add(new Param(name(path.name(), p), "path", json, path.required() && !optional));
			}
			else if (query != null) {
				out.add(new Param(name(query.name(), p), "query", json, required(query.required(), query.defaultValue(), optional)));
			}
			else if (header != null) {
				out.add(new Param(name(header.name(), p), "header", json, required(header.required(), header.defaultValue(), optional)));
			}
			else if (cookie != null) {
				// 약속 ①: 쿠키는 cookie (-240 전에는 header 로 묶었다)
				out.add(new Param(name(cookie.name(), p), "cookie", json, required(cookie.required(), cookie.defaultValue(), optional)));
			}
			else if (body != null) {
				out.add(new Param("body", "body", json, body.required() && !optional));
			}
			else if (BeanUtils.isSimpleValueType(type) && !ClassUtils.isAssignable(jakarta.servlet.ServletRequest.class, type)) {
				// 애너테이션 없는 단순 값은 Spring 이 query 로 받는다
				out.add(new Param(p.getParameterName(), "query", json, false));
			}
			// 그 밖(HttpServletRequest · Principal · @ModelAttribute 객체 · Pageable)은 지금은 뺀다 — §2
		}
		return out;
	}

	private static String name(String annotated, MethodParameter p) {
		return annotated.isEmpty() ? p.getParameterName() : annotated;
	}

	private static boolean required(boolean required, String defaultValue, boolean optional) {
		return required && ValueConstants.DEFAULT_NONE.equals(defaultValue) && !optional;
	}

	/** 약속 ①: string · integer · number · boolean · object · array */
	static String jsonType(Class<?> type) {
		Class<?> t = ClassUtils.resolvePrimitiveIfNecessary(type);
		if (t == String.class || t == Character.class || t.isEnum() || t == UUID.class
				|| Temporal.class.isAssignableFrom(t) || t.getName().endsWith("MultipartFile")) {
			return "string";
		}
		if (t == Integer.class || t == Long.class || t == Short.class || t == Byte.class || t == BigInteger.class) {
			return "integer";
		}
		if (t == Double.class || t == Float.class || t == BigDecimal.class) {
			return "number";
		}
		if (t == Boolean.class) {
			return "boolean";
		}
		if (t.isArray() || Collection.class.isAssignableFrom(t)) {
			return "array";
		}
		return "object";
	}

	private record Auth(boolean required, String role) {
	}

	/** 애너테이션이 없으면 null (그때는 URL 규칙을 본다) */
	private static Auth auth(HandlerMethod handler) {
		Auth onMethod = auth(handler.getMethod().getAnnotations());
		return onMethod != null ? onMethod : auth(handler.getBeanType().getAnnotations());
	}

	// spring-security 를 컴파일에 넣지 않으려고 이름으로 본다
	private static Auth auth(Annotation[] annotations) {
		for (Annotation a : annotations) {
			String name = a.annotationType().getSimpleName();
			if (name.equals("PermitAll")) {
				return new Auth(false, null);
			}
			if (name.equals("PreAuthorize") || name.equals("Secured") || name.equals("RolesAllowed")) {
				Object value = AnnotationUtils.getValue(a);
				String text = value instanceof String[] values ? String.join(",", values) : String.valueOf(value);
				Matcher m = ROLE.matcher(text);
				String role = m.find() ? m.group(1) : name.equals("RolesAllowed") && value instanceof String[] v && v.length > 0 ? v[0] : null;
				return new Auth(true, role);
			}
		}
		return null;
	}

}
