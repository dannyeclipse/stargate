/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.sgv2.graphql.integration.util;

import com.jayway.jsonpath.JsonPath;
import graphql.com.google.common.collect.ImmutableMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.http.HttpStatus;

public class GraphqlFirstClient extends GraphqlClient {

  // Note: these constants duplicate the ones from the production code's `ResourcePaths` (which is
  // not accessible from here).
  private static final String ADMIN = "graphql-admin";
  private static final String KEYSPACES = "graphql";
  private static final String FILES = "graphql-files";

  private final String baseUrl;
  private final String authToken;
  private final String adminUri;
  private final String cqlDirectivesUri;

  public GraphqlFirstClient(String baseUrl, String authToken) {
    this.baseUrl = baseUrl;
    this.authToken = authToken;
    this.adminUri = baseUrl + ADMIN;
    this.cqlDirectivesUri = baseUrl + FILES + "/cql_directives.graphql";
  }

  /**
   * Deploys new contents to a keyspace, assuming no previous version existed.
   *
   * @return the resulting version.
   */
  public UUID deploySchema(String keyspace, String contents) {
    return deploySchema(keyspace, null, contents);
  }

  public UUID deploySchema(String keyspace, String expectedVersion, String contents) {
    return deploySchema(keyspace, expectedVersion, false, contents);
  }

  public UUID deploySchema(
      String keyspace, String expectedVersion, boolean force, String contents) {
    Map<String, Object> response =
        getGraphqlData(
            authToken,
            adminUri,
            buildDeploySchemaQuery(keyspace, expectedVersion, force, contents));
    String version = JsonPath.read(response, "$.deploySchema.version");
    return UUID.fromString(version);
  }

  /**
   * Deploys new contents to a keyspace, assuming it will produce errors.
   *
   * @return the full contents of the {@code errors} field in the response.
   */
  public List<Map<String, Object>> getDeploySchemaErrors(
      String keyspace, String expectedVersion, String contents) {
    String query = buildDeploySchemaQuery(keyspace, expectedVersion, false, contents);
    return getGraphqlErrors(authToken, adminUri, query, HttpStatus.SC_OK);
  }

  /**
   * Same as {@link #getDeploySchemaErrors}, but also assumes that there is exactly one error, and
   * we're only interested in that error's message.
   *
   * @return the message.
   */
  public String getDeploySchemaError(String keyspace, String expectedVersion, String contents) {
    return getDeploySchemaError(keyspace, expectedVersion, false, contents);
  }

  /** @see #getDeploySchemaError(String, String, String) */
  public String getDeploySchemaError(
      String keyspace, String expectedVersion, boolean force, String contents) {
    return getGraphqlError(
        authToken,
        adminUri,
        buildDeploySchemaQuery(keyspace, expectedVersion, force, contents),
        HttpStatus.SC_OK);
  }

  private String buildDeploySchemaQuery(
      String keyspace, String expectedVersion, boolean force, String contents) {
    StringBuilder query = new StringBuilder("mutation { deploySchema( ");
    query.append(String.format("keyspace: \"%s\" ", keyspace));
    if (expectedVersion != null) {
      query.append(String.format("expectedVersion: \"%s\" ", expectedVersion));
    }
    query.append(String.format("force: %b ", force));
    query.append(String.format("schema: \"\"\"%s\"\"\" ", contents));
    return query.append(") { version } }").toString();
  }

  public void undeploySchema(String keyspace, String expectedVersion, boolean force) {
    getGraphqlData(authToken, adminUri, buildUndeploySchemaQuery(keyspace, expectedVersion, force));
  }

  public void undeploySchema(String keyspace, String expectedVersion) {
    undeploySchema(keyspace, expectedVersion, false);
  }

  public String getUndeploySchemaError(String keyspace, String expectedVersion, boolean force) {
    return getGraphqlError(
        authToken,
        adminUri,
        buildUndeploySchemaQuery(keyspace, expectedVersion, force),
        HttpStatus.SC_OK);
  }

  public String getUndeploySchemaError(String keyspace, String expectedVersion) {
    return getUndeploySchemaError(keyspace, expectedVersion, false);
  }

  private String buildUndeploySchemaQuery(String keyspace, String expectedVersion, boolean force) {
    return String.format(
        "mutation { undeploySchema(keyspace: \"%s\", expectedVersion: \"%s\", force: %b) }",
        keyspace, expectedVersion, force);
  }

  /** Returns the contents of the static cql_directives.graphql file. */
  public String getCqlDirectivesFile() {
    return doGet(authToken, cqlDirectivesUri, Response.Status.OK.getStatusCode());
  }

  public String getSchemaFile(String keyspace) {
    return getSchemaFile(keyspace, null);
  }

  public String getSchemaFile(String keyspace, String version, int expectedStatusCode) {
    return doGet(authToken, buildSchemaFileUri(keyspace, version), expectedStatusCode);
  }

  public String getSchemaFile(String keyspace, String version) {
    return getSchemaFile(keyspace, version, Response.Status.OK.getStatusCode());
  }

  public void expectSchemaFileStatus(String keyspace, Response.Status expectedStatus) {
    expectSchemaFileStatus(keyspace, null, expectedStatus);
  }

  public void expectSchemaFileStatus(
      String keyspace, String version, Response.Status expectedStatus) {
    doGet(authToken, buildSchemaFileUri(keyspace, version), expectedStatus.getStatusCode());
  }

  public Object executeAdminQuery(String adminQuery) {
    return getGraphqlData(authToken, adminUri, adminQuery);
  }

  public Object executeKeyspaceQuery(String keyspace, String graphqlQuery) {
    return getGraphqlData(authToken, buildKeyspaceUri(keyspace), graphqlQuery);
  }

  public Map<String, Object> getKeyspaceFullResponse(
      Map<String, String> headers, String keyspace, String graphqlQuery) {
    return getGraphqlResponse(
        ImmutableMap.<String, String>builder()
            .putAll(headers)
            .put("X-Cassandra-Token", authToken)
            .build(),
        buildKeyspaceUri(keyspace),
        graphqlQuery,
        HttpStatus.SC_OK);
  }

  /** Executes a GraphQL query for a keyspace, expecting a <b>single</b> GraphQL error. */
  public String getKeyspaceError(String keyspace, String graphqlQuery) {
    return getGraphqlError(authToken, buildKeyspaceUri(keyspace), graphqlQuery, HttpStatus.SC_OK);
  }

  public List<Map<String, Object>> getKeyspaceErrors(String keyspace, String graphqlQuery) {
    return getGraphqlErrors(authToken, buildKeyspaceUri(keyspace), graphqlQuery, HttpStatus.SC_OK);
  }

  private String buildSchemaFileUri(String keyspace, String version) {
    String url = baseUrl + FILES + "/keyspace/" + urlEncode(keyspace) + ".graphql";
    if (version != null) {
      url = url + "?version=" + urlEncode(version);
    }
    return url;
  }

  private String buildKeyspaceUri(String keyspace) {
    return baseUrl + KEYSPACES + "/" + keyspace;
  }
}
