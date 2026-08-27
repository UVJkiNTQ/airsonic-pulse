package org.airsonic.player.spring;

import org.airsonic.player.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoggingExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingExceptionResolver.class);

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request, HttpServletResponse response, Object o, Exception e
    ) {
        // This happens often and outside of the control of the server, so
        // we catch Tomcat/Jetty "connection aborted by client" exceptions
        // and display a short error message.
        boolean shouldCatch = isClientAbortException(e);
        if (shouldCatch) {
            LOG.info("{}: Client unexpectedly closed connection while loading {}", request.getRemoteAddr(), Util.getAnonymizedURLForRequest(request));
            return null;
        }

        // Display a full stack trace in all other cases
        LOG.error("{}: An exception occurred while loading {}", request.getRemoteAddr(), Util.getAnonymizedURLForRequest(request), e);
        return null;
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    /**
     * Check if the exception or any of its causes is a "client abort" exception.
     *
     * @param e The exception to check
     * @return True if the exception or any of its causes is a "client abort" exception
     */
    boolean isClientAbortException(Throwable e) {
        if (e == null) {
            return false;
        }
        if (Util.isInstanceOfClassName(e, "org.apache.catalina.connector.ClientAbortException")) {
            return true;
        }
        // Spring MVC async requests whose response is no longer usable (client disconnected / timed
        // out while the handler was streaming, e.g. cover art from a slow network mount). This is an
        // expected condition, not a server error — keep it off the ERROR log.
        if (Util.isInstanceOfClassName(e, "org.springframework.web.context.request.async.AsyncRequestNotUsableException")) {
            return true;
        }
        return isClientAbortException(e.getCause());
    }
}
