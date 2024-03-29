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
package io.trino.plugin.redis.decoder;

import io.trino.decoder.DecoderColumnHandle;
import io.trino.decoder.FieldValueProvider;
import io.trino.decoder.RowDecoder;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * Implementations decode a row from map and add field value providers for all decodable columns.
 */
public interface RedisRowDecoder
        extends RowDecoder
{
    /**
     * Decodes a given map into field values.
     *
     * @param dataMap The row data as fields map
     * @return Returns mapping from column handle to decoded value. Unmapped columns will be reported as null. Optional.empty() signals decoding error.
     */
    Optional<Map<DecoderColumnHandle, FieldValueProvider>> decodeRow(@Nullable Map<String, String> dataMap);
}
