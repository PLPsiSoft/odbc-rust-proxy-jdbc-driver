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

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class RustOdbcResultSetMetaData implements ResultSetMetaData {

    private final List<ColumnDef> columns;

    public RustOdbcResultSetMetaData(List<ColumnDef> columns) {
        this.columns = columns != null ? columns : List.of();
    }

    private ColumnDef getCol(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException("Nieprawidłowy indeks kolumny: " + column + ", liczba kolumn: " + columns.size());
        }
        return columns.get(column - 1);
    }

    public static String resolveColumnClassName(int jdbcType) {
        return switch (jdbcType) {
            case Types.NUMERIC, Types.DECIMAL -> BigDecimal.class.getName();
            case Types.BIT, Types.BOOLEAN -> Boolean.class.getName();
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.REAL -> Float.class.getName();
            case Types.FLOAT, Types.DOUBLE -> Double.class.getName();
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> byte[].class.getName();
            case Types.DATE -> java.sql.Date.class.getName();
            case Types.TIME -> java.sql.Time.class.getName();
            case Types.TIMESTAMP -> java.sql.Timestamp.class.getName();
            default -> String.class.getName();
        };
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return getCol(column).nullable();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        int t = getCol(column).sqlType();
        return switch (t) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE -> true;
            default -> false;
        };
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return getCol(column).precision();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getCol(column).name();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return getCol(column).name();
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return getCol(column).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        return getCol(column).scale();
    }

    @Override
    public String getTableName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        getCol(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return getCol(column).sqlType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return getCol(column).typeName();
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        getCol(column);
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        getCol(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return resolveColumnClassName(getCol(column).sqlType());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Nie można rozpakować do: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
