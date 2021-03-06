/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package JMeter.plugins.functional.samplers.websocket;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.util.EncoderCache;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.log.Logger;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Maciej Zaleski
 */
public class WebSocketSampler extends AbstractSampler implements TestStateListener {
    public static int DEFAULT_CONNECTION_TIMEOUT = 20000; //20 sec
    public static int DEFAULT_RESPONSE_TIMEOUT = 20000; //20 sec

    private static final Logger log = LoggingManager.getLoggerForClass();

    private static final String ARG_VAL_SEP = "="; // $NON-NLS-1$
    private static final String QRY_SEP = "&"; // $NON-NLS-1$
    private static final String WS_PREFIX = "ws://"; // $NON-NLS-1$
    private static final String WSS_PREFIX = "wss://"; // $NON-NLS-1$
    private static final String DEFAULT_PROTOCOL = "ws";

    private static Map<String, ServiceSocket> connectionList;

    private static ExecutorService executor = Executors.newCachedThreadPool();

    private StringBuilder messages = new StringBuilder();

    public WebSocketSampler() {
        super();
        setName("WebSocket sampler");
    }

    private ServiceSocket getConnectionSocket() throws Exception {
        URI uri = getUri();
        messages.append("\n\n[CONNECTION INFORMATION]");
        messages.append("\nURI ").append(uri);
        String connectionId = getConnectionId();
        messages.append("\nconnection id ").append(connectionId);

        if (isStreamingConnection() && connectionList.containsKey(connectionId)) {
            log.debug("connection " + connectionId + "already in list");
            ServiceSocket socket = connectionList.get(connectionId);
            socket.initialize(this, null, true);
            return socket;
        }
        //Create WebSocket client
        SslContextFactory sslContexFactory = new SslContextFactory();
        sslContexFactory.setTrustAll(isIgnoreSslErrors());
        WebSocketClient webSocketClient = new WebSocketClient(sslContexFactory, executor);
        HttpCookieStore cookieStore = new HttpCookieStore();
        for (HttpCookie httpCookie : getHttpCookies()) {
            cookieStore.add(uri, httpCookie);
        }
        webSocketClient.setCookieStore(cookieStore);

        ServiceSocket socket = new ServiceSocket(this, webSocketClient);
        socket.setSessionId(connectionId);
        if (isStreamingConnection()) {
            connectionList.put(connectionId, socket);
        }

        //Start WebSocket client thread and upgrage HTTP connection
        webSocketClient.start();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        webSocketClient.connect(socket, uri, request);

        //Get connection timeout or use the default value
        int connectionTimeout;
        try {
            connectionTimeout = Integer.parseInt(getConnectionTimeout());
        } catch (NumberFormatException ex) {
            log.warn("Connection timeout is not a number; using the default connection timeout of " + DEFAULT_CONNECTION_TIMEOUT + "ms");
            connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        }

        socket.awaitOpen(connectionTimeout, TimeUnit.MILLISECONDS);

        return socket;
    }

    private ArrayList<HttpCookie> getHttpCookies() {
        ArrayList<HttpCookie> result = new ArrayList<>();

        JMeterProperty property = JMeterContextService.getContext().getCurrentSampler().getProperty("CookieManager.cookies");
        Object value = property.getObjectValue();
        if (value instanceof List) {
            for (TestElementProperty elementProperty : (List<TestElementProperty>) value) {
                Cookie jmeterCookie = (Cookie) elementProperty.getObjectValue();
                HttpCookie httpCookie = new HttpCookie(jmeterCookie.getName(), jmeterCookie.getValue());
                httpCookie.setDomain(jmeterCookie.getDomain());
                httpCookie.setPath(jmeterCookie.getPath());
                result.add(httpCookie);

                messages.append("\nAdding cookie ").append(jmeterCookie.getName());
            }
        }
        return result;
    }

