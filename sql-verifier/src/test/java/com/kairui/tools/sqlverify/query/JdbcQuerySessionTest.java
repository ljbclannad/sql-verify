package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 驱动初始化在返回会话前失败时，也必须释放已创建连接。 */
class JdbcQuerySessionTest {
    @Test void uncheckedMetadataFailureClosesConnectionOnCreatingThread() throws Exception {
        for (Throwable cause : List.of(new IllegalStateException("驱动元数据异常"), new AssertionError("驱动元数据错误"))) {
            AtomicInteger closed = new AtomicInteger();
            long owner = Thread.currentThread().getId();
            Driver driver = new Driver() {
                @Override public Connection connect(String url, Properties properties) throws SQLException {
                    if (!url.startsWith("jdbc:session-failure:")) return null;
                    Connection delegate = super.connect("jdbc:h2:mem:" + UUID.randomUUID(), properties);
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                if (method.getName().equals("getMetaData")) throw cause;
                                if (method.getName().equals("close")) {
                                    assertEquals(owner, Thread.currentThread().getId());
                                    closed.incrementAndGet();
                                }
                                try { return method.invoke(delegate, args); }
                                catch (InvocationTargetException exception) { throw exception.getCause(); }
                            });
                }
            };
            DriverManager.registerDriver(driver);
            try {
                VerificationConfig.DataSource config = new VerificationConfig.DataSource("source", "org.h2.Driver",
                        "jdbc:session-failure:test", "sa", "source-secret", 1000, 5000);
                Throwable failure = assertThrows(cause.getClass(), () -> JdbcQuerySession.open(config, true));
                assertSame(cause, failure);
                assertEquals(1, closed.get());
            } finally {
                DriverManager.deregisterDriver(driver);
            }
        }
    }
}
