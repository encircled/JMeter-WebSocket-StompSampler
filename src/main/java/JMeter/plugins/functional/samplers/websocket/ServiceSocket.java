/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package JMeter.plugins.functional.samplers.websocket;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * @author Maciej Zaleski
 */
@WebSocket(maxTextMessageSize = 256 * 1024 * 1024)
public class ServiceSocket {

    protected WebSocketSampler parent;
    protected WebSocketClient client;
    private static final Logger log = LoggingManager.getLoggerForClass();
    protected Deque<String> responeBacklog = new LinkedList<>();
    protected Integer error = 0;
    protected StringBuffer logMessage = new StringBuffer();
    protected CountDownLatch openLatch = new CountDownLatch(1);
    protected CountDownLatch closeLatch = new CountDownLatch(1);
    protected CountDownLatch connectedLatch = new CountDownLatch(1);
    protected CountDownLatch subscribeLatch;
    protected Session session = null;
    protected String connectPattern;
    protected String subscribePattern;
    protected String disconnectPattern;
    protected int messageCounter = 1;
    protected Pattern connectedExpression;
    protected Pattern subscribeExpression;
    protected Pattern disconnectExpression;
    protected boolean connected = false;
    private String sessionId;

    public ServiceSocket(WebSocketSampler parent, WebSocketClient client) {
        initialize(parent, client, false);
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        synchronized (parent) {
            log("Received message {" + sessionId + "}: " + msg);

            String length = " (" + msg.length() + " bytes)";
            logMessage.append(" - Received message #").append(messageCounter).append(length);
            logMessage.append(msg);
            addResponseMessage("[Message " + (messageCounter++) + "]\n" + msg + "\n\n");

            if (connectedExpression == null || connectedExpression.matcher(msg).find()) {
                logMessage.append("; matched connected pattern").append("\n");
                connectedLatch.countDown();
            } else if (subscribeExpression == null || subscribeExpression.matcher(msg).find()) {
                logMessage.append("; matched subscribe pattern").append("\n");
                subscribeLatch.countDown();
            } else if (!disconnectPattern.isEmpty() && disconnectExpression.matcher(msg).find()) {
                logMessage.append("; matched connection close pattern").append("\n");
                closeLatch.countDown();
                close(StatusCode.NORMAL, "JMeter closed session.");
            } else {
                logMessage.append("; didn't match any pattern").append("\n");
            }
        }
    }

    @OnWebSocketConnect
    public void onOpen(Session session) {
        logMessage.append(" - WebSocket conection has been opened").append("\n");
        log.debug("Connect " + session.isOpen());
        this.session = session;
        connected = true;
        openLatch.countDown();
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        if (statusCode != 1000) {
            log.error("Disconnect " + statusCode + ": " + reason);
            logMessage.append(" - WebSocket conection closed unexpectedly by the server: [").append(statusCode).append("] ").append(reason).append("\n");
            error = statusCode;
        } else {
            logMessage.append(" - WebSocket conection has been successfully closed by the server").append("\n");
            log.debug("Disconnect " + statusCode + ": " + reason);
        }

        //Notify connection opening and closing latches of the closed connection
        openLatch.countDown();
        closeLatch.countDown();
        connectedLatch.countDown();
        connected = false;
    }

    /**
     * @return response message made of messages saved in the responeBacklog cache
     */
    public String getResponseMessage() {
        synchronized (parent) {
            StringBuilder responseMessage = new StringBuilder();

            //Iterate through response messages saved in the responeBacklog cache
            for (String responeBacklog : responeBacklog) {
                responseMessage.append(responeBacklog);
            }

            return responseMessage.toString();
        }
    }

    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for messages for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.closeLatch.await(duration, unit);

        if (!parent.isStreamingConnection()) {
            close(StatusCode.NORMAL, "JMeter closed session.");
        } else {
            logMessage.append(" - Leaving streaming connection open").append("\n");
        }

