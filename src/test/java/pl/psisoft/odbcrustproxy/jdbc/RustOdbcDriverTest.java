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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

public class RustOdbcDriverTest {

    private static HttpServer mockServer;
    private static int mockPort;

    @BeforeAll
    public static void startMockServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();

        // 1. /session/connect
        mockServer.createContext("/session/connect", exchange -> {
            String resp = "{\"session_id\":\"test_session_123\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 2. /session/autocommit
        mockServer.createContext("/session/autocommit", exchange -> {
            String resp = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 3. /session/commit
        mockServer.createContext("/session/commit", exchange -> {
            String resp = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 4. /session/rollback
        mockServer.createContext("/session/rollback", exchange -> {
            String resp = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 5. /session/ping
        mockServer.createContext("/session/ping", exchange -> {
            String resp = "{\"alive\":true}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 6. /session/close
        mockServer.createContext("/session/close", exchange -> {
            String resp = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 7. /query
        mockServer.createContext("/query", exchange -> {
            String resp = """
                {
                  "columns": [
                    { "name": "ID", "sql_type": 4, "type_name": "INTEGER", "precision": 10, "scale": 0, "nullable": 0 },
                    { "name": "NAZWA", "sql_type": 12, "type_name": "VARCHAR", "precision": 100, "scale": 0, "nullable": 1 },
                    { "name": "KWOTA", "sql_type": 3, "type_name": "DECIMAL", "precision": 15, "scale": 2, "nullable": 1 }
                  ],
                  "rows": [
                    ["1", "Faktura A", "1234,50"],
                    ["2", null, "0.00"]
                  ],
                  "has_more": false
                }
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 8. /execute
        mockServer.createContext("/execute", exchange -> {
            String resp = "{\"affected_rows\":1}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        mockServer.start();
    }

    @AfterAll
    public static void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    public void testUrlAcceptanceAndSpiRegistration() throws Exception {
        Driver driver = DriverManager.getDriver("jdbc:rustodbc://127.0.0.1:8097/test");
        assertNotNull(driver);
        assertTrue(driver instanceof RustOdbcDriver);

        Driver driver2 = DriverManager.getDriver("jdbc:odbc:test");
        assertNotNull(driver2);
        assertTrue(driver2 instanceof RustOdbcDriver);

        assertTrue(driver.acceptsURL("jdbc:rustodbc://127.0.0.1:8097/test?charset=cp852"));
        assertTrue(driver.acceptsURL("jdbc:odbc:MyDsn?charSet=cp1250"));
        assertFalse(driver.acceptsURL("jdbc:oracle:thin:@localhost:1521:xe"));
    }

    @Test
    public void testTypeParsingRobustness() throws Exception {
        // BigDecimal
        assertEquals(new BigDecimal("1234.50"), RustOdbcResultSet.parseBigDecimal("1234,50"));
        assertEquals(new BigDecimal("1234.50"), RustOdbcResultSet.parseBigDecimal(" 1 234,50 "));
        assertEquals(new BigDecimal("100.00"), RustOdbcResultSet.parseBigDecimal("100.00"));
        assertNull(RustOdbcResultSet.parseBigDecimal(""));

        // Boolean
        assertTrue(RustOdbcResultSet.parseBoolean("true"));
        assertTrue(RustOdbcResultSet.parseBoolean("1"));
        assertTrue(RustOdbcResultSet.parseBoolean("-1"));
        assertTrue(RustOdbcResultSet.parseBoolean("T"));
        assertFalse(RustOdbcResultSet.parseBoolean("0"));
        assertFalse(RustOdbcResultSet.parseBoolean("false"));
        assertFalse(RustOdbcResultSet.parseBoolean(null));

        // Date
        assertEquals(Date.valueOf("2026-11-09"), RustOdbcResultSet.parseSqlDate("2026-11-09"));
        assertEquals(Date.valueOf("2026-11-09"), RustOdbcResultSet.parseSqlDate("09.11.2026"));
        assertEquals(Date.valueOf("2026-11-09"), RustOdbcResultSet.parseSqlDate("2026/11/09"));
        assertEquals(Date.valueOf("2026-11-09"), RustOdbcResultSet.parseSqlDate("2026-11-09 14:30:00"));

        // Time
        assertEquals(Time.valueOf("14:30:00"), RustOdbcResultSet.parseSqlTime("14:30:00"));
        assertEquals(Time.valueOf("14:30:00"), RustOdbcResultSet.parseSqlTime("2026-11-09 14:30:00"));

        // Timestamp
        assertEquals(Timestamp.valueOf("2026-11-09 14:30:00"), RustOdbcResultSet.parseSqlTimestamp("2026-11-09 14:30:00"));
        assertEquals(Timestamp.valueOf("2026-11-09 14:30:00"), RustOdbcResultSet.parseSqlTimestamp("2026-11-09T14:30:00"));
        assertEquals(Timestamp.valueOf("2026-11-09 00:00:00"), RustOdbcResultSet.parseSqlTimestamp("2026-11-09"));

        // Bytes
        assertArrayEquals(new byte[]{0x01, 0x0A, (byte) 0xFF}, RustOdbcResultSet.parseBytes("010aff"));
    }

    @Test
    public void testFullEfkaWorkflowSimulation() throws Exception {
        // Symulacja DOKŁADNEGO przepływu z AuZrodloDanych i AuSql w efkawww
        String url = "jdbc:rustodbc://127.0.0.1:" + mockPort + "/test_dsn?charset=cp852";

        Properties props = new Properties();
        props.put("user", "admin");
        props.put("password", "secret");
        props.put("charSet", "cp852");

        try (Connection conn = DriverManager.getConnection(url, props)) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
            assertTrue(conn.isValid(5));

            // 1. AuZrodloDanych.java:649 -> setAutoCommit(false)
            conn.setAutoCommit(false);
            assertFalse(conn.getAutoCommit());

            // 2. AuZrodloDanych.java:658 -> setReadOnly(true) (nie może rzucić wyjątku!)
            conn.setReadOnly(true);
            assertTrue(conn.isReadOnly());

            // 3. AuZrodloDanych.java:702 -> getColumnSetup (SELECT * FROM (...) WHERE 1=2)
            String sql = "SELECT * FROM (SELECT ID, NAZWA, KWOTA FROM DOKUMENTY) WHERE 1=2";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                // AuSql.java:845 -> setObject(i+1, list[i]) z wartością NULL
                stmt.setObject(1, null);
                stmt.setObject(2, "Test Nazwa");
                stmt.setObject(3, new BigDecimal("123.45"));

                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData m = rs.getMetaData();
                    assertNotNull(m);
                    assertEquals(3, m.getColumnCount());

                    assertEquals("ID", m.getColumnName(1));
                    assertEquals(Types.INTEGER, m.getColumnType(1));
                    assertEquals(Integer.class.getName(), m.getColumnClassName(1));

                    assertEquals("NAZWA", m.getColumnName(2));
                    assertEquals(Types.VARCHAR, m.getColumnType(2));
                    assertEquals(String.class.getName(), m.getColumnClassName(2));

                    assertEquals("KWOTA", m.getColumnName(3));
                    assertEquals(Types.DECIMAL, m.getColumnType(3));
                    assertEquals(BigDecimal.class.getName(), m.getColumnClassName(3));

                    // 4. AnyRecord.forRow -> rs.next() i rs.getObject(i)
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertFalse(rs.wasNull());
                    assertEquals("Faktura A", rs.getString(2));
                    assertEquals(new BigDecimal("1234.50"), rs.getBigDecimal(3));

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                    assertNull(rs.getString(2));
                    assertTrue(rs.wasNull());
                    assertEquals(new BigDecimal("0.00"), rs.getBigDecimal(3));

                    assertFalse(rs.next());
                }

                // 5. AuZrodloDanych.java:983 -> statement.cancel() (nie może rzucić wyjątku!)
                stmt.cancel();
            }

            // 6. AuZrodloDanych.java:910 -> statementAfterRow.execute()
            try (PreparedStatement stmtAfterRow = conn.prepareStatement("UPDATE DOKUMENTY SET STATUS = 1 WHERE ID = ?")) {
                stmtAfterRow.setInt(1, 1);
                assertFalse(stmtAfterRow.execute());
                assertEquals(1, stmtAfterRow.getUpdateCount());
            }

            // 7. AuZrodloDanych.java:929 -> conn.commit()
            conn.commit();

            // 8. AuZrodloDanych.java:1006 -> conn.rollback()
            conn.rollback();
        }
    }
}
