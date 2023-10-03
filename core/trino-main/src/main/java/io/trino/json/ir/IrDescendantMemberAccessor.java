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
package io.trino.json.ir;

import io.trino.spi.type.Type;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record IrDescendantMemberAccessor(IrPathNode base, String key, Optional<Type> type)
        implements IrPathNode
{
    public IrDescendantMemberAccessor
    {
        requireNonNull(type, "type is null");
        requireNonNull(base, "descendant member accessor base is null");
        requireNonNull(key, "key is null");
    }

    @Override
    public <R, C> R accept(IrJsonPathVisitor<R, C> visitor, C context)
    {
        return visitor.visitIrDescendantMemberAccessor(this, context);
    }
}
