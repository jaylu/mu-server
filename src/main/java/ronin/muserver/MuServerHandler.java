package ronin.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
    static final AttributeKey<String> PROTO_ATTRIBUTE = AttributeKey.newInstance("proto");

    private final List<AsyncMuHandler> handlers;
	private final ConcurrentHashMap<ChannelHandlerContext, State> state = new ConcurrentHashMap<>();

	public MuServerHandler(List<AsyncMuHandler> handlers) {
		this.handlers = handlers;
	}

	private static final class State {
		public final AsyncContext asyncContext;
		public final AsyncMuHandler handler;
		private State(AsyncContext asyncContext, AsyncMuHandler handler) {
			this.asyncContext = asyncContext;
			this.handler = handler;
		}
	}

	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;

			if (request.decoderResult().isFailure()) {
				handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
			} else {

				HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);

				boolean handled = false;
                Attribute<String> proto = ctx.channel().attr(PROTO_ATTRIBUTE);

                NettyRequestAdapter muRequest = new NettyRequestAdapter(proto.get(), request);
                AsyncContext asyncContext = new AsyncContext(muRequest, new NettyResponseAdaptor(ctx, muRequest, response));

				for (AsyncMuHandler handler : handlers) {
					handled = handler.onHeaders(asyncContext, asyncContext.request.headers());
					if (handled) {
						state.put(ctx, new State(asyncContext, handler));
						break;
					}
				}
				if (!handled) {
					System.out.println("No handler found");
					asyncContext.response.status(404);
					asyncContext.complete();
				}
			}

		} else if (msg instanceof HttpContent) {
			HttpContent content = (HttpContent) msg;
			State state = this.state.get(ctx);
			if (state == null) {
				// ummmmmm
				System.out.println("Got a chunk of message for an unknown request");
			} else {
				ByteBuf byteBuf = content.content();
				if (byteBuf.capacity() > 0) {
					// TODO: why does the buffer need to be copied?
					ByteBuffer byteBuffer = byteBuf.copy().nioBuffer();
					state.handler.onRequestData(state.asyncContext, byteBuffer);
				}
				if (msg instanceof LastHttpContent) {
					state.handler.onRequestComplete(state.asyncContext);
				}
			}
		}
	}

	private void handleHttpRequestDecodeFailure(ChannelHandlerContext ctx, Throwable cause) {
		String message = "Server error";
		int code = 500;
		if (cause instanceof TooLongFrameException) {
			if (cause.getMessage().contains("header is larger")) {
				code = 431;
				message = "HTTP headers too large";
			} else if (cause.getMessage().contains("line is larger")) {
				code = 414;
				message = "URI too long";
			}
		}
		FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(message.getBytes(UTF_8)));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, message.length());
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}
}
