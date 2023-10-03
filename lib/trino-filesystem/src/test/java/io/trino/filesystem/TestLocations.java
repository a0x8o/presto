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
package io.trino.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.trino.filesystem.Locations.appendPath;
import static io.trino.filesystem.Locations.areDirectoryLocationsEquivalent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestLocations
{
    private static Stream<Arguments> locations()
    {
        return Stream.of(
                Arguments.of("test_dir", "", "test_dir/"),
                Arguments.of("", "test_file.txt", "/test_file.txt"),
                Arguments.of("test_dir", "test_file.txt", "test_dir/test_file.txt"),
                Arguments.of("/test_dir", "test_file.txt", "/test_dir/test_file.txt"),
                Arguments.of("test_dir/", "test_file.txt", "test_dir/test_file.txt"),
                Arguments.of("/test_dir/", "test_file.txt", "/test_dir/test_file.txt"),
                Arguments.of("test_dir", "test_dir2/", "test_dir/test_dir2/"),
                Arguments.of("test_dir/", "test_dir2/", "test_dir/test_dir2/"),
                Arguments.of("s3:/test_dir", "test_file.txt", "s3:/test_dir/test_file.txt"),
                Arguments.of("s3://test_dir", "test_file.txt", "s3://test_dir/test_file.txt"),
                Arguments.of("s3://test_dir/", "test_file.txt", "s3://test_dir/test_file.txt"),
                Arguments.of("s3://dir_with_space ", "test_file.txt", "s3://dir_with_space /test_file.txt"),
                Arguments.of("s3://dir_with_double_space  ", "test_file.txt", "s3://dir_with_double_space  /test_file.txt"));
    }

    @ParameterizedTest
    @MethodSource("locations")
    @SuppressWarnings("deprecation") // we're testing a deprecated method
    public void testAppendPath(String location, String path, String expected)
    {
        assertThat(appendPath(location, path)).isEqualTo(expected);
    }

    private static Stream<Arguments> invalidLocations()
    {
        return Stream.of(
                Arguments.of("location?", "location contains a query string.*"),
                Arguments.of("location#", "location contains a fragment.*"));
    }

    @ParameterizedTest
    @MethodSource("invalidLocations")
    @SuppressWarnings("deprecation") // we're testing a deprecated method
    public void testInvalidLocationInAppendPath(String location, String exceptionMessageRegexp)
    {
        assertThatThrownBy(() -> appendPath(location, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching(exceptionMessageRegexp);
    }

    @Test
    public void testDirectoryLocationEquivalence()
    {
        assertDirectoryLocationEquivalence("scheme://authority/", "scheme://authority/", true);
        assertDirectoryLocationEquivalence("scheme://authority/", "scheme://authority//", false);
        assertDirectoryLocationEquivalence("scheme://authority/", "scheme://authority///", false);
        assertDirectoryLocationEquivalence("scheme://userInfo@host:1234/dir", "scheme://userInfo@host:1234/dir/", true);
        assertDirectoryLocationEquivalence("scheme://authority/some/path", "scheme://authority/some/path", true);
        assertDirectoryLocationEquivalence("scheme://authority/some/path", "scheme://authority/some/path/", true);
        assertDirectoryLocationEquivalence("scheme://authority/some/path", "scheme://authority/some/path//", false);

        assertDirectoryLocationEquivalence("scheme://authority/some/path//", "scheme://authority/some/path//", true);
        assertDirectoryLocationEquivalence("scheme://authority/some/path/", "scheme://authority/some/path//", false);
        assertDirectoryLocationEquivalence("scheme://authority/some/path//", "scheme://authority/some/path///", false);

        assertDirectoryLocationEquivalence("scheme://authority/some//path", "scheme://authority/some//path/", true);
    }

    private static void assertDirectoryLocationEquivalence(String leftLocation, String rightLocation, boolean equivalent)
    {
        assertThat(areDirectoryLocationsEquivalent(Location.of(leftLocation), Location.of(rightLocation))).as("equivalence of '%s' in relation to '%s'", leftLocation, rightLocation)
                .isEqualTo(equivalent);
        assertThat(areDirectoryLocationsEquivalent(Location.of(rightLocation), Location.of(leftLocation))).as("equivalence of '%s' in relation to '%s'", rightLocation, leftLocation)
                .isEqualTo(equivalent);
    }
}
