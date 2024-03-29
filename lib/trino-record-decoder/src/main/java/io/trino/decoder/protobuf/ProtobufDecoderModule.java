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
package io.trino.decoder.protobuf;

import com.google.inject.Binder;
import com.google.inject.Module;
import io.trino.decoder.RowDecoderFactory;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.MapBinder.newMapBinder;

public class ProtobufDecoderModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        binder.bind(DynamicMessageProvider.Factory.class).to(FixedSchemaDynamicMessageProvider.Factory.class).in(SINGLETON);
        binder.bind(DescriptorProvider.class).to(DummyDescriptorProvider.class).in(SINGLETON);
        newMapBinder(binder, String.class, RowDecoderFactory.class).addBinding(ProtobufRowDecoder.NAME).to(ProtobufRowDecoderFactory.class).in(SINGLETON);
    }
}
