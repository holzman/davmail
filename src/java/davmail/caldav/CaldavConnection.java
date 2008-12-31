package davmail.caldav;

import davmail.AbstractConnection;
import davmail.Settings;
import davmail.exchange.ExchangeSession;
import davmail.exchange.ExchangeSessionFactory;
import davmail.tray.DavGatewayTray;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.auth.AuthenticationException;
import org.apache.log4j.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handle a caldav connection.
 */
public class CaldavConnection extends AbstractConnection {
    protected Logger wireLogger = Logger.getLogger(this.getClass());

    protected boolean closed = false;

    // Initialize the streams and start the thread
    public CaldavConnection(Socket clientSocket) {
        super("CaldavConnection", clientSocket, "UTF-8");
        wireLogger.setLevel(Settings.getLoggingLevel("httpclient.wire"));
    }

    protected Map<String, String> parseHeaders() throws IOException {
        HashMap<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readClient()) != null && line.length() > 0) {
            int index = line.indexOf(':');
            if (index <= 0) {
                throw new IOException("Invalid header: " + line);
            }
            headers.put(line.substring(0, index).toLowerCase(), line.substring(index + 1).trim());
        }
        return headers;
    }

    protected String getContent(String contentLength) throws IOException {
        if (contentLength == null || contentLength.length() == 0) {
            return null;
        } else {
            int size;
            try {
                size = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid content length: " + contentLength);
            }
            char[] buffer = new char[size];
            int actualSize = in.read(buffer);
            if (actualSize < 0) {
                throw new IOException("End of stream reached reading content");
            }
            return new String(buffer, 0, actualSize);
        }
    }

    protected void setSocketTimeout(String keepAliveValue) throws IOException {
        if (keepAliveValue != null && keepAliveValue.length() > 0) {
            int keepAlive;
            try {
                keepAlive = Integer.parseInt(keepAliveValue);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Keep-Alive: " + keepAliveValue);
            }
            if (keepAlive > 300) {
                keepAlive = 300;
            }
            client.setSoTimeout(keepAlive * 1000);
            DavGatewayTray.debug("Set socket timeout to " + keepAlive + " seconds");
        }
    }

    public void run() {
        String line;
        StringTokenizer tokens;

        try {
            while (!closed) {
                line = readClient();
                // unable to read line, connection closed ?
                if (line == null) {
                    break;
                }
                tokens = new StringTokenizer(line);
                if (tokens.hasMoreTokens()) {
                    String command = tokens.nextToken();
                    Map<String, String> headers = parseHeaders();
                    if (tokens.hasMoreTokens()) {
                        String path = tokens.nextToken();
                        String content = getContent(headers.get("content-length"));
                        setSocketTimeout(headers.get("keep-alive"));
                        if ("OPTIONS".equals(command)) {
                            sendOptions();
                        } else if (!headers.containsKey("authorization")) {
                            sendUnauthorized();
                        } else {
                            decodeCredentials(headers.get("authorization"));
                            // authenticate only once
                            if (session == null) {
                                // first check network connectivity
                                ExchangeSessionFactory.checkConfig();
                                try {
                                    session = ExchangeSessionFactory.getInstance(userName, password);
                                } catch (AuthenticationException e) {
                                    sendErr(HttpStatus.SC_UNAUTHORIZED, e.getMessage());
                                }
                            }
                            if (session != null) {
                                handleRequest(command, path, headers, content);
                            }
                        }
                    } else {
                        sendErr(HttpStatus.SC_NOT_IMPLEMENTED, "Invalid URI");
                    }
                }

                os.flush();
            }
        } catch (SocketTimeoutException e) {
            DavGatewayTray.debug("Closing connection on timeout");
        } catch (SocketException e) {
            DavGatewayTray.debug("Connection closed");
        } catch (IOException e) {
            DavGatewayTray.error(e);
            try {
                sendErr(HttpStatus.SC_INTERNAL_SERVER_ERROR, e);
            } catch (IOException e2) {
                DavGatewayTray.debug("Exception sending error to client", e2);
            }
        } finally {
            close();
        }
        DavGatewayTray.resetIcon();
    }

    protected int getDepth(Map<String, String> headers) {
        int result = 0;
        String depthValue = headers.get("depth");
        if (depthValue != null) {
            try {
                result = Integer.valueOf(depthValue);
            } catch (NumberFormatException e) {
                DavGatewayTray.warn("Invalid depth value: " + depthValue);
            }
        }
        return result;
    }

    public void handleRequest(String command, String path, Map<String, String> headers, String body) throws IOException {
        int depth = getDepth(headers);
        String[] paths = path.split("/");

        // full debug trace
        if (wireLogger.isDebugEnabled()) {
            wireLogger.debug("Caldav command: " + command + " " + path + " depth: " + depth + "\n" + body);
        }

        CaldavRequest request = null;
        if ("PROPFIND".equals(command) || "REPORT".equals(command)) {
            request = new CaldavRequest(body);
        }
        if ("OPTIONS".equals(command)) {
            sendOptions();
            // redirect PROPFIND on / to current user principal
        } else if ("PROPFIND".equals(command) && (paths.length == 0 || paths.length == 1)) {
            sendRoot(request);
        } else if ("GET".equals(command) && (paths.length == 0 || paths.length == 1)) {
            sendGetRoot();
            // return current user calendar
        } else if ("calendar".equals(paths[1])) {
            StringBuilder message = new StringBuilder();
            message.append("/calendar no longer supported, recreate calendar with /users/")
                    .append(session.getEmail()).append("/calendar");
            DavGatewayTray.error(message.toString());
            sendErr(HttpStatus.SC_BAD_REQUEST, message.toString());
        } else if ("user".equals(paths[1])) {
            sendRedirect(headers, "/principals/users/" + session.getEmail());
            // principal namespace
        } else if ("PROPFIND".equals(command) && "principals".equals(paths[1]) && paths.length == 4 &&
                "users".equals(paths[2])) {
            sendPrincipal(request, paths[3]);
            // send back principal on search
        } else if ("REPORT".equals(command) && "principals".equals(paths[1]) && paths.length == 3 &&
                "users".equals(paths[2])) {
            sendPrincipal(request, session.getEmail());
            // user root
        } else if ("PROPFIND".equals(command) && "users".equals(paths[1]) && paths.length == 3) {
            sendUserRoot(request, depth, paths[2]);
        } else if ("PROPFIND".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "inbox".equals(paths[3])) {
            sendInbox(request, paths[2]);
        } else if ("REPORT".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "inbox".equals(paths[3])) {
            reportInbox();
        } else if ("PROPFIND".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "outbox".equals(paths[3])) {
            sendOutbox(request, paths[2]);
        } else if ("POST".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "outbox".equals(paths[3])) {
            sendFreeBusy(body);
        } else if ("PROPFIND".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "calendar".equals(paths[3])) {
            sendCalendar(request, depth, paths[2]);
        } else if ("REPORT".equals(command) && "users".equals(paths[1]) && paths.length == 4 && "calendar".equals(paths[3])
                // only current user for now
                && session.getEmail().equals(paths[2])) {
            reportCalendar(request);

        } else if ("PUT".equals(command) && "users".equals(paths[1]) && paths.length == 5 && "calendar".equals(paths[3])
                // only current user for now
                && session.getEmail().equals(paths[2])) {
            String etag = headers.get("if-match");
            int status = session.createOrUpdateEvent(paths[4], body, etag);
            sendHttpResponse(status, true);

        } else if ("DELETE".equals(command) && "users".equals(paths[1]) && paths.length == 5 && "calendar".equals(paths[3])
                // only current user for now
                && session.getEmail().equals(paths[2])) {
            int status = session.deleteEvent(paths[4]);
            sendHttpResponse(status, true);
        } else if ("GET".equals(command) && "users".equals(paths[1]) && paths.length == 5 && "calendar".equals(paths[3])
                // only current user for now
                && session.getEmail().equals(paths[2])) {
            ExchangeSession.Event event = session.getEvent(paths[4]);
            sendHttpResponse(HttpStatus.SC_OK, null, "text/calendar;charset=UTF-8", event.getICS(), true);

        } else {
            StringBuilder message = new StringBuilder();
            message.append("Unsupported request: ").append(command).append(" ").append(path);
            message.append(" Depth: ").append(depth).append("\n").append(body);
            DavGatewayTray.error(message.toString());
            sendErr(HttpStatus.SC_BAD_REQUEST, message.toString());
        }
    }

    protected void appendEventsResponses(StringBuilder buffer, CaldavRequest request, List<ExchangeSession.Event> events) throws IOException {
        int size = events.size();
        int count = 0;
        for (ExchangeSession.Event event : events) {
            DavGatewayTray.debug("Retrieving event " + (++count) + "/" + size);
            appendEventResponse(buffer, request, event);
        }
    }

    protected void appendEventResponse(StringBuilder buffer, CaldavRequest request, ExchangeSession.Event event) throws IOException {
        String eventPath = event.getPath().replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
        buffer.append("<D:response>\n");
        buffer.append("        <D:href>/users/").append(session.getEmail()).append("/calendar").append(eventPath).append("</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");
        if (request.hasProperty("calendar-data")) {
            String ics = event.getICS();
            if (ics != null && ics.length() > 0) {
                ics = ics.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
                buffer.append("                <C:calendar-data xmlns:C=\"urn:ietf:params:xml:ns:caldav\"\n");
                buffer.append("                    C:content-type=\"text/calendar\" C:version=\"2.0\">");
                buffer.append(ics);
                buffer.append("</C:calendar-data>\n");
            }
        }
        if (request.hasProperty("getetag")) {
            buffer.append("                <D:getetag>").append(event.getEtag()).append("</D:getetag>\n");
        }
        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype/>");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>").append(eventPath).append("</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("    </D:response>\n");
    }

    public void appendCalendar(StringBuilder buffer, String principal, CaldavRequest request) throws IOException {
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/users/").append(principal).append("/calendar</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");

        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype>\n");
            buffer.append("                    <D:collection/>\n");
            buffer.append("                    <C:calendar xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>\n");
            buffer.append("                </D:resourcetype>\n");
        }
        if (request.hasProperty("owner")) {
            buffer.append("                <D:owner>\n");
            buffer.append("                    <D:href>/principals/users/").append(principal).append("</D:href>\n");
            buffer.append("                </D:owner>\n");
        }
        if (request.hasProperty("getctag")) {
            buffer.append("                <CS:getctag xmlns:CS=\"http://calendarserver.org/ns/\">")
                    .append(base64Encode(session.getCalendarEtag()))
                    .append("</CS:getctag>\n");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>calendar</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("    </D:response>\n");
    }

    public void appendInbox(StringBuilder buffer, String principal, CaldavRequest request) throws IOException {
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/users/").append(principal).append("/inbox</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");

        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype>\n");
            buffer.append("                    <D:collection/>\n");
            buffer.append("                    <C:schedule-inbox xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>\n");
            buffer.append("                </D:resourcetype>\n");
        }
        if (request.hasProperty("getctag")) {
            buffer.append("                <CS:getctag xmlns:CS=\"http://calendarserver.org/ns/\">0</CS:getctag>\n");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>inbox</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("    </D:response>\n");
    }

    public void appendOutbox(StringBuilder buffer, String principal, CaldavRequest request) throws IOException {
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/users/").append(principal).append("/outbox</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");

        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype>\n");
            buffer.append("                    <D:collection/>\n");
            buffer.append("                    <C:schedule-outbox xmlns:C=\"urn:ietf:params:xml:ns:caldav\"/>\n");
            buffer.append("                </D:resourcetype>\n");
        }
        if (request.hasProperty("getctag")) {
            buffer.append("                <CS:getctag xmlns:CS=\"http://calendarserver.org/ns/\">0</CS:getctag>\n");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>outbox</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("    </D:response>\n");
    }

    public void sendGetRoot() throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Connected to DavMail<br/>");
        buffer.append("UserName :").append(userName).append("<br/>");
        buffer.append("Email :").append(session.getEmail()).append("<br/>");
        sendHttpResponse(HttpStatus.SC_OK, null, "text/html;charset=UTF-8", buffer.toString(), true);
    }

    public void sendInbox(CaldavRequest request, String principal) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        appendInbox(buffer, principal, request);
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void reportInbox() throws IOException {
        // inbox is always empty
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<D:multistatus xmlns:D=\"DAV:\">\n");
        buffer.append("</D:multistatus>");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendOutbox(CaldavRequest request, String principal) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        appendOutbox(buffer, principal, request);
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendCalendar(CaldavRequest request, int depth, String principal) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        appendCalendar(buffer, principal, request);
        if (depth == 1) {
            appendEventsResponses(buffer, request, session.getAllEvents());
        }
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    protected String getEventFileNameFromPath(String path) {
        int index = path.indexOf("/calendar/");
        if (index < 0) {
            return null;
        } else {
            return path.substring(index + "/calendar/".length());
        }
    }

    public void reportCalendar(CaldavRequest request) throws IOException {
        List<ExchangeSession.Event> events;
        List<String> notFound = new ArrayList<String>();
        if (request.isMultiGet()) {
            events = new ArrayList<ExchangeSession.Event>();
            for (String href : request.getHrefs()) {
                try {
                    String eventName = getEventFileNameFromPath(href);
                    if (eventName == null) {
                        notFound.add(href);
                    } else {
                        events.add(session.getEvent(eventName));
                    }
                } catch (HttpException e) {
                    notFound.add(href);
                }
            }
        } else {
            events = session.getAllEvents();
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<D:multistatus xmlns:D=\"DAV:\">\n");
        appendEventsResponses(buffer, request, events);

        // send not found events errors
        for (String href : notFound) {
            buffer.append("    <D:response>\n");
            buffer.append("        <D:href>").append(href).append("</D:href>\n");
            buffer.append("        <D:propstat>\n");
            buffer.append("            <D:status>HTTP/1.1 404 Not Found</D:status>\n");
            buffer.append("        </D:propstat>\n");
            buffer.append("    </D:response>\n");
        }
        buffer.append("</D:multistatus>");

        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendUserRoot(CaldavRequest request, int depth, String principal) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/users/").append(principal).append("</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");

        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype>\n");
            buffer.append("                    <D:collection/>\n");
            buffer.append("                </D:resourcetype>\n");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>").append(principal).append("</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("      </D:response>\n");
        if (depth == 1) {
            appendInbox(buffer, principal, request);
            appendOutbox(buffer, principal, request);
            appendCalendar(buffer, principal, request);
        }
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendRoot(CaldavRequest request) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");
        if (request.hasProperty("principal-collection-set")) {
            buffer.append("                <D:principal-collection-set>\n");
            buffer.append("                    <D:href>/principals/users/").append(session.getEmail()).append("</D:href>\n");
            buffer.append("                </D:principal-collection-set>");
        }
        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>ROOT</D:displayname>");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("      </D:response>\n");
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendPrincipal(CaldavRequest request, String principal) throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        buffer.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");
        buffer.append("    <D:response>\n");
        buffer.append("        <D:href>/principals/users/").append(principal).append("</D:href>\n");
        buffer.append("        <D:propstat>\n");
        buffer.append("            <D:prop>\n");
        if (request.hasProperty("calendar-home-set")) {
            buffer.append("                <C:calendar-home-set>\n");
            buffer.append("                    <D:href>/users/").append(principal).append("</D:href>\n");
            buffer.append("                </C:calendar-home-set>");
        }

        if (request.hasProperty("calendar-user-address-set")) {
            buffer.append("                <C:calendar-user-address-set>\n");
            buffer.append("                    <D:href>mailto:").append(principal).append("</D:href>\n");
            buffer.append("                </C:calendar-user-address-set>");
        }

        if (request.hasProperty("schedule-inbox-URL")) {
            buffer.append("                <C:schedule-inbox-URL>\n");
            buffer.append("                    <D:href>/users/").append(principal).append("/inbox</D:href>\n");
            buffer.append("                </C:schedule-inbox-URL>");
        }

        if (request.hasProperty("schedule-outbox-URL")) {
            buffer.append("                <C:schedule-outbox-URL>\n");
            buffer.append("                    <D:href>/users/").append(principal).append("/outbox</D:href>\n");
            buffer.append("                </C:schedule-outbox-URL>");
        }

        if (request.hasProperty("displayname")) {
            buffer.append("                <D:displayname>").append(principal).append("</D:displayname>");
        }
        if (request.hasProperty("resourcetype")) {
            buffer.append("                <D:resourcetype>\n");
            buffer.append("                    <D:collection/>\n");
            buffer.append("                    <D:principal/>\n");
            buffer.append("                </D:resourcetype>\n");
        }
        buffer.append("            </D:prop>\n");
        buffer.append("            <D:status>HTTP/1.1 200 OK</D:status>\n");
        buffer.append("        </D:propstat>\n");
        buffer.append("      </D:response>\n");
        buffer.append("</D:multistatus>\n");
        sendHttpResponse(HttpStatus.SC_MULTI_STATUS, null, "text/xml;charset=UTF-8", buffer.toString(), true);
    }

    public void sendFreeBusy(String body) throws IOException {
        Map<String, String> valueMap = new HashMap<String, String>();
        Map<String, String> keyMap = new HashMap<String, String>();
        BufferedReader reader = new BufferedReader(new StringReader(body));
        String line;
        String key = null;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(" ") && "ATTENDEE".equals(key)) {
                valueMap.put(key, valueMap.get(key) + line.substring(1));
            } else {
                int index = line.indexOf(':');
                if (index <= 0) {
                    throw new IOException("Invalid request: " + body);
                }
                String fullkey = line.substring(0, index);
                String value = line.substring(index + 1);
                int semicolonIndex = fullkey.indexOf(";");
                if (semicolonIndex > 0) {
                    key = fullkey.substring(0, semicolonIndex);
                } else {
                    key = fullkey;
                }
                valueMap.put(key, value);
                keyMap.put(key, fullkey);
            }
        }
        String freeBusy = session.getFreebusy(valueMap);
        if (freeBusy != null) {
            String response = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                    "   <C:schedule-response xmlns:D=\"DAV:\"\n" +
                    "                xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n" +
                    "   <C:response>\n" +
                    "     <C:recipient>\n" +
                    "       <D:href>" + valueMap.get("ATTENDEE") + "</D:href>\n" +
                    "     </C:recipient>\n" +
                    "     <C:request-status>2.0;Success</C:request-status>\n" +
                    "     <C:calendar-data>BEGIN:VCALENDAR\n" +
                    "VERSION:2.0\n" +
                    "PRODID:-//Mozilla.org/NONSGML Mozilla Calendar V1.1//EN\n" +
                    "METHOD:REPLY\n" +
                    "BEGIN:VFREEBUSY\n" +
                    "DTSTAMP:" + valueMap.get("DTSTAMP") + "\n" +
                    "ORGANIZER:" + valueMap.get("ORGANIZER") + "\n" +
                    "DTSTART:" + valueMap.get("DTSTART") + "\n" +
                    "DTEND:" + valueMap.get("DTEND") + "\n" +
                    "UID:" + valueMap.get("UID") + "\n" +
                    keyMap.get("ATTENDEE") + ";" + valueMap.get("ATTENDEE") + "\n" +
                    "FREEBUSY;FBTYPE=BUSY-UNAVAILABLE:" + freeBusy + "\n" +
                    "END:VFREEBUSY\n" +
                    "END:VCALENDAR" +
                    "</C:calendar-data>\n" +
                    "   </C:response>\n" +
                    "   </C:schedule-response>";
            sendHttpResponse(HttpStatus.SC_OK, null, "text/xml;charset=UTF-8", response, true);
        } else {
            sendHttpResponse(HttpStatus.SC_NOT_FOUND, null, "text/plain", "Unknown recipient: " + valueMap.get("ATTENDEE"), true);
        }

    }


    public void sendRedirect(Map<String, String> headers, String path) throws IOException {
        StringBuilder buffer = new StringBuilder();
        if (headers.get("host") != null) {
            buffer.append("http://").append(headers.get("host"));
        }
        buffer.append(path);
        Map<String, String> responseHeaders = new HashMap<String, String>();
        responseHeaders.put("Location", buffer.toString());
        sendHttpResponse(HttpStatus.SC_MOVED_PERMANENTLY, responseHeaders, null, null, true);
    }

    public void sendErr(int status, Exception e) throws IOException {
        String message = e.getMessage();
        if (message == null) {
            message = e.toString();
        }
        sendErr(status, message);
    }

    public void sendErr(int status, String message) throws IOException {
        sendHttpResponse(status, null, "text/plain;charset=UTF-8", message, false);
    }

    public void sendOptions() throws IOException {
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("Allow", "OPTIONS, GET, PROPFIND, PUT, POST");
        headers.put("DAV", "1, 2, 3, access-control, calendar-access, ticket, calendar-schedule");
        sendHttpResponse(HttpStatus.SC_OK, headers, null, null, true);
    }

    public void sendUnauthorized() throws IOException {
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("WWW-Authenticate", "Basic realm=\"" + Settings.getProperty("davmail.url") + "\"");
        sendHttpResponse(HttpStatus.SC_UNAUTHORIZED, headers, null, null, true);
    }

    public void sendHttpResponse(int status, boolean keepAlive) throws IOException {
        sendHttpResponse(status, null, null, null, keepAlive);
    }

    public void sendHttpResponse(int status, Map<String, String> headers, String contentType, String content, boolean keepAlive) throws IOException {
        sendClient("HTTP/1.1 " + status + " " + HttpStatus.getStatusText(status));
        sendClient("Server: DavMail Gateway");
        SimpleDateFormat formatter = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
        sendClient("Date: " + formatter.format(new java.util.Date()));
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                sendClient(header.getKey() + ": " + header.getValue());
            }
        }
        if (contentType != null) {
            sendClient("Content-Type: " + contentType);
        }
        sendClient("Connection: " + (keepAlive ? "keep-alive" : "close"));
        closed = !keepAlive;
        if (content != null && content.length() > 0) {
            sendClient("Content-Length: " + content.getBytes("UTF-8").length);
        } else {
            sendClient("Content-Length: 0");
        }
        sendClient("");
        if (content != null && content.length() > 0) {
            // full debug trace
            if (wireLogger.isDebugEnabled()) {
                wireLogger.debug("> " + content);
            }
            sendClient(content.getBytes("UTF-8"));
        }
    }

    /**
     * Decode HTTP credentials
     *
     * @param authorization http authorization header value
     * @throws java.io.IOException if invalid credentials
     */
    protected void decodeCredentials(String authorization) throws IOException {
        int index = authorization.indexOf(' ');
        if (index > 0) {
            String mode = authorization.substring(0, index).toLowerCase();
            if (!"basic".equals(mode)) {
                throw new IOException("Unsupported authorization mode: " + mode);
            }
            String encodedCredentials = authorization.substring(index + 1);
            String decodedCredentials = base64Decode(encodedCredentials);
            index = decodedCredentials.indexOf(':');
            if (index > 0) {
                userName = decodedCredentials.substring(0, index);
                password = decodedCredentials.substring(index + 1);
            } else {
                throw new IOException("Invalid credentials");
            }
        } else {
            throw new IOException("Invalid credentials");
        }

    }

    protected class CaldavRequest {
        protected HashSet<String> properties = new HashSet<String>();
        protected HashSet<String> hrefs;
        protected boolean isMultiGet;

        public CaldavRequest(String body) throws IOException {
            // parse body
            XMLStreamReader streamReader = null;
            try {
                XMLInputFactory inputFactory = XMLInputFactory.newInstance();
                inputFactory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
                inputFactory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.TRUE);

                streamReader = inputFactory.createXMLStreamReader(new StringReader(body));
                boolean inElement = false;
                boolean inProperties = false;
                String currentElement = null;
                while (streamReader.hasNext()) {
                    int event = streamReader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        inElement = true;
                        currentElement = streamReader.getLocalName();
                        if ("prop".equals(currentElement)) {
                            inProperties = true;
                        } else if ("calendar-multiget".equals(currentElement)) {
                            isMultiGet = true;
                        } else if (inProperties) {
                            properties.add(currentElement);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("prop".equals(currentElement)) {
                            inProperties = false;
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS && inElement) {
                        if ("href".equals(currentElement)) {
                            if (hrefs == null) {
                                hrefs = new HashSet<String>();
                            }
                            hrefs.add(streamReader.getText());
                        }
                        inElement = false;
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException(e.getMessage());
            } finally {
                try {
                    if (streamReader != null) {
                        streamReader.close();
                    }
                } catch (XMLStreamException e) {
                    DavGatewayTray.error(e);
                }
            }
        }

        public boolean hasProperty(String propertyName) {
            return properties.contains(propertyName);
        }

        public boolean isMultiGet() {
            return isMultiGet && hrefs != null;
        }

        public Set<String> getHrefs() {
            return hrefs;
        }
    }
}

