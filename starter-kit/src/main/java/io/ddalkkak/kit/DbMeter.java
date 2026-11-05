package io.ddalkkak.kit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 고객 앱의 DataSource 를 감싸 요청마다 DB 조회 수 · 커넥션 대기 시간을 {@link RequestMeter} 에 더한다.
 * <p>
 * 클래스 프록시라 HikariDataSource 로 꺼내 쓰는 코드도 그대로 돈다. 상속할 수 없거나 감싸지 못하면 원래 빈을 두고 미측정.
 * SQL 문장 · 값은 보지 않는다 (실제 데이터 값은 보내지 않는다)
 */
class DbMeter implements BeanPostProcessor {

	private static final Log log = LogFactory.getLog(DbMeter.class);

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof DataSource)) {
			return bean;
		}
		// final 클래스(H2 JdbcDataSource) · JDK 프록시는 상속할 수 없다. 인터페이스 프록시로 바꾸면 구체 타입으로 주입받는 빈이
		// 기동에 실패하니 감싸지 않는다 → 미측정
		if (Modifier.isFinal(bean.getClass().getModifiers()) || Proxy.isProxyClass(bean.getClass())) {
			return unmeasured(bean, beanName, "상속할 수 없는 클래스");
		}
		ProxyFactory factory = new ProxyFactory(bean);
		factory.setProxyTargetClass(true);
		factory.addInterface(Metered.class);
		factory.addAdvice((MethodInterceptor) call -> {
			if (!call.getMethod().getName().equals("getConnection")) {
				return call.proceed();
			}
			long start = System.nanoTime();
			Connection connection = (Connection) call.proceed();
			RequestMeter.waited(System.nanoTime() - start);
			return wrap(connection);
		});
		try {
			return factory.getProxy(bean.getClass().getClassLoader());
		}
		catch (RuntimeException e) {
			return unmeasured(bean, beanName, e.toString());
		}
	}

	/**
	 * 감싼 DataSource 표시. 하나라도 이게 없으면 DB 값은 미측정 ({@link RequestMeter}). 프록시 클래스는 고객 DataSource 의
	 * 패키지에 생기니 public 이어야 한다
	 */
	public interface Metered {

	}

	// 재는 것 때문에 고객 앱이 못 뜨면 안 된다 → 원래 빈 그대로
	private static Object unmeasured(Object bean, String beanName, String why) {
		log.info("딸깍: DataSource '" + beanName + "'를 감싸지 못해 DB 조회를 재지 않아요 (" + why + ")");
		return bean;
	}

	private static Connection wrap(Connection connection) {
		return proxy(Connection.class, connection, (p, method, args) -> {
			Object result = invoke(connection, method, args);
			return result instanceof Statement statement ? count(statement) : result;
		});
	}

	private static Statement count(Statement statement) {
		Class<? extends Statement> type = statement instanceof CallableStatement ? CallableStatement.class
				: statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
		return proxy(type, statement, (p, method, args) -> {
			if (method.getName().startsWith("execute")) {
				RequestMeter.query();
			}
			return invoke(statement, method, args);
		});
	}

	// equals · hashCode 는 프록시 자신 기준 (대상에 넘기면 c.equals(c) 가 false — 풀 · Hibernate 가 비교에 쓴다)
	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<? extends T> type, T target, InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(DbMeter.class.getClassLoader(), new Class<?>[] { type }, (p, method, args) -> {
			if (method.getName().equals("equals") && args != null && args.length == 1) {
				return p == args[0];
			}
			if (method.getName().equals("hashCode") && args == null) {
				return System.identityHashCode(p);
			}
			return handler.invoke(p, method, args);
		});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

}
