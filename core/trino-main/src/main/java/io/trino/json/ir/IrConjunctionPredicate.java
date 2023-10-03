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

import static java.util.Objects.requireNonNull;

public record IrConjunctionPredicate(IrPredicate left, IrPredicate right)
        implements IrPredicate
{
    public IrConjunctionPredicate
    {
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
    }

    @Override
    public <R, C> R accept(IrJsonPathVisitor<R, C> visitor, C context)
    {
        return visitor.visitIrConjunctionPredicate(this, context);
    }
}
