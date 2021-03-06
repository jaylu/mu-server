package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class StatusLogger {
    private static final Logger log = LoggerFactory.getLogger(StatusLogger.class);
    public static void logRequests(Collection<MuRequest> muRequests) {
        int count = 0;
        for (MuRequest muRequest : muRequests) {
            NettyRequestAdapter req = (NettyRequestAdapter) muRequest;
            HttpExchange exchange = req.exchange();
            log.info(count + ": exchange state "+ exchange.state() + " with duration " + exchange.duration() + "ms. Req: " + req + " (status " + req.requestState() + ") with response " + exchange.response() + " ");
            count++;
        }
    }
}
