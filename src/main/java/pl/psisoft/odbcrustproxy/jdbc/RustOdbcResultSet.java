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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class RustOdbcResultSet implements ResultSet {

    private final RustOdbcConnection connection;
    private final RustOdbcStatement statement;
    private final List<ColumnDef> columns;
    private final List<List<String>> rows;
    private final String cursorId;
    private boolean hasMore;

    private int rowIndex = -1;
    private boolean wasNull = false;
    private boolean closed = false;

    public RustOdbcResultSet(
            RustOdbcConnection connection,
            RustOdbcStatement statement,
            List<ColumnDef> columns,
            List<List<String>> rows,
            String cursorId,
            boolean hasMore
    ) {
        this.connection = connection;
        this.statement = statement;
        this.columns = columns != null ? columns : List.of();
        this.rows = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
        this.cursorId = cursorId;
        this.hasMore = hasMore;
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet został już zamknięty.");
        }
    }

    private int resolveColumnIndex(Object arg) throws SQLException {
        checkClosed();
        if (arg instanceof Integer idx) {
            if (idx < 1 || idx > columns.size()) {
                throw new SQLException("Nieprawidłowy indeks kolumny: " + idx + ", liczba kolumn: " + columns.size());
            }
            return idx;
        }
        String colLabel = (String) arg;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(colLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("Nie znaleziono kolumny o nazwie lub etykiecie: " + colLabel);
    }

    private String getRawValue(Object arg) throws SQLException {
        int idx = resolveColumnIndex(arg);
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new SQLException("Kursor ResultSet znajduje się przed pierwszym wierszem lub za ostatnim wierszem (indeks: " + rowIndex + ")");
        }
        String val = rows.get(rowIndex).get(idx - 1);
        wasNull = (val == null);
        return val;
    }

    private void fetchMore() throws SQLException {
        if (!hasMore || cursorId == null || connection == null) {
            return;
        }

        try {
            int fetchSize = statement != null ? statement.getFetchSize() : 500;
            if (fetchSize <= 0) fetchSize = 500;

            ObjectNode payload = RustOdbcDriver.MAPPER.createObjectNode();
            payload.put("session_id", connection.getSessionId());
            payload.put("cursor_id", cursorId);
            payload.put("fetch_size", fetchSize);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(connection.getBaseUrl() + "/session/fetch"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(RustOdbcDriver.MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = connection.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                JsonNode errNode = RustOdbcDriver.MAPPER.readTree(response.body());
                throw new SQLException("Błąd fetch z serwera Rust: " + errNode.path("error").asText(response.body()));
            }

            JsonNode root = RustOdbcDriver.MAPPER.readTree(response.body());
            this.hasMore = root.path("has_more").asBoolean(false);

            for (JsonNode rowNode : root.withArray("rows")) {
                List<String> row = new ArrayList<>(columns.size());
                for (JsonNode cell : rowNode) {
                    row.add(cell.isNull() ? null : cell.asText());
                }
                this.rows.add(row);
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Błąd komunikacji z mikroserwisem podczas fetch: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Parsowanie typów z odpornością na formaty lokalne
    // =========================================================================

    public static BigDecimal parseBigDecimal(String v) throws SQLException {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().replace("\u00A0", "").replace(" ", "").replace(',', '.');
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new SQLException("Nie można przekonwertować wartości '" + v + "' na BigDecimal: " + e.getMessage(), e);
        }
    }

    public static boolean parseBoolean(String v) {
        if (v == null) return false;
        String s = v.trim();
        return s.equalsIgnoreCase("true") || s.equals("1") || s.equals("-1")
                || s.equalsIgnoreCase("T") || s.equalsIgnoreCase("Y");
    }

    public static Date parseSqlDate(String v) throws SQLException {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.contains(" ") || s.contains("T")) {
            int spaceIdx = s.indexOf(' ');
            if (spaceIdx < 0) spaceIdx = s.indexOf('T');
            s = s.substring(0, spaceIdx);
        }

        // 1. ISO format: yyyy-MM-dd
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return Date.valueOf(LocalDate.parse(s));
        }
        // 2. Format z kropkami (np. dd.MM.yyyy)
        if (s.matches("^\\d{2}\\.\\d{2}\\.\\d{4}$")) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            return Date.valueOf(LocalDate.parse(s, dtf));
        }
        // 3. Format ze slashem (np. yyyy/MM/dd lub dd/MM/yyyy)
        if (s.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd");
            return Date.valueOf(LocalDate.parse(s, dtf));
        }
        if (s.matches("^\\d{2}/\\d{2}/\\d{4}$")) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            return Date.valueOf(LocalDate.parse(s, dtf));
        }

        try {
            return Date.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Nieprawidłowy format daty: '" + v + "'", e);
        }
    }

    public static Time parseSqlTime(String v) throws SQLException {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.contains(" ")) {
            s = s.substring(s.indexOf(' ') + 1);
        }
        if (s.contains(".")) {
            s = s.substring(0, s.indexOf('.'));
        }
        try {
            return Time.valueOf(LocalTime.parse(s));
        } catch (Exception e) {
            try {
                return Time.valueOf(s);
            } catch (Exception ex) {
                throw new SQLException("Nieprawidłowy format czasu: '" + v + "'", ex);
            }
        }
    }

    public static Timestamp parseSqlTimestamp(String v) throws SQLException {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().replace('T', ' ');
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
        }
        if (s.matches("^\\d{2}\\.\\d{2}\\.\\d{4}$")) {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            return Timestamp.valueOf(LocalDate.parse(s, dtf).atStartOfDay());
        }
        try {
            return Timestamp.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Nieprawidłowy format Timestamp: '" + v + "'", e);
        }
    }

    public static byte[] parseBytes(String v) {
        if (v == null) return null;
        String s = v.trim();
        try {
            if (s.length() % 2 == 0 && s.matches("^[0-9a-fA-F]+$")) {
                return HexFormat.of().parseHex(s);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return v.getBytes(StandardCharsets.UTF_8);
    }

    private Object getTypedObject(Object colArg) throws SQLException {
        int idx = resolveColumnIndex(colArg);
        String raw = getRawValue(idx);
        if (raw == null) {
            return null;
        }
        int jdbcType = columns.get(idx - 1).sqlType();
        return switch (jdbcType) {
            case Types.NUMERIC, Types.DECIMAL -> parseBigDecimal(raw);
            case Types.BIT, Types.BOOLEAN -> parseBoolean(raw);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                BigDecimal bd = parseBigDecimal(raw);
                yield bd != null ? bd.intValue() : null;
            }
            case Types.BIGINT -> {
                BigDecimal bd = parseBigDecimal(raw);
                yield bd != null ? bd.longValue() : null;
            }
            case Types.REAL -> {
                BigDecimal bd = parseBigDecimal(raw);
                yield bd != null ? bd.floatValue() : null;
            }
            case Types.FLOAT, Types.DOUBLE -> {
                BigDecimal bd = parseBigDecimal(raw);
                yield bd != null ? bd.doubleValue() : null;
            }
            case Types.DATE -> parseSqlDate(raw);
            case Types.TIME -> parseSqlTime(raw);
            case Types.TIMESTAMP -> parseSqlTimestamp(raw);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> parseBytes(raw);
            default -> raw;
        };
    }

    private Object getTypedObjectAsClass(Object colArg, Class<?> targetClass) throws SQLException {
        String raw = getRawValue(colArg);
        if (raw == null) return null;
        if (targetClass == String.class) return raw;
        if (targetClass == Integer.class || targetClass == int.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.intValue() : null;
        }
        if (targetClass == Long.class || targetClass == long.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.longValue() : null;
        }
        if (targetClass == Short.class || targetClass == short.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.shortValue() : null;
        }
        if (targetClass == Byte.class || targetClass == byte.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.byteValue() : null;
        }
        if (targetClass == Double.class || targetClass == double.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.doubleValue() : null;
        }
        if (targetClass == Float.class || targetClass == float.class) {
            BigDecimal bd = parseBigDecimal(raw);
            return bd != null ? bd.floatValue() : null;
        }
        if (targetClass == BigDecimal.class) return parseBigDecimal(raw);
        if (targetClass == Boolean.class || targetClass == boolean.class) return parseBoolean(raw);
        if (targetClass == Date.class) return parseSqlDate(raw);
        if (targetClass == Time.class) return parseSqlTime(raw);
        if (targetClass == Timestamp.class) return parseSqlTimestamp(raw);
        if (targetClass == LocalDate.class) {
            Date d = parseSqlDate(raw);
            return d != null ? d.toLocalDate() : null;
        }
        if (targetClass == LocalTime.class) {
            Time t = parseSqlTime(raw);
            return t != null ? t.toLocalTime() : null;
        }
        if (targetClass == LocalDateTime.class) {
            Timestamp ts = parseSqlTimestamp(raw);
            return ts != null ? ts.toLocalDateTime() : null;
        }
        if (targetClass == byte[].class) return parseBytes(raw);

        Object defaultObj = getTypedObject(colArg);
        if (targetClass.isInstance(defaultObj)) {
            return defaultObj;
        }
        throw new SQLException("Nie można przekonwertować wartości '" + raw + "' na typ " + targetClass.getName());
    }

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        if (rowIndex + 1 < rows.size()) {
            rowIndex++;
            return true;
        }
        if (hasMore) {
            fetchMore();
            if (rowIndex + 1 < rows.size()) {
                rowIndex++;
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            if (hasMore && cursorId != null && connection != null) {
                try {
                    ObjectNode payload = RustOdbcDriver.MAPPER.createObjectNode();
                    payload.put("session_id", connection.getSessionId());
                    payload.put("cursor_id", cursorId);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(connection.getBaseUrl() + "/session/close-cursor"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(RustOdbcDriver.MAPPER.writeValueAsString(payload)))
                            .build();

                    connection.getHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override public boolean wasNull() { return wasNull; }
    @Override public int findColumn(String columnLabel) throws SQLException { return resolveColumnIndex(columnLabel); }
    @Override public ResultSetMetaData getMetaData() { return new RustOdbcResultSetMetaData(columns); }
    @Override public Statement getStatement() { return statement; }
    @Override public boolean isClosed() { return closed; }
    @Override public int getRow() { return rowIndex + 1; }
    @Override public String getCursorName() throws SQLException { return cursorId != null ? cursorId : ""; }
    @Override public int getType() { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}

    @Override public String getString(int columnIndex) throws SQLException { return getRawValue(columnIndex); }
    @Override public String getString(String columnLabel) throws SQLException { return getRawValue(columnLabel); }

    @Override public boolean getBoolean(int columnIndex) throws SQLException { return parseBoolean(getRawValue(columnIndex)); }
    @Override public boolean getBoolean(String columnLabel) throws SQLException { return parseBoolean(getRawValue(columnLabel)); }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0 : bd.byteValue();
    }
    @Override
    public byte getByte(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0 : bd.byteValue();
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0 : bd.shortValue();
    }
    @Override
    public short getShort(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0 : bd.shortValue();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0 : bd.intValue();
    }
    @Override
    public int getInt(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0 : bd.intValue();
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0L : bd.longValue();
    }
    @Override
    public long getLong(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0L : bd.longValue();
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0.0f : bd.floatValue();
    }
    @Override
    public float getFloat(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0.0f : bd.floatValue();
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnIndex));
        return bd == null ? 0.0 : bd.doubleValue();
    }
    @Override
    public double getDouble(String columnLabel) throws SQLException {
        BigDecimal bd = parseBigDecimal(getRawValue(columnLabel));
        return bd == null ? 0.0 : bd.doubleValue();
    }

    @Override public BigDecimal getBigDecimal(int columnIndex) throws SQLException { return parseBigDecimal(getRawValue(columnIndex)); }
    @Override public BigDecimal getBigDecimal(String columnLabel) throws SQLException { return parseBigDecimal(getRawValue(columnLabel)); }
    @Override public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException { return getBigDecimal(columnIndex); }
    @Override public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException { return getBigDecimal(columnLabel); }

    @Override public byte[] getBytes(int columnIndex) throws SQLException { return parseBytes(getRawValue(columnIndex)); }
    @Override public byte[] getBytes(String columnLabel) throws SQLException { return parseBytes(getRawValue(columnLabel)); }

    @Override public Date getDate(int columnIndex) throws SQLException { return parseSqlDate(getRawValue(columnIndex)); }
    @Override public Date getDate(String columnLabel) throws SQLException { return parseSqlDate(getRawValue(columnLabel)); }
    @Override public Date getDate(int columnIndex, java.util.Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, java.util.Calendar cal) throws SQLException { return getDate(columnLabel); }

    @Override public Time getTime(int columnIndex) throws SQLException { return parseSqlTime(getRawValue(columnIndex)); }
    @Override public Time getTime(String columnLabel) throws SQLException { return parseSqlTime(getRawValue(columnLabel)); }
    @Override public Time getTime(int columnIndex, java.util.Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, java.util.Calendar cal) throws SQLException { return getTime(columnLabel); }

    @Override public Timestamp getTimestamp(int columnIndex) throws SQLException { return parseSqlTimestamp(getRawValue(columnIndex)); }
    @Override public Timestamp getTimestamp(String columnLabel) throws SQLException { return parseSqlTimestamp(getRawValue(columnLabel)); }
    @Override public Timestamp getTimestamp(int columnIndex, java.util.Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, java.util.Calendar cal) throws SQLException { return getTimestamp(columnLabel); }

    @Override public Object getObject(int columnIndex) throws SQLException { return getTypedObject(columnIndex); }
    @Override public Object getObject(String columnLabel) throws SQLException { return getTypedObject(columnLabel); }
    @Override public <T> T getObject(int columnIndex, Class<T> type) throws SQLException { return type.cast(getTypedObjectAsClass(columnIndex, type)); }
    @Override public <T> T getObject(String columnLabel, Class<T> type) throws SQLException { return type.cast(getTypedObjectAsClass(columnLabel, type)); }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b != null ? new ByteArrayInputStream(b) : null;
    }
    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return getBinaryStream(columnIndex);
    }
    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getBinaryStream(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s != null ? new StringReader(s) : null;
    }
    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override public boolean isBeforeFirst() { return rowIndex < 0 && !rows.isEmpty(); }
    @Override public boolean isAfterLast() { return rowIndex >= rows.size() && !hasMore; }
    @Override public boolean isFirst() { return rowIndex == 0 && !rows.isEmpty(); }
    @Override public boolean isLast() { return rowIndex == rows.size() - 1 && !hasMore; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Nie można rozpakować do: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    // Pozostałe metody ResultSet rzucają SQLException (zamiast unhandled reflection errors)
    @Override public InputStream getUnicodeStream(int columnIndex) throws SQLException { throw new SQLException("getUnicodeStream nie jest obsługiwane"); }
    @Override public InputStream getUnicodeStream(String columnLabel) throws SQLException { throw new SQLException("getUnicodeStream nie jest obsługiwane"); }
    @Override public void beforeFirst() throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY nie obsługuje cofania"); }
    @Override public void afterLast() throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY nie obsługuje skoków"); }
    @Override public boolean first() throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY"); }
    @Override public boolean last() throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY"); }
    @Override public boolean absolute(int row) throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY"); }
    @Override public boolean relative(int rows) throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY"); }
    @Override public boolean previous() throws SQLException { throw new SQLException("Kursor typu TYPE_FORWARD_ONLY"); }
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public int getFetchSize() { return statement != null ? statement.getFetchSize() : 500; }
    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }
    @Override public void updateNull(int columnIndex) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateByte(int columnIndex, byte x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateShort(int columnIndex, short x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateInt(int columnIndex, int x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateLong(int columnIndex, long x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateFloat(int columnIndex, float x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateDouble(int columnIndex, double x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateString(int columnIndex, String x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateDate(int columnIndex, Date x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateTime(int columnIndex, Time x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateObject(int columnIndex, Object x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNull(String columnLabel) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateByte(String columnLabel, byte x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateShort(String columnLabel, short x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateInt(String columnLabel, int x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateLong(String columnLabel, long x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateFloat(String columnLabel, float x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateDouble(String columnLabel, double x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateString(String columnLabel, String x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateDate(String columnLabel, Date x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateTime(String columnLabel, Time x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateObject(String columnLabel, Object x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void insertRow() throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateRow() throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void deleteRow() throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void refreshRow() throws SQLException {}
    @Override public void cancelRowUpdates() throws SQLException {}
    @Override public void moveToInsertRow() throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void moveToCurrentRow() throws SQLException {}
    @Override public java.net.URL getURL(int columnIndex) throws SQLException { throw new SQLException("getURL nie jest obsługiwane"); }
    @Override public java.net.URL getURL(String columnLabel) throws SQLException { throw new SQLException("getURL nie jest obsługiwane"); }
    @Override public void updateRef(int columnIndex, java.sql.Ref x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateRef(String columnLabel, java.sql.Ref x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(int columnIndex, java.sql.Blob x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(String columnLabel, java.sql.Blob x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(int columnIndex, java.sql.Clob x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(String columnLabel, java.sql.Clob x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateArray(int columnIndex, java.sql.Array x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateArray(String columnLabel, java.sql.Array x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public java.sql.RowId getRowId(int columnIndex) throws SQLException { throw new SQLException("RowId nie jest obsługiwane"); }
    @Override public java.sql.RowId getRowId(String columnLabel) throws SQLException { throw new SQLException("RowId nie jest obsługiwane"); }
    @Override public void updateRowId(int columnIndex, java.sql.RowId x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateRowId(String columnLabel, java.sql.RowId x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNString(String columnLabel, String nString) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(int columnIndex, java.sql.NClob nClob) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(String columnLabel, java.sql.NClob nClob) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public java.sql.NClob getNClob(int columnIndex) throws SQLException { throw new SQLException("NClob nie jest obsługiwane"); }
    @Override public java.sql.NClob getNClob(String columnLabel) throws SQLException { throw new SQLException("NClob nie jest obsługiwane"); }
    @Override public java.sql.SQLXML getSQLXML(int columnIndex) throws SQLException { throw new SQLException("SQLXML nie jest obsługiwane"); }
    @Override public java.sql.SQLXML getSQLXML(String columnLabel) throws SQLException { throw new SQLException("SQLXML nie jest obsługiwane"); }
    @Override public void updateSQLXML(int columnIndex, java.sql.SQLXML xmlObject) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateSQLXML(String columnLabel, java.sql.SQLXML xmlObject) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public String getNString(int columnIndex) throws SQLException { return getString(columnIndex); }
    @Override public String getNString(String columnLabel) throws SQLException { return getString(columnLabel); }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { return getCharacterStream(columnIndex); }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { return getCharacterStream(columnLabel); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException { throw new SQLException("ResultSet tylko do odczytu"); }
    @Override public java.sql.Ref getRef(int columnIndex) throws SQLException { throw new SQLException("Ref nie jest obsługiwane"); }
    @Override public java.sql.Blob getBlob(int columnIndex) throws SQLException { throw new SQLException("Blob nie jest obsługiwane"); }
    @Override public java.sql.Clob getClob(int columnIndex) throws SQLException { throw new SQLException("Clob nie jest obsługiwane"); }
    @Override public java.sql.Array getArray(int columnIndex) throws SQLException { throw new SQLException("Array nie jest obsługiwane"); }
    @Override public java.sql.Ref getRef(String columnLabel) throws SQLException { throw new SQLException("Ref nie jest obsługiwane"); }
    @Override public java.sql.Blob getBlob(String columnLabel) throws SQLException { throw new SQLException("Blob nie jest obsługiwane"); }
    @Override public java.sql.Clob getClob(String columnLabel) throws SQLException { throw new SQLException("Clob nie jest obsługiwane"); }
    @Override public java.sql.Array getArray(String columnLabel) throws SQLException { throw new SQLException("Array nie jest obsługiwane"); }
    @Override public Object getObject(int columnIndex, java.util.Map<String, Class<?>> map) throws SQLException { return getObject(columnIndex); }
    @Override public Object getObject(String columnLabel, java.util.Map<String, Class<?>> map) throws SQLException { return getObject(columnLabel); }
}
