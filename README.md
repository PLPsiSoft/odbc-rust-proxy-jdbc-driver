# Rust ODBC Proxy JDBC Driver (`pl.psisoft.odbc-rust-proxy`)

Lekki sterownik JDBC (dla Java 21+) umożliwiający 64-bitowym aplikacjom Java łączenie się z **32-bitowymi źródłami danych ODBC** (np. dBase, FoxPro, stare bazy eFKa) za pośrednictwem lokalnego lub sieciowego mikroserwisu [`odbc-rust-proxy`](https://github.com/PLPsiSoft/odbc-rust-proxy).

Stanowi bezpośredni, nowoczesny zamiennik dla wycofanego w Java 8 systemowego mostka `sun.jdbc.odbc.JdbcOdbcDriver`.

---

## Główne cechy

* **Brak konieczności używania 32-bitowej maszyny JVM**: Aplikacja Java działa w nowoczesnej 64-bitowej JVM, a 32-bitowe biblioteki DLL sterowników ODBC są obsługiwane w osobnym procesie proxy.
* **Kompatybilność z legacy JDBC**: Obsługuje zarówno nowy schemat URL `jdbc:rustodbc://`, jak i tradycyjne adresy `jdbc:odbc:`.
* **Automatyczna rejestracja (Java SPI)**: Gotowy do użycia przez `java.sql.DriverManager` bez konieczności ręcznego ładowania klasy.
* **Pełne wsparcie transakcji**: Obsługa `setAutoCommit(false)`, `commit()` i `rollback()` z gwarancją spójności na dedykowanym wątku sesji.
* **Zapytania sparametryzowane**: `PreparedStatement` z obsługą typów `setString`, `setInt`, `setDouble`, `setBigDecimal`, `setDate`, `setTimestamp` oraz poprawnym wiązaniem `NULL` (`setNull`).
* **Stronicowanie kursorów (`fetchSize`)**: Bezpieczne pobieranie dużych zbiorów danych partiami bez ryzyka przepełnienia pamięci RAM.
* **Polskie strony kodowe**: Transparentne dekodowanie CP852 (DOS Latin-2 / `EE8PC852`), Windows-1250 (`EE8MSWIN1250`), ISO-8859-2 oraz UTF-8.

---

## Formaty połączenia (JDBC URL)

### 1. Dedykowany format `jdbc:rustodbc://`
```text
jdbc:rustodbc://[host:port]/DSN[?parametry]
```
Przykłady:
```java
// Domyślny port proxy 8097, źródło DSN "Test"
String url = "jdbc:rustodbc://127.0.0.1:8097/Test";

// Z podaniem kodowania DOS CP852 dla starych baz dBase/FoxPro
String url = "jdbc:rustodbc://127.0.0.1:8097/Test?charset=cp852";

// Zdalny serwer proxy
String url = "jdbc:rustodbc://192.168.1.50:8097/FirmaDSN?charset=cp1250";
```

### 2. Tradycyjny format `jdbc:odbc:` (Drop-in Replacement)
Sterownik przejmuje obsługę standardowych adresów URL:
```text
jdbc:odbc:DSN[?parametry]
```
Przykłady:
```java
String url = "jdbc:odbc:Test";
String url = "jdbc:odbc:Test?charset=cp852";
```
*Domyślnie sterownik kieruje ruch na `http://127.0.0.1:8097`. Adres serwera proxy można zmienić globalną właściwością JVM: `-Drustodbc.proxy.url=http://host:port`.*

---

## Przykład użycia

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

public class PrzykladUzycia {
    public static void main(String[] args) throws Exception {
        // Parametry połączenia i kodowania
        Properties props = new Properties();
        props.put("charset", "cp852"); // lub EE8PC852
        props.put("user", "admin");
        props.put("password", "tajne");

        String url = "jdbc:rustodbc://127.0.0.1:8097/Test";

        try (Connection conn = DriverManager.getConnection(url, props)) {
            // Kontrola transakcji
            conn.setAutoCommit(false);

            String sql = "SELECT DOKUMENT, TRESC, WARTOSC, MIASTO FROM EFKA WHERE DOKUMENT = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, 1);
                stmt.setFetchSize(50); // pobieranie paczkami po 50 wierszy

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int nrDokumentu = rs.getInt("DOKUMENT");
                        String tresc = rs.getString("TRESC");
                        double wartosc = rs.getDouble("WARTOSC");
                        String miasto = rs.getString("MIASTO"); // automatycznie poprawne polskie znaki

                        System.out.printf("Dokument: %d | %s | %.2f zł | %s\n",
                                nrDokumentu, tresc, wartosc, miasto);
                    }
                }
            }

            conn.commit();
        }
    }
}
```

---

## Kompilacja i budowanie

Wymagania: **JDK 21+** oraz **Maven 3.8+**.

```cmd
# Uruchomienie testów jednostkowych i integracyjnych:
mvn test

# Zbudowanie pliku JAR sterownika:
mvn clean package
```

Wynikowy plik JAR:
`target/pl.psisoft.odbc-rust-proxy-0.0.1.jar`

---

## Licencja

Projekt objęty jest licencją **Apache License 2.0**. Szczegóły znajdują się w pliku [`LICENSE`](./LICENSE).
