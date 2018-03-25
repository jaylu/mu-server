package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;

import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class OperationObjectTest {

    @Test
    public void canGenerateJson() throws IOException {
        ResponsesObject responses = responsesObject().withHttpStatusCodes(
            Collections.singletonMap("200", responseObject().withDescription("Success").build())).build();

        OperationObject operation = operationObject().withTags(asList("pets"))
            .withSummary("Find pets by ID")
            .withDescription("Returns pets based on ID")
            .withExternalDocs(new ExternalDocumentationObject("The docs on the web", URI.create("http://muserver.io")))
            .withOperationId("some.unique.id")
            .withResponses(responses).build();


        StringWriter writer = new StringWriter();
        operation.writeJson(writer);

        assertThat(writer.toString(), equalTo("{\"tags\":[\"pets\"],\"summary\":\"Find pets by ID\",\"description\":\"Returns pets based on ID\",\"externalDocs\":{\"description\":\"The docs on the web\",\"url\":\"http://muserver.io\"},\"operationId\":\"some.unique.id\",\"responses\":{\"200\":{\"description\":\"Success\"}},\"deprecated\":false}"));
    }

}