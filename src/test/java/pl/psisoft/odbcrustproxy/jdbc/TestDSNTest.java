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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class TestDSNTest {

    private static final String URL = "jdbc:rustodbc://127.0.0.1:8097/test";

    @BeforeAll
    public static void checkPrerequisites() {
        boolean proxyUp = isProxyAvailable("127.0.0.1", 8097);
        assumeTrue(proxyUp, "Pominięto TestDSNTest: Serwer Rust ODBC Proxy nie jest uruchomiony na 127.0.0.1:8097.");
    }

    private static boolean isProxyAvailable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("Weryfikacja pełnego zapytania SELECT * FROM EFKA (metadane, dane, polskie znaki, NULL-e)")
    public void testSelectAllAndVerifyMetadataAndData() throws Exception {
        Class.forName("pl.psisoft.odbcrustproxy.jdbc.RustOdbcDriver");

        try (Connection conn = DriverManager.getConnection(URL, null, null)) {
            assertNotNull(conn, "Połączenie nie powinno być null");
            assertFalse(conn.isClosed(), "Połączenie powinno być aktywne");

            String sql = "SELECT * FROM EFKA";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                // 1. Weryfikacja metadanych kolumn
                ResultSetMetaData meta = rs.getMetaData();
                assertNotNull(meta, "Metadane ResultSet nie mogą być null");
                assertEquals(101, meta.getColumnCount(), "Tabela EFKA powinna mieć dokładnie 101 kolumn");

                assertEquals("TYPOPER", meta.getColumnName(1));
                assertEquals("MAGAZYN", meta.getColumnName(2));
                assertEquals("OPERACJA", meta.getColumnName(3));
                assertEquals("DOKUMENT", meta.getColumnName(4));
                assertEquals("TRESC", meta.getColumnName(5));
                assertEquals("MIASTO", meta.getColumnName(17));
                assertEquals("WARTOSC", meta.getColumnName(21));
                assertEquals("NAZ_DLUGA", meta.getColumnName(25));
                assertEquals("NR_SKOJ_1", meta.getColumnName(98));
                assertEquals("KPR_NRDOK", meta.getColumnName(99));
                assertEquals("JPKV_GTU", meta.getColumnName(100));
                assertEquals("KSEF_NR", meta.getColumnName(101));

                assertEquals("java.lang.Double", meta.getColumnClassName(4), "Kolumna numeryczna DOKUMENT powinna być Double");
                assertEquals("java.lang.String", meta.getColumnClassName(5), "Kolumna tekstowa TRESC powinna być String");

                // 2. Weryfikacja pierwszego rekordu (Dokument 26/1)
                assertTrue(rs.next(), "Tabela powinna zawierać przynajmniej jeden wiersz");

                // Typy numeryczne
                assertEquals(10000000001L, rs.getLong("TYPOPER"));
                assertEquals(1, rs.getInt("MAGAZYN"));
                assertEquals(2, rs.getInt("OPERACJA"));
                assertEquals(1, rs.getInt("DOKUMENT"));
                assertEquals(1.0, rs.getDouble("DOKUMENT"), 0.001);
                assertEquals(1, rs.getInt(4), "Pobieranie po indeksie 1-based (kolumna 4 DOKUMENT)");

                // Kwoty i wartości finansowe
                assertEquals(190.33, rs.getDouble("WARTOSC"), 0.001);
                assertEquals(new BigDecimal("190.33"), rs.getBigDecimal("WARTOSC"));
                assertEquals(154.74, rs.getDouble("NETTO_23"), 0.001);
                assertEquals(35.59, rs.getDouble("VAT_23"), 0.001);

                // Pola tekstowe
                assertEquals("26/1", rs.getString("TRESC"));
                assertEquals("00050", rs.getString("KOD_KONTR1"));
                assertEquals("00050", rs.getString("KOD_KLIENT"));
                assertEquals("PSI SOFT", rs.getString("NAZ_KONTRA"));
                assertEquals("51-149", rs.getString("PNA"));
                assertEquals("ul. Koszarowa 7", rs.getString("ULICA"));
                assertEquals("8952191856", rs.getString("NIP"));
                assertEquals("G", rs.getString("SPOS_ZAPL"));
                assertEquals("1PSI", rs.getString("MAGZREK"));
                assertEquals("f", rs.getString("TYPD"));
                assertEquals("<B>", rs.getString("SYMBOL"));
                assertEquals("<B>/1/1PSI/26", rs.getString("KPR_NRDOK"));

                // Polskie znaki diakrytyczne (ł, Ó, Ł, Ą, Ś)
                assertEquals("Wrocław", rs.getString("MIASTO"), "Wartość kolumny MIASTO powinna zawierać poprawny znak 'ł'");
                assertEquals("PSI SOFT SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ", rs.getString("NAZ_DLUGA"),
                        "Wartość kolumny NAZ_DLUGA powinna zawierać polskie znaki: Ó, Ł, Ą, Ś");

                // Wartości NULL i flaga wasNull()
                assertNull(rs.getString("GRUPA"), "Pole GRUPA powinno być NULL");
                assertTrue(rs.wasNull(), "wasNull() powinno zwrócić true dla pola GRUPA");

                assertNull(rs.getString("DOD_DOK"), "Pole DOD_DOK powinno być NULL");
                assertTrue(rs.wasNull(), "wasNull() powinno zwrócić true dla pola DOD_DOK");

                assertNull(rs.getString("MAGKASA"), "Pole MAGKASA powinno być NULL");
                assertTrue(rs.wasNull(), "wasNull() powinno zwrócić true dla pola MAGKASA");

                // 3. Sprawdzenie liczby wszystkich rekordów w tabeli (dokładnie 20 w pliku efka.dbf)
                int totalRows = 1;
                while (rs.next()) {
                    totalRows++;
                    // Kontrola czy pole DOKUMENT jest zawsze liczbą
                    assertTrue(rs.getInt("DOKUMENT") > 0);
                    assertNotNull(rs.getString("TRESC"));
                }
                assertEquals(20, totalRows, "Tabela EFKA powinna zawierać dokładnie 20 rekordów");
            }
        }
    }

    @Test
    @DisplayName("Weryfikacja zapytania z parametrem PreparedStatement (WHERE DOKUMENT = ?)")
    public void testParameterizedQuery() throws Exception {
        Class.forName("pl.psisoft.odbcrustproxy.jdbc.RustOdbcDriver");

        try (Connection conn = DriverManager.getConnection(URL, null, null)) {
            String sql = "SELECT DOKUMENT, TRESC, WARTOSC, MIASTO FROM EFKA WHERE DOKUMENT = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, 1);

                try (ResultSet rs = stmt.executeQuery()) {
                    int matchCount = 0;
                    while (rs.next()) {
                        matchCount++;
                        assertEquals(1, rs.getInt("DOKUMENT"), "Każdy zwrócony wiersz musi mieć DOKUMENT = 1");
                        assertNotNull(rs.getString("TRESC"));
                    }
                    assertEquals(13, matchCount, "Dokładnie 13 rekordów w EFKA ma DOKUMENT = 1");
                }
            }
        }
    }

    @Test
    @DisplayName("Weryfikacja stronicowania kursora (fetchSize = 5)")
    public void testPaginationWithFetchSize() throws Exception {
        Class.forName("pl.psisoft.odbcrustproxy.jdbc.RustOdbcDriver");

        try (Connection conn = DriverManager.getConnection(URL, null, null)) {
            String sql = "SELECT DOKUMENT, TRESC FROM EFKA";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setFetchSize(5);

                try (ResultSet rs = stmt.executeQuery()) {
                    int totalFetched = 0;
                    while (rs.next()) {
                        totalFetched++;
                        assertNotNull(rs.getString("TRESC"));
                    }
                    assertEquals(20, totalFetched, "Pobieranie paczkami po 5 wierszy powinno pobrać łącznie wszystkie 20 rekordów");
                }
            }
        }
    }

    @Test
    @DisplayName("Weryfikacja metadanych bazy danych DatabaseMetaData")
    public void testDatabaseMetaData() throws Exception {
        Class.forName("pl.psisoft.odbcrustproxy.jdbc.RustOdbcDriver");

        try (Connection conn = DriverManager.getConnection(URL, null, null)) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            assertNotNull(dbMeta);

            assertNotNull(dbMeta.getUserName());
            assertEquals("ODBC (test)", dbMeta.getDatabaseProductName());
            assertEquals("Rust ODBC Proxy JDBC Driver", dbMeta.getDriverName());
            assertNotNull(dbMeta.getDriverVersion());
            assertTrue(dbMeta.supportsTransactions());
            assertTrue(dbMeta.supportsBatchUpdates());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, dbMeta.getDefaultTransactionIsolation());
        }
    }
}
