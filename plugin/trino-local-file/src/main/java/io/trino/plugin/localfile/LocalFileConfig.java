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
package io.trino.plugin.localfile;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.LegacyConfig;
import io.airlift.configuration.validation.FileExists;

public class LocalFileConfig
{
    private String httpRequestLogLocation = "var/log/http-request.log";
    private String httpRequestLogFileNamePattern;

    @FileExists
    public String getHttpRequestLogLocation()
    {
        return httpRequestLogLocation;
    }

    @Config("trino-logs.http-request-log.location")
    @LegacyConfig("presto-logs.http-request-log.location")
    @ConfigDescription("Directory or file where http request logs are written")
    public LocalFileConfig setHttpRequestLogLocation(String httpRequestLogLocation)
    {
        this.httpRequestLogLocation = httpRequestLogLocation;
        return this;
    }

    public String getHttpRequestLogFileNamePattern()
    {
        return httpRequestLogFileNamePattern;
    }

    @Config("trino-logs.http-request-log.pattern")
    @LegacyConfig("presto-logs.http-request-log.pattern")
    @ConfigDescription("If log location is a directory this glob is used to match the file names in the directory")
    public LocalFileConfig setHttpRequestLogFileNamePattern(String pattern)
    {
        this.httpRequestLogFileNamePattern = pattern;
        return this;
    }
}
