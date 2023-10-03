/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.google.common.collect.ImmutableList;
import org.gaul.modernizer_maven_annotations.SuppressModernizer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static io.trino.cli.FormatUtils.formatHexDump;
import static java.util.Objects.requireNonNull;

public class JsonPrinter
        implements OutputPrinter
{
    private final List<String> fieldNames;
    private final Writer writer;

    public JsonPrinter(List<String> fieldNames, Writer writer)
    {
        this.fieldNames = ImmutableList.copyOf(requireNonNull(fieldNames, "fieldNames is null"));
        this.writer = requireNonNull(writer, "writer is null");
    }

    @Override
    public void printRows(List<List<?>> rows, boolean complete)
            throws IOException
    {
        JsonFactory jsonFactory = jsonFactory();
        try (JsonGenerator jsonGenerator = jsonFactory.createGenerator(writer)) {
            jsonGenerator.setRootValueSeparator(null);
            for (List<?> row : rows) {
                jsonGenerator.writeStartObject();
                for (int position = 0; position < row.size(); position++) {
                    String columnName = fieldNames.get(position);
                    jsonGenerator.writeObjectField(columnName, formatValue(row.get(position)));
                }
                jsonGenerator.writeEndObject();
                jsonGenerator.writeRaw('\n');
                jsonGenerator.flush();
            }
        }
    }

    @Override
    public void finish()
            throws IOException
    {
        writer.flush();
    }

    private static Object formatValue(Object o)
    {
        if (o instanceof byte[]) {
            return formatHexDump((byte[]) o);
        }
        return o;
    }

    @SuppressModernizer
    // JsonFactoryBuilder usage is intentional as we don't want to bring additional dependency on plugin-toolkit module
    private static JsonFactory jsonFactory()
    {
        return new JsonFactoryBuilder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNumberLength(Integer.MAX_VALUE)
                        .maxNestingDepth(Integer.MAX_VALUE)
                        .maxStringLength(Integer.MAX_VALUE)
                        .build())
                .build()
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }
}
