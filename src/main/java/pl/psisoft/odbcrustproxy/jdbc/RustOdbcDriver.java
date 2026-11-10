// Copyright (C) 2026 PSI SOFT Sp. z o.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//         http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package pl.psisoft.odbcrustproxy.jdbc;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sterownik JDBC dla JDK 21+ łączący się z 32-bitowym serwerem Rust ODBC Proxy.
 *
 * Obsługiwane formaty URL:
 * 1. jdbc:rustodbc://127.0.0.1:8097/NazwaDSN[?charset=cp1250&...]
 * 2. jdbc:odbc:NazwaDSN[?charset=cp852&...] (domyślnie łączy się z lokalnym proxy na porcie 8097)
 */
public class RustOdbcDriver implements Driver {

    public static final String RUSTODBC_PREFIX = "jdbc:rustodbc://";
    public static final String ODBC_PREFIX = "jdbc:odbc:";
    public static final String DEFAULT_PROXY_URL = "http://127.0.0.1:8097";

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static {
        try {
            DriverManager.registerDriver(new RustOdbcDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith(RUSTODBC_PREFIX) || lower.startsWith(ODBC_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        String baseUrl;
        String dsn;
        Map<String, String> urlParams = new HashMap<>();

        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith(RUSTODBC_PREFIX)) {
            String withoutPrefix = url.substring(RUSTODBC_PREFIX.length());
            int slashIdx = withoutPrefix.indexOf('/');
            if (slashIdx < 0) {
                throw new SQLException("Nieprawidłowy format URL jdbc:rustodbc://. Oczekiwano: jdbc:rustodbc://host:port/DSN");
            }

            String hostPort = withoutPrefix.substring(0, slashIdx);
            baseUrl = "http://" + hostPort;
            String pathAndQuery = withoutPrefix.substring(slashIdx + 1);

            int qIdx = pathAndQuery.indexOf('?');
            if (qIdx >= 0) {
                dsn = pathAndQuery.substring(0, qIdx);
                parseQueryParams(pathAndQuery.substring(qIdx + 1), urlParams);
            } else {
                dsn = pathAndQuery;
            }
        } else {
            // jdbc:odbc:DSN[?params]
            String proxySysProp = System.getProperty("rustodbc.proxy.url");
            if (proxySysProp != null && !proxySysProp.isBlank()) {
                baseUrl = proxySysProp.trim();
            } else {
                baseUrl = DEFAULT_PROXY_URL;
            }

            String afterPrefix = url.substring(ODBC_PREFIX.length());
            int qIdx = afterPrefix.indexOf('?');
            if (qIdx >= 0) {
                dsn = afterPrefix.substring(0, qIdx);
                parseQueryParams(afterPrefix.substring(qIdx + 1), urlParams);
            } else {
                dsn = afterPrefix;
            }
        }

        if (dsn.isBlank()) {
            throw new SQLException("Nazwa źródła DSN w URL nie może być pusta.");
        }

        // Pobieranie parametrów uwierzytelniania i kodowania
        String user = info != null ? info.getProperty("user", "") : "";
        if (user.isBlank() && urlParams.containsKey("user")) {
            user = urlParams.get("user");
        }

        String password = info != null ? info.getProperty("password", "") : "";
        if (password.isBlank() && urlParams.containsKey("password")) {
            password = urlParams.get("password");
        }

        String charset = null;
        if (info != null) {
            charset = info.getProperty("charset");
            if (charset == null) charset = info.getProperty("charSet");
        }
        if (charset == null) {
            charset = urlParams.get("charset");
            if (charset == null) charset = urlParams.get("charset");
        }

        return createSessionConnection(baseUrl, dsn, user, password, charset);
    }

    private static void parseQueryParams(String queryString, Map<String, String> outParams) {
        if (queryString == null || queryString.isBlank()) return;
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq).trim(), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1).trim(), StandardCharsets.UTF_8);
                outParams.put(k.toLowerCase(Locale.ROOT), v);
            }
        }
    }

    private static Connection createSessionConnection(
            String baseUrl,
            String dsn,
            String user,
            String password,
            String charset
    ) throws SQLException {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("dsn", dsn);
            if (user != null && !user.isBlank()) {
                payload.put("user", user);
            }
            if (password != null && !password.isBlank()) {
                payload.put("password", password);
            }
            if (charset != null && !charset.isBlank()) {
                payload.put("charset", charset);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/session/connect"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                JsonNode errNode = MAPPER.readTree(response.body());
                throw new SQLException("Nie udało się nawiązać sesji ODBC przez serwer Rust: "
                        + errNode.path("error").asText(response.body()));
            }

            JsonNode root = MAPPER.readTree(response.body());
            String sessionId = root.path("session_id").asText();
            if (sessionId == null || sessionId.isBlank()) {
                throw new SQLException("Serwer Rust nie zwrócił poprawnego identyfikatora sesji.");
            }

            return new RustOdbcConnection(baseUrl, sessionId, dsn, user, charset);

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Błąd komunikacji z serwerem Rust ODBC Proxy (" + baseUrl + "): " + e.getMessage(), e);
        }
    }

    public static int resolveJdbcType(int rawSqlType, String typeName) {
        switch (rawSqlType) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                 Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT,
                 Types.BIT, Types.BOOLEAN, Types.FLOAT, Types.REAL, Types.DOUBLE,
                 Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY:
                return rawSqlType;
            default:
                break;
        }

        if (typeName == null) {
            return Types.VARCHAR;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        if (upper.contains("INT")) {
            if (upper.contains("BIG")) return Types.BIGINT;
            if (upper.contains("SMALL")) return Types.SMALLINT;
            if (upper.contains("TINY")) return Types.TINYINT;
            return Types.INTEGER;
        }
        if (upper.contains("DEC") || upper.contains("NUM") || upper.contains("MONEY") || upper.contains("CURRENCY")) {
            return Types.DECIMAL;
        }
        if (upper.contains("DOUB")) return Types.DOUBLE;
        if (upper.contains("FLOAT")) return Types.FLOAT;
        if (upper.contains("REAL")) return Types.REAL;
        if (upper.contains("BOOL") || upper.contains("BIT") || upper.contains("LOGICAL")) return Types.BIT;
        if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) return Types.TIMESTAMP;
        if (upper.contains("DATE")) return Types.DATE;
        if (upper.contains("TIME")) return Types.TIME;
        if (upper.contains("BIN") || upper.contains("BLOB") || upper.contains("BYTE")) return Types.VARBINARY;
        if (upper.contains("CHAR") || upper.contains("TEXT") || upper.contains("MEMO")) return Types.VARCHAR;

        return Types.VARCHAR;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
