package io.muserver.rest;

import org.junit.Test;

import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CORSConfigTest {

    @Test
    public void everythingCanBeAllowed() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withAllOriginsAllowed().build();
        assertThat(config.allowCors("http://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.com.au"), is(true));
    }

    @Test
    public void regexPatternsCanBeUsed() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withAllowedOriginRegex(Pattern.compile("http[s]?://.*\\.nz")).build();
        assertThat(config.allowCors("http://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.com.au"), is(false));
    }

    @Test
    public void regexStringsCanBeUsed() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withAllowedOriginRegex("http[s]?://.*\\.nz").build();
        assertThat(config.allowCors("http://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.co.nz"), is(true));
        assertThat(config.allowCors("https://apprunner.com.au"), is(false));
    }

    @Test
    public void theRegexCanBeExcludeTheScheme() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withAllowedOriginRegex(Pattern.compile(".*localhost.*")).build();
        assertThat(config.allowCors("http://localhost:8080"), is(true));
        assertThat(config.allowCors("https://localhost"), is(true));
        assertThat(config.allowCors("https://apprunner.com.au"), is(false));
    }

    @Test
    public void domainNamesCanBeExplicitlyListed() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withAllowedOrigins(asList("http://localhost:8080", "https://localhost")).build();
        assertThat(config.allowCors("http://localhost:8080"), is(true));
        assertThat(config.allowCors("https://localhost"), is(true));
        assertThat(config.allowCors("https://apprunner.com.au"), is(false));
    }

    @Test
    public void localhostUrlsCanBeAllowedEasily() {
        CORSConfig config = CORSConfigBuilder.corsConfig().withLocalhostAllowed().build();
        assertThat(config.allowCors("http://localhost"), is(true));
        assertThat(config.allowCors("https://localhost"), is(true));
        assertThat(config.allowCors("https://localhost:8080"), is(true));
        assertThat(config.allowCors("http://localhost:8080"), is(true));
        assertThat(config.allowCors("https://localhoster"), is(false));
        assertThat(config.allowCors("https://localhost.com"), is(false));
    }

}