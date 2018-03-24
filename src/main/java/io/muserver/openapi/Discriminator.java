package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

public class Discriminator implements JsonWriter {
    public final String propertyName;
    public final Map<String, String> mapping;

    public Discriminator(String propertyName, Map<String, String> mapping) {
        notNull("propertyName", propertyName);
        this.propertyName = propertyName;
        this.mapping = mapping;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !append(writer, "propertyName", propertyName, isFirst);
        isFirst = !append(writer, "mapping", mapping, isFirst);
        writer.write('}');
    }
}