package io.muserver.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.Test;

import static java.net.URI.create;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class ResourceClassTest {

    private final SchemaObjectCustomizer customizer = new CompositeSchemaObjectCustomizer(emptyList());

    @Test
    public void canCreate() {
        ResourceClass rc = ResourceClass.fromObject(new Fruit(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(rc.matches(create("api/fruit")), equalTo(false));
        assertThat(rc.matches(create("api/fruits")), equalTo(true));
        assertThat(rc.matches(create("api/fruits?yeah=yeah")), equalTo(true));
        assertThat(rc.matches(create("api/fruits/orange")), equalTo(true));
    }

    @Test
    public void pathParamsCanBeInheritedIfThereAreNoJaxAnnotations() {
        ResourceClass rc = ResourceClass.fromObject(new ConcreteWidget(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(rc.matches(create("api/widgets")), equalTo(true));
    }

    @Test
    public void canIdentifyNonSubResourceMethods() {
        @Path("/{s:.*}")
        class Optionsy {
            @GET
            public String optionsGet() { return ""; }
            @OPTIONS
            public String options() { return ""; }
            @Path("more")
            @OPTIONS
            public String more() { return ""; }
        }

        ResourceClass resourceClass = ResourceClass.fromObject(new Optionsy(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(resourceClass.resourceMethods, hasSize(3));
        assertThat(resourceClass.nonSubResourceMethods(), hasSize(2));
        assertThat(resourceClass.subResourceMethods(), hasSize(1));

    }

    @Path("/api/fruits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }

        @Path("/:name")
        @GET
        public String get(@PathParam("name") String name) {
            switch (name) {
                case "apple":
                    return "{ \"name\": \"apple\" }";
                case "orange":
                    return "{ \"name\": \"orange\" }";
            }
            return "not found";
        }

        public void notEligible() {
        }

    }

    @Path("/api/widgets")
    private static abstract class BaseWidgetResource {

    }
    private static class ConcreteWidget extends BaseWidgetResource {

    }

}