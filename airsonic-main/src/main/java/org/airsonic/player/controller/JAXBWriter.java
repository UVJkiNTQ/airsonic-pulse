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

 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.MediaType;
import org.airsonic.player.controller.SubsonicRESTController.APIException;
import org.airsonic.player.service.VersionService;
import org.airsonic.player.util.StringUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.subsonic.restapi.Error;
import org.subsonic.restapi.ObjectFactory;
import org.subsonic.restapi.Response;
import org.subsonic.restapi.ResponseStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.time.Instant;
import java.util.GregorianCalendar;
import java.util.Map;
import java.util.Map.Entry;

import static org.airsonic.player.util.XMLUtil.createSAXBuilder;
import static org.springframework.web.bind.ServletRequestUtils.getStringParameter;

/**
 * @author Sindre Mehus
 * @version $Id$
 */
@Component
public class JAXBWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JAXBWriter.class);

    private final jakarta.xml.bind.JAXBContext jaxbContext;
    private final DatatypeFactory datatypeFactory;
    private static final String restProtocolVersion = parseRESTProtocolVersion();

    private static final String SERVER_TYPE = "airsonic-pulse";
    private final VersionService versionService;

    @Autowired
    public JAXBWriter(VersionService versionService) {
        this.versionService = versionService;
        Map<String, Object> properties = Map.of(JAXBContext.JAXB_CONTEXT_FACTORY, "org.eclipse.persistence.jaxb.JAXBContextFactory");
        Class<?>[] classes = {Response.class};
        try {
            jaxbContext = JAXBContext.newInstance(classes, properties);
            datatypeFactory = DatatypeFactory.newInstance();
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    private Marshaller createXmlMarshaller() {
        Marshaller marshaller = null;
        try {
            marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StringUtil.ENCODING_UTF8);
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            return marshaller;
        } catch (JAXBException e) {
            throw new RuntimeException(e);
        }
    }

    private Marshaller createJsonMarshaller() {
        try {
            Marshaller marshaller;
            marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StringUtil.ENCODING_UTF8);
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
            marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, true);
            return marshaller;
        } catch (JAXBException e) {
            throw new RuntimeException(e);
        }
    }

    private static String parseRESTProtocolVersion() {
        try (InputStream in = StringUtil.class.getResourceAsStream("/subsonic-rest-api.xsd")) {
            Document document = createSAXBuilder().build(in);
            Attribute version = document.getRootElement().getAttribute("version");
            return version.getValue();
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    public static String getRestProtocolVersion() {
        return restProtocolVersion;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * OpenSubsonic specifies {@code openSubsonicExtensions} as a flat JSON array — a documented
     * JSON/XML asymmetry (XML keeps the {@code <openSubsonicExtensions>} wrapper element). MOXy
     * serializes the JAXB wrapper object as {@code { "openSubsonicExtension": [...] }}, which
     * strict clients such as Symfonium reject ({@code Expected BEGIN_ARRAY but was BEGIN_OBJECT
     * at path $.subsonic-response.openSubsonicExtensions}). Unwrap to the flat array for JSON/
     * JSONP when the field is present; all other endpoints keep the original output untouched.
     */
    static String unwrapOpenSubsonicExtensions(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode rootNode)) {
                return json;
            }
            JsonNode response = rootNode.get("subsonic-response");
            if (!(response instanceof ObjectNode responseNode)) {
                return json;
            }
            JsonNode extensions = responseNode.get("openSubsonicExtensions");
            if (extensions == null || !extensions.isObject()) {
                return json;
            }
            JsonNode list = extensions.get("openSubsonicExtension");
            if (list == null || !list.isArray()) {
                return json;
            }
            responseNode.set("openSubsonicExtensions", list);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        } catch (IOException x) {
            LOG.warn("Failed to unwrap openSubsonicExtensions in JSON response", x);
            return json;
        }
    }

    public Response createResponse(boolean ok) {
        Response response = new ObjectFactory().createResponse();
        response.setStatus(ok ? ResponseStatus.OK : ResponseStatus.FAILED);
        response.setVersion(restProtocolVersion);
        response.setType(SERVER_TYPE);
        response.setOpenSubsonic(true);
        response.setServerVersion(versionService.getLocalVersion().toString());
        return response;
    }

    public void writeResponse(HttpServletRequest request, HttpServletResponse httpResponse, Response jaxbResponse) {
        Entry<String, String> serializedResp = serializeForType(request, jaxbResponse);

        httpResponse.setCharacterEncoding(StringUtil.ENCODING_UTF8);
        httpResponse.setContentType(serializedResp.getKey());

        try {
            httpResponse.getWriter().append(serializedResp.getValue());
        } catch (IOException x) {
            LOG.error("Failed to marshal JAXB", x);
            throw new RuntimeException(x);
        }
    }

    public void writeErrorResponse(HttpServletRequest request, HttpServletResponse response,
            SubsonicRESTController.ErrorCode code, String message) {
        Response res = createErrorResponse(code, message);
        writeResponse(request, response, res);
    }

    public Response createErrorResponse(APIException e) {
        return createErrorResponse(e.getError(), e.getMessage());
    }

    public Response createErrorResponse(SubsonicRESTController.ErrorCode code, String message) {
        Response res = createResponse(false);
        Error error = new Error();
        res.setError(error);
        error.setCode(code.getCode());
        error.setMessage(message);
        return res;
    }

    public Entry<String, String> serializeForType(HttpServletRequest request, Response resp) {
        String format = getStringParameter(request, "f", "xml");
        String jsonpCallback = request.getParameter("callback");
        boolean json = "json".equals(format);
        boolean jsonp = "jsonp".equals(format) && jsonpCallback != null;
        Marshaller marshaller;
        MediaType type;

        if (json) {
            marshaller = createJsonMarshaller();
            type = MediaType.JSON_UTF_8;
        } else if (jsonp) {
            marshaller = createJsonMarshaller();
            type = MediaType.JAVASCRIPT_UTF_8;
        } else {
            marshaller = createXmlMarshaller();
            type = MediaType.XML_UTF_8;
        }

        StringWriter writer = new StringWriter();
        try {
            marshaller.marshal(new ObjectFactory().createSubsonicResponse(resp), writer);
        } catch (JAXBException x) {
            LOG.error("Failed to marshal JAXB", x);
            throw new RuntimeException(x);
        }

        String out = writer.toString();
        if ((json || jsonp) && resp.getOpenSubsonicExtensions() != null) {
            out = unwrapOpenSubsonicExtensions(out);
        }
        if (jsonp) {
            out = jsonpCallback + "(" + out + ");";
        }

        // Defensive sanitization: MOXy writes string values verbatim, so a control character
        // (e.g. a null byte from mojibake APEv2 tags) lands raw in the response and produces
        // invalid XML/JSON that strict Subsonic clients (Symfonium, etc.) cannot parse — they
        // hang waiting for a well-formed document. Replace every XML-invalid character with
        // U+FFFD so a single bad tag can never corrupt the whole response.
        return Pair.of(type.toString(), sanitizeInvalidXmlChars(out));
    }

    /**
     * Replaces XML 1.0-invalid characters with U+FFFD (replacement character). Valid per
     * XML 1.0 5th ed: tab (0x09), LF (0x0A), CR (0x0D), 0x20-0xD7FF, 0xE000-0xFFFD, and
     * supplementary planes 0x10000-0x10FFFF. Surrogate pairs are handled so valid emoji /
     * supplementary characters survive intact.
     */
    static String sanitizeInvalidXmlChars(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF && i + 1 < value.length()) {
                char low = value.charAt(i + 1);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    // Valid surrogate pair (supplementary character) — keep both.
                    int cp = Character.toCodePoint(c, low);
                    if (cp >= 0x10000 && cp <= 0x10FFFF) {
                        sb.append(c).append(low);
                        i++;
                        continue;
                    }
                }
            }
            sb.append(isValidXmlChar(c) ? c : '\uFFFD');
        }
        return sb.toString();
    }

    private static boolean isValidXmlChar(char c) {
        return c == 0x09 || c == 0x0A || c == 0x0D
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD);
    }

    public XMLGregorianCalendar convertDate(Instant date) {
        if (date == null) {
            return null;
        }

        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(date.toEpochMilli());
        return datatypeFactory.newXMLGregorianCalendar(c).normalize();
    }
}
