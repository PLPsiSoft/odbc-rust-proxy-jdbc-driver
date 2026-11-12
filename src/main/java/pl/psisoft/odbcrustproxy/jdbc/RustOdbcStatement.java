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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class RustOdbcStatement implements PreparedStatement {

    private final RustOdbcConnection connection;
    private final String initialSql;
    private final List<String> params = new ArrayList<>();
    private final List<List<String>> batchParams = new ArrayList<>();

    private int fetchSize = 500;
    private int queryTimeout = 0;
    private int maxRows = 0;
    private ResultSet currentResultSet;
    private int updateCount = -1;
    private boolean closed = false;

    public RustOdbcStatement(RustOdbcConnection connection, String initialSql) {
        this.connection = connection;
        this.initialSql = initialSql;
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement został już zamknięty.");
        }
    }

    private void setParam(int index, Object value) throws SQLException {
        checkClosed();
        if (index < 1) {
            throw new SQLException("Nieprawidłowy indeks parametru: " + index);
        }
        while (params.size() < index) {
            params.add(null);
        }

        String strVal;
        if (value == null) {
            strVal = null;
        } else if (value instanceof Date d) {
            strVal = d.toString();
        } else if (value instanceof Time t) {
            strVal = t.toString();
        } else if (value instanceof Timestamp ts) {
            strVal = ts.toString();
        } else if (value instanceof java.util.Date ud) {
            strVal = new Timestamp(ud.getTime()).toString();
        } else if (value instanceof LocalDate ld) {
            strVal = ld.toString();
        } else if (value instanceof LocalTime lt) {
            strVal = lt.toString();
        } else if (value instanceof LocalDateTime ldt) {
            strVal = ldt.toString().replace('T', ' ');
        } else if (value instanceof BigDecimal bd) {
            strVal = bd.toPlainString();
        } else if (value instanceof byte[] bytes) {
            strVal = HexFormat.of().formatHex(bytes);
        } else {
            strVal = String.valueOf(value);
        }

        params.set(index - 1, strVal);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(initialSql);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        String sqlToRun = (sql != null && !sql.isBlank()) ? sql : initialSql;
        if (sqlToRun == null || sqlToRun.isBlank()) {
            throw new SQLException("Brak zapytania SQL do wykonania.");
        }

        try {
            ObjectNode payload = RustOdbcDriver.MAPPER.createObjectNode();
            payload.put("session_id", connection.getSessionId());
            payload.put("sql", sqlToRun);
            if (connection.getCharset() != null) {
                payload.put("charset", connection.getCharset());
            }
            if (fetchSize > 0) {
                payload.put("fetch_size", fetchSize);
            }

            ArrayNode paramsArray = payload.putArray("params");
            for (String p : params) {
                if (p == null) {
                    paramsArray.addNull();
                } else {
                    paramsArray.add(p);
                }
            }

            Duration timeout = queryTimeout > 0 ? Duration.ofSeconds(queryTimeout) : Duration.ofSeconds(60);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(connection.getBaseUrl() + "/query"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(RustOdbcDriver.MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = connection.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                JsonNode errNode = RustOdbcDriver.MAPPER.readTree(response.body());
                throw new SQLException("Błąd ODBC z serwera Rust: " + errNode.path("error").asText(response.body()));
            }

            JsonNode root = RustOdbcDriver.MAPPER.readTree(response.body());
            List<ColumnDef> columns = new ArrayList<>();
            for (JsonNode col : root.withArray("columns")) {
                String name = col.path("name").asText();
                String typeName = col.path("type_name").asText("VARCHAR");
                int rawSqlType = col.path("sql_type").asInt(Types.VARCHAR);
                int resolvedJdbcType = RustOdbcDriver.resolveJdbcType(rawSqlType, typeName);
                int precision = col.path("precision").asInt(255);
                int scale = col.path("scale").asInt(0);
                int nullable = col.path("nullable").asInt(ResultSetMetaData.columnNullableUnknown);

                columns.add(new ColumnDef(name, resolvedJdbcType, typeName, precision, scale, nullable));
            }

            List<List<String>> rows = new ArrayList<>();
            for (JsonNode rowNode : root.withArray("rows")) {
                List<String> row = new ArrayList<>(columns.size());
                for (JsonNode cell : rowNode) {
                    row.add(cell.isNull() ? null : cell.asText());
                }
                rows.add(row);
            }

            String cursorId = root.hasNonNull("cursor_id") ? root.get("cursor_id").asText() : null;
            boolean hasMore = root.path("has_more").asBoolean(false);

            if (currentResultSet != null && !currentResultSet.isClosed()) {
                currentResultSet.close();
            }

            currentResultSet = new RustOdbcResultSet(connection, this, columns, rows, cursorId, hasMore);
            updateCount = -1;
            return currentResultSet;

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Błąd komunikacji z mikroserwisem Rust ODBC Proxy: " + e.getMessage(), e);
        }
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(initialSql);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        String sqlToRun = (sql != null && !sql.isBlank()) ? sql : initialSql;
        if (sqlToRun == null || sqlToRun.isBlank()) {
            throw new SQLException("Brak instrukcji SQL do wykonania.");
        }

        try {
            ObjectNode payload = RustOdbcDriver.MAPPER.createObjectNode();
            payload.put("session_id", connection.getSessionId());
            payload.put("sql", sqlToRun);

            ArrayNode paramsArray = payload.putArray("params");
            for (String p : params) {
                if (p == null) {
                    paramsArray.addNull();
                } else {
                    paramsArray.add(p);
                }
            }

            Duration timeout = queryTimeout > 0 ? Duration.ofSeconds(queryTimeout) : Duration.ofSeconds(60);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(connection.getBaseUrl() + "/execute"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(RustOdbcDriver.MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = connection.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                JsonNode errNode = RustOdbcDriver.MAPPER.readTree(response.body());
                throw new SQLException("Błąd ODBC z serwera Rust: " + errNode.path("error").asText(response.body()));
            }

            JsonNode root = RustOdbcDriver.MAPPER.readTree(response.body());
            updateCount = root.path("affected_rows").asInt(0);

            if (currentResultSet != null && !currentResultSet.isClosed()) {
                currentResultSet.close();
                currentResultSet = null;
            }

            return updateCount;

        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Błąd komunikacji z mikroserwisem Rust ODBC Proxy: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(initialSql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        String sqlToRun = (sql != null && !sql.isBlank()) ? sql : initialSql;
        if (sqlToRun == null || sqlToRun.isBlank()) {
            throw new SQLException("Brak instrukcji SQL do wykonania.");
        }

        String trimmed = sqlToRun.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") || trimmed.startsWith("{CALL")) {
            executeQuery(sqlToRun);
            return true;
        } else {
            executeUpdate(sqlToRun);
            return false;
        }
    }

    @Override
    public void cancel() throws SQLException {
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            if (currentResultSet != null && !currentResultSet.isClosed()) {
                currentResultSet.close();
            }
        }
    }

    @Override public boolean isClosed() { return closed; }
    @Override public ResultSet getResultSet() { return currentResultSet; }
    @Override public int getUpdateCount() { return updateCount; }
    @Override public boolean getMoreResults() { return false; }
    @Override public boolean getMoreResults(int current) { return false; }

    @Override public void setFetchSize(int rows) throws SQLException { this.fetchSize = rows; }
    @Override public int getFetchSize() { return fetchSize; }
    @Override public void setQueryTimeout(int seconds) throws SQLException { this.queryTimeout = seconds; }
    @Override public int getQueryTimeout() { return queryTimeout; }
    @Override public void setMaxRows(int max) throws SQLException { this.maxRows = max; }
    @Override public int getMaxRows() { return maxRows; }

    @Override public Connection getConnection() { return connection; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public void clearParameters() { params.clear(); }

    @Override public int getResultSetType() { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }

    @Override public void setEscapeProcessing(boolean enable) {}
    @Override public void setMaxFieldSize(int max) {}
    @Override public int getMaxFieldSize() { return 65536; }
    @Override public void setCursorName(String name) {}
    @Override public void setFetchDirection(int direction) {}
    @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    @Override public void setPoolable(boolean poolable) {}
    @Override public boolean isPoolable() { return false; }
    @Override public void closeOnCompletion() {}
    @Override public boolean isCloseOnCompletion() { return false; }

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException { setParam(parameterIndex, null); }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { setParam(parameterIndex, null); }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setShort(int parameterIndex, short x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setInt(int parameterIndex, int x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setLong(int parameterIndex, long x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setString(int parameterIndex, String x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException { setParam(parameterIndex, x); }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException { setParam(parameterIndex, x); }

    @Override
    public void addBatch() {
        batchParams.add(new ArrayList<>(params));
    }

    @Override
    public void clearBatch() {
        batchParams.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int[] counts = new int[batchParams.size()];
        for (int i = 0; i < batchParams.size(); i++) {
            params.clear();
            params.addAll(batchParams.get(i));
            counts[i] = executeUpdate();
        }
        clearBatch();
        return counts;
    }

    @Override public void addBatch(String sql) {}

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (currentResultSet != null) {
            return currentResultSet.getMetaData();
        }
        return null;
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new ParameterMetaData() {
            @Override public int getParameterCount() { return params.size(); }
            @Override public int isNullable(int param) { return ParameterMetaData.parameterNullableUnknown; }
            @Override public boolean isSigned(int param) { return true; }
            @Override public int getPrecision(int param) { return 255; }
            @Override public int getScale(int param) { return 0; }
            @Override public int getParameterType(int param) { return Types.VARCHAR; }
            @Override public String getParameterTypeName(int param) { return "VARCHAR"; }
            @Override public String getParameterClassName(int param) { return String.class.getName(); }
            @Override public int getParameterMode(int param) { return ParameterMetaData.parameterModeIn; }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Nie można rozpakować do: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    // Nieobsługiwane metody ze specyfikacji JDBC (rzucają SQLException zamiast błędu dynamic proxy)
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setURL(int parameterIndex, java.net.URL x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setNString(int parameterIndex, String value) throws SQLException { setString(parameterIndex, value); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException { throw new SQLException("Nieobsługiwane"); }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException { return execute(sql); }
    @Override public ResultSet getGeneratedKeys() throws SQLException { return null; }
}
