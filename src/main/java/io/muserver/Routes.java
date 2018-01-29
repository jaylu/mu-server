package io.muserver;

import io.muserver.rest.PathMatch;
import io.muserver.rest.UriPattern;

public class Routes {
	public static MuHandler route(Method method, String uriTemplate, RouteHandler muHandler) {
        UriPattern uriPattern = UriPattern.uriTemplateToRegex(uriTemplate);

        return (request, response) -> {
			boolean methodMatches = method == null || method.equals(request.method());
			if (methodMatches) {
                PathMatch matcher = uriPattern.matcher(request.uri());
                if (matcher.fullyMatches()) {
                    muHandler.handle(request, response, matcher.params());
                    return true;
                }
			}
			return false;
		};
	}

	private Routes() {}
}
