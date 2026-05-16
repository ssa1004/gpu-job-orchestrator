package com.example.gwp.orchestrator.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        var id = req.getHeader(HEADER)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
        }
        MDC.put(MDC_KEY, id)
        res.setHeader(HEADER, id)
        try {
            chain.doFilter(req, res)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
    }
}