    @Override
    public SampleResult sample(Entry entry) {
        ServiceSocket socket = null;
        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel(getName());
        sampleResult.setDataEncoding(getContentEncoding());

        //This StringBuilder will track all exceptions related to the protocol processing
        StringBuilder errorList = new StringBuilder();
        errorList.append("\n\n[Problems]\n");

        boolean isOK = false;

        //Set the message payload in the Sampler
        String connectPayloadMessage = getStompPayload(getConnectPayload());
        String subscribePayloadMessage = getStompPayload(getSubscribePayload());

        int responseTimeout;
        try {
            responseTimeout = Integer.parseInt(getResponseTimeout());
        } catch (NumberFormatException ex) {
            log.warn("Request timeout is not a number; using the default request timeout of " + DEFAULT_RESPONSE_TIMEOUT + "ms");
            responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        }

        sampleResult.setSamplerData(connectPayloadMessage + "\n" + subscribePayloadMessage);

        //Could improve precision by moving this closer to the action
        sampleResult.sampleStart();

        try {
            socket = getConnectionSocket();
            if (socket == null) {
                //Couldn't open a connection, set the status and exit
                sampleResult.setResponseCode("500");
                sampleResult.setSuccessful(false);
                sampleResult.sampleEnd();
                sampleResult.setResponseMessage(errorList.toString());
                errorList.append(" - Connection couldn't be opened").append("\n");
                return sampleResult;
            }

            //Wait for any of the following:
            // - Response matching response pattern is received
            // - Response matching connection closing pattern is received
            // - Timeout is reached
            if (StringUtils.isNotBlank(connectPayloadMessage)) {
                sendMessage(socket, connectPayloadMessage);
                socket.awaitConnected(responseTimeout, TimeUnit.MILLISECONDS);
            }

            sendMessage(socket, subscribePayloadMessage);

            if (socket.subscribeExpression != null) {
                socket.awaitSubscribe(responseTimeout, TimeUnit.MILLISECONDS);
            }

            sampleResult.setResponseCode(getCodeRetour(socket));

            //Set sampler response code
            if (socket.getError() != 0) {
                isOK = false;
                sampleResult.setResponseCode(socket.getError().toString());
            } else {
                sampleResult.setResponseCodeOK();
                isOK = true;
            }

            //set sampler response
            sampleResult.setResponseData(socket.getResponseMessage(), getContentEncoding());

        } catch (URISyntaxException e) {
            errorList.append(" - Invalid URI syntax: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
        } catch (IOException e) {
            errorList.append(" - IO Exception: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
        } catch (NumberFormatException e) {
            errorList.append(" - Cannot parse number: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
        } catch (InterruptedException e) {
            errorList.append(" - Execution interrupted: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
        } catch (Exception e) {
            errorList.append(" - Unexpected error: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
        }

        sampleResult.sampleEnd();
        sampleResult.setSuccessful(isOK);

        String logMessage = (socket != null) ? socket.getLogMessage() : "";
        sampleResult.setResponseMessage(messages.toString() + logMessage + errorList);
        return sampleResult;
    }

    private void sendMessage(ServiceSocket socket, String payloadMessage) throws IOException, InterruptedException {
        //Send message only if it is not empty
        socket.sendMessage(payloadMessage);
    }

    private String getCodeRetour(ServiceSocket socket) {
        String codeRetour = null;

        //If no response is received set code 204; actually not used...needs to do something else
        if (socket.getResponseMessage() == null || socket.getResponseMessage().isEmpty()) {
            codeRetour = "204";
        }
        return codeRetour;
    }

    /**
     * Build Payload message like [" ....\\n\\n\\u0000"]
     *
     * @param payloadMessage message
     * @return payload
     */
    private String getStompPayload(String payloadMessage) {
        if (StringUtils.isBlank(payloadMessage)) {
            return "";
        }
        if (!payloadMessage.endsWith("\\u0000\"]")) {
            return String.format("[\"%s%s\"]", payloadMessage, "\\n\\n\\u0000");
        }
        return payloadMessage;
    }

    @Override
    public void setName(String name) {
        if (name != null) {
            setProperty(TestElement.NAME, name);
        }
    }

    @Override
    public String getName() {
        return getPropertyAsString(TestElement.NAME);
    }

    @Override
    public void setComment(String comment) {
        setProperty(new StringProperty(TestElement.COMMENTS, comment));
    }

    @Override
    public String getComment() {
        return getProperty(TestElement.COMMENTS).getStringValue();
    }

    public URI getUri() throws URISyntaxException {
        String path = this.getContextPath();
        // Hack to allow entire URL to be provided in host field
        if (path.startsWith(WS_PREFIX)
                || path.startsWith(WSS_PREFIX)) {
            return new URI(path);
        }
        String domain = getServerAddress();
        String protocol = getProtocol();
        // HTTP URLs must be absolute, allow file to be relative
        if (!path.startsWith("/")) { // $NON-NLS-1$
            path = "/" + path; // $NON-NLS-1$
        }

        String queryString = getQueryString(getContentEncoding());
        if (isProtocolDefaultPort()) {
            return new URI(protocol, null, domain, -1, path, queryString, null);
        }
        return new URI(protocol, null, domain, Integer.parseInt(getServerPort()), path, queryString, null);
    }

    /**
     * Tell whether the default port for the specified protocol is used
     *
     * @return true if the default port number for the protocol is used, false
     * otherwise
     */
    public boolean isProtocolDefaultPort() {
        final int port = Integer.parseInt(getServerPort());
        final String protocol = getProtocol();
        return ("ws".equalsIgnoreCase(protocol) && port == HTTPConstants.DEFAULT_HTTP_PORT)
                || ("wss".equalsIgnoreCase(protocol) && port == HTTPConstants.DEFAULT_HTTPS_PORT);
    }

    public String getServerPort() {
        final String port_s = getPropertyAsString("serverPort", "0");
        Integer port;
        String protocol = getProtocol();

        try {
            port = Integer.parseInt(port_s);
        } catch (Exception ex) {
            port = 0;
        }

        if (port == 0) {
            if ("wss".equalsIgnoreCase(protocol)) {
                return String.valueOf(HTTPConstants.DEFAULT_HTTPS_PORT);
            } else if ("ws".equalsIgnoreCase(protocol)) {
                return String.valueOf(HTTPConstants.DEFAULT_HTTP_PORT);
            }
        }
        return port.toString();
    }

    public void setServerPort(String port) {
        setProperty("serverPort", port);
    }

    public String getResponseTimeout() {
        return getPropertyAsString("responseTimeout", "20000");
    }

    public void setResponseTimeout(String responseTimeout) {
        setProperty("responseTimeout", responseTimeout);
    }


    public String getConnectionTimeout() {
        return getPropertyAsString("connectionTimeout", "5000");
    }

    public void setConnectionTimeout(String connectionTimeout) {
        setProperty("connectionTimeout", connectionTimeout);
    }

    public void setProtocol(String protocol) {
        setProperty("protocol", protocol);
    }

    public String getProtocol() {
        String protocol = getPropertyAsString("protocol");
        if (protocol == null || protocol.isEmpty()) {
            return DEFAULT_PROTOCOL;
        }
        return protocol;
    }

    public void setServerAddress(String serverAddress) {
        setProperty("serverAddress", serverAddress);
    }

    public String getServerAddress() {
        return getPropertyAsString("serverAddress");
    }


    public void setImplementation(String implementation) {
        setProperty("implementation", implementation);
    }

    public String getImplementation() {
        return getPropertyAsString("implementation");
    }

    public void setContextPath(String contextPath) {
        setProperty("contextPath", contextPath);
    }

    public String getContextPath() {
        return getPropertyAsString("contextPath");
    }

    public void setContentEncoding(String contentEncoding) {
        setProperty("contentEncoding", contentEncoding);
    }

    public String getContentEncoding() {
        return getPropertyAsString("contentEncoding", "UTF-8");
    }

    public void setConnectPayload(String connectPayload) {
        setProperty("connectPayload", connectPayload);
    }

    public String getConnectPayload() {
        return getPropertyAsString("connectPayload");
    }

    public void setSubscribePayload(String subscribePayload) {
        setProperty("subscribePayload", subscribePayload);
    }

    public String getSubscribePayload() {
        return getPropertyAsString("subscribePayload");
    }

    public void setIgnoreSslErrors(Boolean ignoreSslErrors) {
        setProperty("ignoreSslErrors", ignoreSslErrors);
    }

    public Boolean isIgnoreSslErrors() {
        return getPropertyAsBoolean("ignoreSslErrors");
    }

    public void setStreamingConnection(Boolean streamingConnection) {
        setProperty("streamingConnection", streamingConnection);
    }

    public Boolean isStreamingConnection() {
        return getPropertyAsBoolean("streamingConnection");
    }


    public Boolean isStompProtocol() {
        return getPropertyAsBoolean("stompProtocol");
    }

    public void setStompProtocol(final Boolean stompProtocol) {
        setProperty("stompProtocol", stompProtocol);
    }

    public void setConnectionId(String connectionId) {
        setProperty("connectionId", connectionId);
    }

    public String getConnectionId() {
        return getPropertyAsString("connectionId");
    }

    public void setConnectPattern(String connectPattern) {
        setProperty("connectPattern", connectPattern);
    }

    public String getConnectPattern() {
        return getPropertyAsString("connectPattern");
    }

    public void setSubscribePattern(String subscribePattern) {
        setProperty("subscribePattern", subscribePattern);
    }

    public String getSubscribePattern() {
        return getPropertyAsString("subscribePattern");
    }

    public void setCloseConncectionPattern(String closeConncectionPattern) {
        setProperty("closeConncectionPattern", closeConncectionPattern);
    }

    public String getCloseConncectionPattern() {
        return getPropertyAsString("closeConncectionPattern");
    }

    public void setProxyAddress(String proxyAddress) {
        setProperty("proxyAddress", proxyAddress);
    }

    public String getProxyAddress() {
        return getPropertyAsString("proxyAddress");
    }

    public void setProxyPassword(String proxyPassword) {
        setProperty("proxyPassword", proxyPassword);
    }

    public String getProxyPassword() {
        return getPropertyAsString("proxyPassword");
    }

    public void setProxyPort(String proxyPort) {
        setProperty("proxyPort", proxyPort);
    }

    public String getProxyPort() {
        return getPropertyAsString("proxyPort");
    }

    public void setProxyUsername(String proxyUsername) {
        setProperty("proxyUsername", proxyUsername);
    }

    public String getProxyUsername() {
        return getPropertyAsString("proxyUsername");
    }

    public String getResponsesCount() {
        return getPropertyAsString("responsesCount", "1");
    }

    public void setResponsesCount(String responsesCount) {
        setProperty("responsesCount", responsesCount);
    }

    public String getQueryString(String contentEncoding) {
        // Check if the sampler has a specified content encoding
        if (JOrphanUtils.isBlank(contentEncoding)) {
            // We use the encoding which should be used according to the HTTP spec, which is UTF-8
            contentEncoding = EncoderCache.URL_ARGUMENT_ENCODING;
        }
        StringBuilder buf = new StringBuilder();
        PropertyIterator iter = getQueryStringParameters().iterator();
        boolean first = true;
        while (iter.hasNext()) {
            HTTPArgument item;
            Object objectValue = iter.next().getObjectValue();
            try {
                item = (HTTPArgument) objectValue;
            } catch (ClassCastException e) {
                item = new HTTPArgument((Argument) objectValue);
            }
            final String encodedName = item.getEncodedName();
            if (encodedName.length() == 0) {
                continue; // Skip parameters with a blank name (allows use of optional variables in parameter lists)
            }
            if (!first) {
                buf.append(QRY_SEP);
            } else {
                first = false;
            }
            buf.append(encodedName);
            if (item.getMetaData() == null) {
                buf.append(ARG_VAL_SEP);
            } else {
                buf.append(item.getMetaData());
            }

            // Encode the parameter value in the specified content encoding
            try {
                buf.append(item.getEncodedValue(contentEncoding));
            } catch (UnsupportedEncodingException e) {
                log.warn("Unable to encode parameter in encoding " + contentEncoding + ", parameter value not included in query string");
            }
        }
        return buf.toString();
    }

    public void setQueryStringParameters(Arguments queryStringParameters) {
        setProperty(new TestElementProperty("queryStringParameters", queryStringParameters));
    }

    public Arguments getQueryStringParameters() {
        Arguments args = (Arguments) getProperty("queryStringParameters").getObjectValue();
        return args;
    }


    @Override
    public void testStarted() {
        testStarted("unknown");
    }

    @Override
    public void testStarted(String host) {
        connectionList = new ConcurrentHashMap<>();
    }

    @Override
    public void testEnded() {
        testEnded("unknown");
    }

    @Override
    public void testEnded(String host) {
        for (ServiceSocket socket : connectionList.values()) {
            socket.close();
        }
    }


}
