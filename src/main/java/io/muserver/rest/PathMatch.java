package io.muserver.rest;

import jakarta.ws.rs.core.PathSegment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The result of matching a template URI against a real request URI. If there is a match, then any path parameters
 * are available in the {@link #params()} map.
 */
public class PathMatch {

    /**
     * An empty match
     */
    public static final PathMatch EMPTY_MATCH = new PathMatch(true, Collections.emptyMap(), Pattern.compile("").matcher(""));

    private final boolean matches;
    private final Map<String, PathSegment> params;
    private final Matcher matcher;

    PathMatch(boolean matches, Map<String, PathSegment> params, Matcher matcher) {
        this.matches = matches;
        this.params = params;
        this.matcher = matcher;
    }

    /**
     * @return Returns true if the beginning of the checked URI matches this pattern. For example
     * if the pattern is <code>/abc</code> then this will return <code>true</code> for <code>/abc</code>
     * and <code>/abc/def</code> etc.
     */
    public boolean prefixMatches() {
        return matches;
    }
    /**
     * @return Returns true if the checked URI matches this pattern. For example
     * if the pattern is <code>/abc</code> then this will return <code>true</code> for <code>/abc</code>
     * but false for <code>/abc/def</code> etc.
     */
    public boolean fullyMatches() {
        return matches && lastGroup() == null;
    }

    /**
     * Returns a mapping of URI names to path params. For example the template URI is <code>/fruit/{name}</code>
     * then <code>pathMatch.params().get("name")</code> will return <code>orange</code> if the URI was <code>/fruit/orange</code>
     * @return Returns a read-only map of path parameters names to values.
     */
    public Map<String, String> params() {
        Map<String, String> copy = new HashMap<>(params.size());
        for (Map.Entry<String, PathSegment> entry : params.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().getPath());
        }
        return copy;
    }
    /**
     * Returns a mapping of URI names to path segments. For example the template URI is <code>/fruit/{name}</code>
     * then <code>pathMatch.params().get("name").getPath()</code> will return <code>orange</code> if the URI was <code>/fruit/orange</code>.
     * <p>The segments also contain matrix parameter info.</p>
     * @return Returns a read-only map of path parameters names to values.
     */
    public Map<String, PathSegment> segments() {
        return params;
    }

    /**
     * @return Returns the regex Matcher that was used
     */
    public Matcher regexMatcher() {
        return matcher;
    }

    /**
     * @return Returns the last captured group value, which may be null
     */
    String lastGroup() {
        String group = matcher.group(matcher.groupCount());
        return "/".equals(group) ? null : group;
    }

    @Override
    public String toString() {
        return "PathMatch{" +
            "matches=" + matches +
            ", params=" + params +
            '}';
    }
}
