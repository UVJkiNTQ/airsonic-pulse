/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.airsonic.player.spring;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in which exceptions are treated as benign "client closed the connection" conditions
 * (logged quietly) rather than server errors (full ERROR stack). See the getCoverArt
 * AsyncRequestNotUsableException flood from slow network mounts.
 */
class LoggingExceptionResolverTest {

    private final LoggingExceptionResolver resolver = new LoggingExceptionResolver();

    @Test
    void clientAbortExceptionIsRecognized() {
        assertTrue(resolver.isClientAbortException(new ClientAbortException("aborted")));
    }

    @Test
    void asyncRequestNotUsableExceptionIsRecognized() {
        assertTrue(resolver.isClientAbortException(new AsyncRequestNotUsableException("Response not usable")));
    }

    @Test
    void asyncRequestNotUsableAsCauseIsRecognized() {
        RuntimeException wrapper = new RuntimeException(new AsyncRequestNotUsableException("Response not usable"));
        assertTrue(resolver.isClientAbortException(wrapper));
    }

    @Test
    void unrelatedExceptionIsNotClientAbort() {
        assertFalse(resolver.isClientAbortException(new IllegalStateException("nope")));
        assertFalse(resolver.isClientAbortException(null));
    }
}
