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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Properties;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

public class RustOdbcDriverTest {

    private static HttpServer mockServer;
    private static int mockPort;

    @BeforeAll
    public static void startMockServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();
        registerMockRoutes(mockServer);
        mockServer.start();
    }

    private static void registerMockRoutes(HttpServer server) {
        server.createContext("/session/connect", exchange -> sendJson(exchange, "{\"session_id\":\"test_session_123\"}"));
        server.createContext("/session/autocommit", exchange -> sendJson(exchange, "{\"status\":\"ok\"}"));
        server.createContext("/session/commit", exchange -> sendJson(exchange, "{\"status\":\"ok\"}"));
        server.createContext("/session/rollback", exchange -> sendJson(exchange, "{\"status\":\"ok\"}"));
        server.createContext("/session/ping", exchange -> sendJson(exchange, "{\"alive\":true}"));
        server.createContext("/session/close", exchange -> sendJson(exchange, "{\"status\":\"ok\"}"));
        server.createContext("/query", exchange -> sendJson(exchange, """
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
            """));
        server.createContext("/execute", exchange -> sendJson(exchange, "{\"affected_rows\":1}"));
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, String resp) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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
        // Symulacja DOKŁADNEGO przepływu z AuZrodloDanych i AuSql w efkawww (z jawnym tls=false dla mocka HTTP)
        String url = "jdbc:rustodbc://127.0.0.1:" + mockPort + "/test_dsn?charset=cp852&tls=false";

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

    @Test
    public void testTlsCertificatePinningAndGlobalIsolation(@TempDir Path tempDir) throws Exception {
        SSLContext defaultSslBefore = SSLContext.getDefault();
        SSLSocketFactory defaultSocketFactoryBefore = HttpsURLConnection.getDefaultSSLSocketFactory();
        String trustStorePropBefore = System.getProperty("javax.net.ssl.trustStore");

        CertPair pairA = generateSelfSignedCertPair(tempDir, "serverA");
        CertPair pairB = generateSelfSignedCertPair(tempDir, "serverB");

        // Uruchomienie mocka HTTPS używającego wyłącznie certyfikatu A
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(pairA.p12Path())) {
            ks.load(is, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverSsl));
        registerMockRoutes(httpsServer);
        httpsServer.start();
        int httpsPort = httpsServer.getAddress().getPort();

        try {
            // 1. Przypadek pozytywny: przypięty właściwy certA.pem -> działa przez 127.0.0.1
            String urlA = "jdbc:rustodbc://127.0.0.1:" + httpsPort + "/test_dsn?tlsCert="
                    + pairA.pemPath().toAbsolutePath();
            try (Connection conn = DriverManager.getConnection(urlA, null)) {
                assertTrue(conn.isValid(5));
                try (PreparedStatement stmt = conn.prepareStatement("SELECT 1");
                     ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Faktura A", rs.getString(2));
                }
            }

            // 2. Przypadek pozytywny po DNS:localhost (weryfikacja pola SAN w certyfikacie)
            String urlLocalhost = "jdbc:rustodbc://localhost:" + httpsPort + "/test_dsn";
            Properties propsA = new Properties();
            propsA.put("tlsCert", pairA.pemPath().toAbsolutePath().toString());
            try (Connection conn = DriverManager.getConnection(urlLocalhost, propsA)) {
                assertTrue(conn.isValid(5));
            }

            // 3. Przypadek negatywny (PSK): przypięty inny certyfikat certB.pem -> serwer z certA musi zostać odrzucony
            String urlWrongCert = "jdbc:rustodbc://127.0.0.1:" + httpsPort + "/test_dsn?tlsCert="
                    + pairB.pemPath().toAbsolutePath();
            SQLException wrongCertEx = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(urlWrongCert, null));
            assertTrue(
                    hasCause(wrongCertEx, SSLHandshakeException.class)
                            || wrongCertEx.getMessage().contains("PKIX")
                            || wrongCertEx.getMessage().contains("handshake")
                            || wrongCertEx.getMessage().contains("certificate"),
                    "Oczekiwano błędu weryfikacji TLS (Certificate Pinning), otrzymano: " + wrongCertEx
            );

            // 4. Przypadek Fail-Closed (Opcja A): brak certyfikatu bez ?tls=false -> czytelny wyjątek
            String missingCertUrl = "jdbc:rustodbc://127.0.0.1:" + httpsPort + "/test_dsn?tlsCert="
                    + tempDir.resolve("nie_istnieje.pem").toAbsolutePath();
            SQLException missingEx = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(missingCertUrl, null));
            assertTrue(missingEx.getMessage().contains("Fail-Closed"));

            // 5. Gwarancja pełnej izolacji: globalny SSLContext/cacerts JVM pozostał nienaruszony!
            assertSame(defaultSslBefore, SSLContext.getDefault(),
                    "Sterownik JDBC nie może zmieniać globalnego SSLContext.getDefault()");
            assertSame(defaultSocketFactoryBefore, HttpsURLConnection.getDefaultSSLSocketFactory(),
                    "Sterownik JDBC nie może zmieniać HttpsURLConnection.getDefaultSSLSocketFactory()");
            assertEquals(trustStorePropBefore, System.getProperty("javax.net.ssl.trustStore"),
                    "Sterownik JDBC nie może modyfikować właściwości javax.net.ssl.trustStore");

            // Domyślny HttpClient aplikacji (korzystający z globalnego cacerts) nadal odrzuca self-signed certA,
            // co dowodzi, że certA nie wyciekł do globalnego magazynu zaufania JVM:
            HttpClient appDefaultClient = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://127.0.0.1:" + httpsPort + "/session/ping"))
                    .GET()
                    .build();
            assertThrows(SSLHandshakeException.class,
                    () -> appDefaultClient.send(req, HttpResponse.BodyHandlers.ofString()),
                    "Domyślny klient JVM nie powinien ufać przypiętemu certyfikatowi sterownika JDBC");
        } finally {
            httpsServer.stop(0);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> expectedType) {
        Throwable curr = t;
        while (curr != null) {
            if (expectedType.isInstance(curr)) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private record CertPair(Path p12Path, Path pemPath) {
    }

    private static CertPair generateSelfSignedCertPair(Path dir, String name) throws Exception {
        Path keytoolExe = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        String keytool = Files.exists(keytoolExe) ? keytoolExe.toString() : "keytool";

        Path p12 = dir.resolve(name + ".p12");
        Path pem = dir.resolve(name + ".pem");

        runProcess(
                keytool, "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", p12.toString(),
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-noprompt"
        );

        runProcess(
                keytool, "-exportcert",
                "-alias", "server",
                "-keystore", p12.toString(),
                "-storepass", "changeit",
                "-rfc",
                "-file", pem.toString()
        );

        return new CertPair(p12, pem);
    }

    private static void runProcess(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Polecenie " + String.join(" ", cmd)
                    + " zakończyło się kodem " + code + ": " + output);
        }
    }
}
