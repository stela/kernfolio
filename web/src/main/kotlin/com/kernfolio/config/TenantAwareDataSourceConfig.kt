package com.kernfolio.config

import com.kernfolio.security.TenantContext
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

@Configuration
class TenantAwareDataSourceConfig {

    @Bean
    fun tenantAwareDataSourcePostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is DataSource && beanName == "dataSource") {
                return TenantAwareDataSource(bean)
            }
            return bean
        }
    }
}

private class TenantAwareDataSource(private val delegate: DataSource) : DataSource {

    override fun getConnection(): Connection =
        applyTenantContext(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        applyTenantContext(delegate.getConnection(username, password))

    private fun applyTenantContext(connection: Connection): Connection {
        val tenantInfo = TenantContext.get() ?: return connection

        connection.createStatement().use { stmt ->
            stmt.execute("SET app.current_user_id = '${tenantInfo.userId}'")
            stmt.execute("SET app.current_user_role = '${tenantInfo.role}'")
        }

        return Proxy.newProxyInstance(
            connection.javaClass.classLoader,
            arrayOf(Connection::class.java),
            TenantConnectionHandler(connection),
        ) as Connection
    }

    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
}

private class TenantConnectionHandler(private val connection: Connection) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        if (method.name == "close") {
            try {
                connection.createStatement().use { stmt ->
                    stmt.execute("RESET app.current_user_id")
                    stmt.execute("RESET app.current_user_role")
                }
            } finally {
                connection.close()
            }
            return null
        }
        return if (args != null) method.invoke(connection, *args) else method.invoke(connection)
    }
}
