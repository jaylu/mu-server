package io.muserver;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.RedirectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;

class NettyHandlerAdapter {


    private static final Logger log = LoggerFactory.getLogger(NettyHandlerAdapter.class);
    private final List<MuHandler> muHandlers;
    private final ExecutorService executor;
    private final List<ResponseCompleteListener> completeListeners;

    NettyHandlerAdapter(ExecutorService executor, List<MuHandler> muHandlers, List<ResponseCompleteListener> completeListeners) {
        this.executor = executor;
        this.muHandlers = muHandlers;
        this.completeListeners = completeListeners;
    }

    void onHeaders(HttpExchange muCtx) {

        executor.execute(() -> {
            if (muCtx.state().endState()) {
                return;
            }
            NettyRequestAdapter request = muCtx.request;
            NettyResponseAdaptor response = muCtx.response;
            try {
                boolean handled = false;
                for (MuHandler muHandler : muHandlers) {
                    handled = muHandler.handle(request, response);
                    if (handled) {
                        break;
                    }
                    if (request.isAsync()) {
                        throw new IllegalStateException(muHandler.getClass() + " returned false however this is not allowed after starting to handle a request asynchronously.");
                    }
                }
                if (!handled) {
                    throw new NotFoundException();
                }
                if (!request.isAsync() && !response.outputState().endState()) {
                    response.flushAndCloseOutputStream();
                    muCtx.block(muCtx::complete);
                }
            } catch (Throwable ex) {
                useCustomExceptionHandlerOrFireIt(muCtx, ex);
            }
        });
    }

    static void useCustomExceptionHandlerOrFireIt(HttpExchange exchange, Throwable ex) {
        MuServerImpl server = (MuServerImpl) exchange.request.server();
        try {
            if (server.unhandledExceptionHandler != null && !(ex instanceof RedirectionException) && server.unhandledExceptionHandler.handle(exchange.request, exchange.response, ex)) {
                exchange.response.flushAndCloseOutputStream();
                exchange.block(exchange::complete);
            } else {
                exchange.fireException(ex);
            }
        } catch (Throwable handlerException) {
            exchange.fireException(handlerException);
        }
    }

    void onResponseComplete(ResponseInfo info, MuStatsImpl serverStats, MuStatsImpl connectionStats) {
        connectionStats.onRequestEnded(info.request());
        serverStats.onRequestEnded(info.request());
        if (completeListeners != null) {
            for (ResponseCompleteListener listener : completeListeners) {
                try {
                    listener.onComplete(info);
                } catch (Exception e) {
                    log.error("Error from completion listener", e);
                }
            }
        }
    }
}
