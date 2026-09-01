package com.axonbase.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Fábrica de proxies JDBC. Implementa dinamicamente métodos que não são
 * suportados, retornando valores padrão (null, 0, false, void) sem exigir
 * que cada implementação declare centenas de métodos abstratos.
 */
public final class JdbcProxy {

    private JdbcProxy() {}

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> iface, Object delegate) {
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{iface},
            new Handler(delegate));
    }

    private record Handler(Object delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                // Tenta delegar para o objeto real
                Method real = delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
                return real.invoke(delegate, args);
            } catch (NoSuchMethodException e) {
                // Método não implementado: retorna valor padrão seguro
                Class<?> ret = method.getReturnType();
                if (ret == void.class) return null;
                if (ret == boolean.class) return false;
                if (ret == int.class || ret == long.class || ret == short.class || ret == byte.class) return 0;
                if (ret == float.class || ret == double.class) return 0.0;
                if (ret == String.class) return "";
                if (ret == Object.class) return null;
                return null;
            }
        }
    }
}