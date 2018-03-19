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
package com.facebook.presto.plugin.geospatial;

import com.facebook.presto.sql.planner.iterative.rule.TransformSpatialPredicateToJoin;
import com.facebook.presto.sql.planner.iterative.rule.test.BaseRuleTest;
import com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder;
import com.facebook.presto.sql.planner.iterative.rule.test.RuleAssert;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.expression;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.project;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.spatialJoin;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.values;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.INNER;

public class TestTransformSpatialPredicateToJoin
        extends BaseRuleTest
{
    public TestTransformSpatialPredicateToJoin()
    {
        super(new GeoPlugin());
    }

    @Test
    public void testDoesNotFire()
    {
        // scalar expression
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText('POLYGON ...'), b)"),
                                p.join(INNER,
                                        p.values(),
                                        p.values(p.symbol("b")))))
                .doesNotFire();

        // OR operand
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt), point) OR name_1 != name_2"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                        p.values(p.symbol("point", GEOMETRY), p.symbol("name_2")))))
                .doesNotFire();

        // NOT operator
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("NOT ST_Contains(ST_GeometryFromText(wkt), point)"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                        p.values(p.symbol("point", GEOMETRY), p.symbol("name_2")))))
                .doesNotFire();
    }

    @Test
    public void testConvertToSpatialJoin()
    {
        // symbols
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(a, b)"),
                                p.join(INNER,
                                        p.values(p.symbol("a")),
                                        p.values(p.symbol("b")))))
                .matches(
                        spatialJoin("ST_Contains(a, b)",
                                values(ImmutableMap.of("a", 0)),
                                values(ImmutableMap.of("b", 0))));

        // AND
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("name_1 != name_2 AND ST_Contains(a, b)"),
                                p.join(INNER,
                                        p.values(p.symbol("a"), p.symbol("name_1")),
                                        p.values(p.symbol("b"), p.symbol("name_2")))))
                .matches(
                        spatialJoin("name_1 != name_2 AND ST_Contains(a, b)",
                                values(ImmutableMap.of("a", 0, "name_1", 1)),
                                values(ImmutableMap.of("b", 0, "name_2", 1))));

        // AND
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(a1, b1) AND ST_Contains(a2, b2)"),
                                p.join(INNER,
                                        p.values(p.symbol("a1"), p.symbol("a2")),
                                        p.values(p.symbol("b1"), p.symbol("b2")))))
                .matches(
                        spatialJoin("ST_Contains(a1, b1) AND ST_Contains(a2, b2)",
                                values(ImmutableMap.of("a1", 0, "a2", 1)),
                                values(ImmutableMap.of("b1", 0, "b2", 1))));
    }

    @Test
    public void testPushDownFirstArgument()
    {
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt), point)"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR)),
                                        p.values(p.symbol("point", GEOMETRY)))))
                .matches(
                        spatialJoin("ST_Contains(st_geometryfromtext, point)",
                                project(ImmutableMap.of("st_geometryfromtext", expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0))),
                                values(ImmutableMap.of("point", 0))));

        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(0, 0))"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR)),
                                        p.values())))
                .doesNotFire();
    }

    @Test
    public void testPushDownSecondArgument()
    {
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(polygon, ST_Point(lng, lat))"),
                                p.join(INNER,
                                        p.values(p.symbol("polygon", GEOMETRY)),
                                        p.values(p.symbol("lat"), p.symbol("lng")))))
                .matches(
                        spatialJoin("ST_Contains(polygon, st_point)",
                                values(ImmutableMap.of("polygon", 0)),
                                project(ImmutableMap.of("st_point", expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1)))));

        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText('POLYGON ...'), ST_Point(lng, lat))"),
                                p.join(INNER,
                                        p.values(),
                                        p.values(p.symbol("lat"), p.symbol("lng")))))
                .doesNotFire();
    }

    @Test
    public void testPushDownBothArguments()
    {
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR)),
                                        p.values(p.symbol("lat"), p.symbol("lng")))))
                .matches(
                        spatialJoin("ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_geometryfromtext", expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0))),
                                project(ImmutableMap.of("st_point", expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1)))));
    }

    @Test
    public void testPushDownOppositeOrder()
    {
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))"),
                                p.join(INNER,
                                        p.values(p.symbol("lat"), p.symbol("lng")),
                                        p.values(p.symbol("wkt", VARCHAR)))))
                .matches(
                        spatialJoin("ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_point", expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1))),
                                project(ImmutableMap.of("st_geometryfromtext", expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0)))));
    }

    @Test
    public void testPushDownAnd()
    {
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("name_1 != name_2 AND ST_Contains(ST_GeometryFromText(wkt), ST_Point(lng, lat))"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt", VARCHAR), p.symbol("name_1")),
                                        p.values(p.symbol("lat"), p.symbol("lng"), p.symbol("name_2")))))
                .matches(
                        spatialJoin("name_1 != name_2 AND ST_Contains(st_geometryfromtext, st_point)",
                                project(ImmutableMap.of("st_geometryfromtext", expression("ST_GeometryFromText(wkt)")), values(ImmutableMap.of("wkt", 0, "name_1", 1))),
                                project(ImmutableMap.of("st_point", expression("ST_Point(lng, lat)")), values(ImmutableMap.of("lat", 0, "lng", 1, "name_2", 2)))));

        // Multiple spatial functions - only the first one is being processed
        assertRuleApplication()
                .on(p ->
                        p.filter(PlanBuilder.expression("ST_Contains(ST_GeometryFromText(wkt1), geometry1) AND ST_Contains(ST_GeometryFromText(wkt2), geometry2)"),
                                p.join(INNER,
                                        p.values(p.symbol("wkt1", VARCHAR), p.symbol("wkt2", VARCHAR)),
                                        p.values(p.symbol("geometry1"), p.symbol("geometry2")))))
                .matches(
                        spatialJoin("ST_Contains(st_geometryfromtext, geometry1) AND ST_Contains(ST_GeometryFromText(wkt2), geometry2)",
                                project(ImmutableMap.of("st_geometryfromtext", expression("ST_GeometryFromText(wkt1)")), values(ImmutableMap.of("wkt1", 0, "wkt2", 1))),
                                values(ImmutableMap.of("geometry1", 0, "geometry2", 1))));
    }

    private RuleAssert assertRuleApplication()
    {
        return tester().assertThat(new TransformSpatialPredicateToJoin(tester().getMetadata()));
    }
}
