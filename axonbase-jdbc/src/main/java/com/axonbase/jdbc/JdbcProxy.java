package com.axonbase.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Fábrica de proxies JDBC. Métodos não implementados no delegate
 * lançam {@link SQLFeatureNotSupportedException}.
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
                Method real = delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
                return real.invoke(delegate, args);
            } catch (NoSuchMethodException e) {
                throw new SQLFeatureNotSupportedException(
                    "method " + method.getName() + " not supported by this driver");
            }
        }
    }
}