        return res;
    }

    public boolean awaitConnected(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for messages for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.connectedLatch.await(duration, unit);

        if (!parent.isStreamingConnection()) {
            close(StatusCode.NORMAL, "JMeter closed session.");
        } else {
            logMessage.append(" - Leaving streaming connection open").append("\n");
        }

        return res;
    }

    public boolean awaitSubscribe(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for messages for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.subscribeLatch.await(duration, unit);

        if (!parent.isStreamingConnection()) {
            close(StatusCode.NORMAL, "JMeter closed session.");
        } else {
            logMessage.append(" - Leaving streaming connection open").append("\n");
        }

        return res;
    }

    public boolean awaitOpen(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for the server connection for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.openLatch.await(duration, unit);

        if (connected) {
            logMessage.append(" - Connection established").append("\n");
        } else {
            logMessage.append(" - Cannot connect to the remote server").append("\n");
        }

        return res;
    }

    public void sendMessage(String message) throws IOException {
        log("\n** send message ** session id {" + sessionId + "} : " + message);
        if (session != null && session.getRemote() != null) {
            session.getRemote().sendString(message);
        } else {
            log("\nCant send message, session is not available!\n");
        }
    }

    public void close() {
        close(StatusCode.NORMAL, "JMeter closed session.");
    }

    public void close(int statusCode, String statusText) {
        //Closing WebSocket session
        if (session != null) {
            session.close(statusCode, statusText);
            logMessage.append(" - WebSocket session closed by the client").append("\n");
        } else {
            logMessage.append(" - WebSocket session wasn't started (...that's odd)").append("\n");
        }


        //Stoping WebSocket client; thanks m0ro
        try {
            client.stop();
            logMessage.append(" - WebSocket client closed by the client").append("\n");
        } catch (Exception e) {
            logMessage.append(" - WebSocket client wasn't started (...that's odd)").append("\n");
        }
    }

    /**
     * @return the error
     */
    public Integer getError() {
        return error;
    }

    /**
     * @return the logMessage
     */
    public String getLogMessage() {
        log("\n\n[Variables]\n");
        logMessage.append(" - Message count: ").append(messageCounter - 1).append("\n");

        return logMessage.toString();
    }

    public void log(String message) {
        logMessage.append(message);
    }

    protected void initializePatterns() {
        try {
            logMessage.append(" - Using connect message pattern \"").append(connectPattern).append("\"\n");
            connectedExpression = StringUtils.isNotEmpty(connectPattern) ? Pattern.compile(connectPattern) : null;
        } catch (Exception ex) {
            logMessage.append(" - Invalid connect message regular expression pattern: ").append(ex.getLocalizedMessage()).append("\n");
            log.error("Invalid connect message regular expression pattern: " + ex.getLocalizedMessage());
            connectedExpression = null;
        }
        try {
            logMessage.append(" - Using response message pattern \"").append(subscribePattern).append("\"\n");
            subscribeExpression = StringUtils.isNotEmpty(subscribePattern) ? Pattern.compile(subscribePattern) : null;
        } catch (Exception ex) {
            logMessage.append(" - Invalid response message regular expression pattern: ").append(ex.getLocalizedMessage()).append("\n");
            log.error("Invalid response message regular expression pattern: " + ex.getLocalizedMessage());
            subscribeExpression = null;
        }

        try {
            logMessage.append(" - Using disconnect pattern \"").append(disconnectPattern).append("\"\n");
            disconnectExpression = StringUtils.isNotEmpty(disconnectPattern) ? Pattern.compile(disconnectPattern) : null;
        } catch (Exception ex) {
            logMessage.append(" - Invalid disconnect regular expression pattern: ").append(ex.getLocalizedMessage()).append("\n");
            log.error("Invalid disconnect regular regular expression pattern: " + ex.getLocalizedMessage());
            disconnectExpression = null;
        }

    }

    /**
     * @return the connected
     */
    public boolean isConnected() {
        return connected;
    }

    public void initialize(WebSocketSampler parent, WebSocketClient client, boolean isReuse) {
        this.parent = parent;
        if (client != null) {
            this.client = client;
        }

        responeBacklog = new LinkedList<>();
        //Evaluate response matching patterns in case thay contain JMeter variables (i.e. ${var})
        connectPattern = new CompoundVariable(parent.getConnectPattern()).execute();
        subscribePattern = new CompoundVariable(parent.getSubscribePattern()).execute();
        disconnectPattern = new CompoundVariable(parent.getCloseConncectionPattern()).execute();
        subscribeLatch = new CountDownLatch(Integer.parseInt(parent.getResponsesCount()));
        initializePatterns();

        if (isReuse) {
            logMessage = new StringBuffer();
            logMessage.append("\n\n[Execution Flow]\n");
            logMessage.append(" - Reusing exising connection\n");
            error = 0;
        } else {
            log("\n\n[Execution Flow]\n");
            log(" - Opening new connection\n");
        }
    }

    private void addResponseMessage(String message) {
        int messageBacklog = 25;

        while (responeBacklog.size() >= messageBacklog) {
            responeBacklog.poll();
        }
        responeBacklog.add(message);
    }

    public void setSessionId(final String sessionId) {
        this.sessionId = sessionId;
        logMessage.append(" Session id : ").append(sessionId).append("\n");
    }
}
